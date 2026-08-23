package com.decade.doj.sandbox.worker;

import com.alibaba.fastjson.JSON;
import com.decade.doj.sandbox.domain.vo.ExecuteMessage;
import com.decade.doj.sandbox.domain.vo.JudgingTask;
import com.decade.doj.sandbox.service.ISandboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 判题任务消费者
 * <p>
 * 使用单一调度线程从 Redis 队列轮询任务，并将任务分发到 JudgingThreadPool 执行。
 * 实现 DisposableBean 接口支持优雅关闭。
 */
@Slf4j
@Component
//调度线程+判题线程池
//线程池减少线程创建开销，复用线程，有核心、最大线程数、队列长度限制，不会耗尽内存
public class JudgingWorker implements ApplicationRunner, DisposableBean {

    private final StringRedisTemplate redisTemplate;
    private final ISandboxService sandboxService;
    private final ThreadPoolTaskExecutor judgingExecutor;
    private final RabbitTemplate rabbitTemplate;

    private static final String JUDGING_QUEUE_KEY = "judging:queue";
    private static final int POLL_TIMEOUT_SECONDS = 5;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread schedulerThread;

    public JudgingWorker(
            StringRedisTemplate redisTemplate,
            ISandboxService sandboxService,
            @Qualifier("JudgingThreadPool") ThreadPoolTaskExecutor judgingExecutor,
            RabbitTemplate rabbitTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.sandboxService = sandboxService;
        this.judgingExecutor = judgingExecutor;
        this.rabbitTemplate = rabbitTemplate;
    }

//    容器启动时执行run,继承applicationrunner
    @Override
    public void run(ApplicationArguments args) {
//        启动runnable线程，JudgingScheduler
        schedulerThread = new Thread(this::pollAndDispatch, "JudgingScheduler");
        schedulerThread.start();
        log.info("判题调度器已启动，任务将分发到 JudgingThreadPool 执行");
    }

    /**
     * 轮询 Redis 队列并分发任务到线程池
     */
    private void pollAndDispatch() {
        while (running.get()) {
            try {
//                runnable->time_waiting
                String taskJson = redisTemplate.opsForList()
                        .rightPop(JUDGING_QUEUE_KEY, POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
//                time_waiting->runnable
                if (taskJson != null) {
                    JudgingTask task = JSON.parseObject(taskJson, JudgingTask.class);
                    log.info("收到判题任务: submissionId={}, problemId={}", 
                            task.getSubmissionId(), task.getProblemId());

                    // 分发到线程池异步执行
                    judgingExecutor.execute(() -> executeTask(task));
                }
            } catch (Exception e) {
                if (running.get()) {
//                    如果系统还在运行，由于redis连接异常，json解析失败等捕获异常，打印error日志
                    log.error("轮询判题队列出现异常", e);
                    // 短暂休眠避免错误风暴
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        //sleep被中断，结束循环，调度线程停止循环
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.info("判题调度器已停止轮询");
    }

    /**
     * 执行单个判题任务。
     * <p>
     * 【兜底机制】任何未预期的异常都会导致发送 System Error 到 MQ，
     * 确保 submission 不会永远停留在 PENDING 状态。
     */
    private void executeTask(JudgingTask task) {
        try {
            log.debug("开始执行判题任务: submissionId={}", task.getSubmissionId());
            sandboxService.execute(task);
            log.debug("判题任务执行完成: submissionId={}", task.getSubmissionId());
        } catch (Exception e) {
            log.error("执行判题任务异常: submissionId={}, problemId={}", 
                    task.getSubmissionId(), task.getProblemId(), e);
            // 【兜底】判题任务执行过程中发生未预期异常（如 K8s API 故障、网络抖动等），
            // 发送 System Error 状态到 MQ，避免提交记录永久 PENDING
            try {
                ExecuteMessage errorResult = new ExecuteMessage()
                        .setExitValue(1)
                        .setStatus("System Error")
                        .setMessage("判题系统内部错误: " + e.getMessage());
                Map<String, Object> resultMessage = Map.of(
                        "submissionId", task.getSubmissionId(),
                        "executeMessage", errorResult
                );
                rabbitTemplate.convertAndSend("doj.topic", "judging.result", resultMessage);
                log.info("已发送 System Error 到 MQ, submissionId={}", task.getSubmissionId());
            } catch (Exception mqEx) {
                log.error("发送 System Error MQ 消息失败, submissionId={}", 
                        task.getSubmissionId(), mqEx);
            }
        }
    }
//容器结束时destroy,disposablebean
    @Override
    public void destroy() {
        log.info("正在关闭判题调度器...");
        running.set(false);

        if (schedulerThread != null) {
//            唤醒scheduler线程，time_waiting->runnable
            schedulerThread.interrupt();
            try {
                // 主线程等待调度线程终止
                schedulerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("判题调度器已关闭，线程池将由 Spring 容器管理关闭");
    }
}

