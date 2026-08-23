package com.decade.doj.sandbox.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 判题任务对象 — 在 Redis 队列中传递的数据结构。
 * Controller 组装 → JSON → Redis List → JudgingWorker → SandboxService
 * @Accessors(chain = true) 开启链式调用
 */
@Data
@Accessors(chain = true)
public class JudgingTask {

    Long submissionId;
    String localPath;
    String input;
    String output;
    List<String> inputFileNames;
    List<String> outputAnswers;
    String filename;
    String lang;
    Long problemId;
    String code;
    Long uid;
}
