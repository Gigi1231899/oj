package com.decade.doj.sandbox.service;

import com.decade.doj.sandbox.domain.vo.JudgingTask;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface ISandboxService {

    // 说明：run 功能（runCodeInSandbox）已整体下线，仅保留提交判题与测试用例生成

    void execute(JudgingTask task) throws IOException;

    List<Map<String, String>> generateTestCases(String checkerConfig, String standardCode,
                                                 String standardLang, int rounds) throws IOException;
}
