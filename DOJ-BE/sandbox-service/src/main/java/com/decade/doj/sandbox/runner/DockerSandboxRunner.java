package com.decade.doj.sandbox.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker 判题执行器：通过 ProcessBuilder 调用 docker run，本地开发使用。
 * 需要 Docker Desktop 运行中，且已构建好判题镜像（code-runner-python / code-runner-java / myoj_time:1.0）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "doj.sandbox.runner", havingValue = "docker", matchIfMissing = true)
public class DockerSandboxRunner implements SandboxRunner {

    @PostConstruct
    public void init() {
        log.info("DockerSandboxRunner 初始化成功（本地 Docker 模式）");
    }

    @Override
    public SandboxResult runAndWait(
            String imageName,
            String hostCodeDir,
            String mountPath,
            String execCmd,
            int memoryLimitMb,
            double timeLimitSeconds,
            String jobId
    ) {
        String hostDir = new File(hostCodeDir).getAbsolutePath();
        String containerName = "doj-" + jobId;
//        docker run --rm --name xx \
//        --network=none \
//        -w 工作目录 \
//        --memory xxm \
//        -v : \
//        imageName \
//        sh -c execCmd
        List<String> cmd = new ArrayList<>(Arrays.asList(
                "docker", "run", "--rm",
                "--name", containerName,
                "--network=none",
                "-w", mountPath,
                "--memory=" + memoryLimitMb + "m",
                "-v", hostDir + ":" + mountPath,
                imageName,
                "sh", "-c", execCmd
        ));

        log.info("Docker 执行: {}", String.join(" ", cmd));

        long deadlineSeconds = (long) timeLimitSeconds + 30;

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 并发读取 stdout，避免管道缓冲区满导致死锁
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Thread readerThread = new Thread(() -> {
                try {
                    process.getInputStream().transferTo(stdout);
                } catch (IOException ignored) {
                }
            }, "docker-reader-" + jobId);
            readerThread.start();

            boolean finished = process.waitFor(deadlineSeconds, TimeUnit.SECONDS);

            if (!finished) {
                killContainer(containerName);
                process.destroyForcibly();
                readerThread.interrupt();
                log.warn("Docker 容器超时未完成，已终止: containerName={}", containerName);
                return SandboxResult.timeout(deadlineSeconds);
            }
//            runnable->time_waiting，让readerthread自己死掉
            readerThread.join(5000);
            String output = stdout.toString(StandardCharsets.UTF_8);
            int exitCode = process.exitValue();

            log.debug("Docker 执行完成, exitCode={}", exitCode);
            return new SandboxResult(exitCode, output, false);

        } catch (IOException e) {
            log.error("Docker 执行失败（Docker Desktop 是否在运行？）", e);
            return SandboxResult.error("Docker 执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(containerName);
            return SandboxResult.error("Docker 执行被中断");
        }
    }

    private void killContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "kill", containerName)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            log.debug("docker kill 失败（容器可能已退出）: {}", containerName);
        }
    }
}
