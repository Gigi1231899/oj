package com.decade.doj.sandbox.runner;

/**
 * ============================================================
 * 沙箱运行结果 — SandboxRunner 的统一返回值
 * ============================================================
 *
 * 包含三层信息：
 *   1. exitCode: 容器退出码
 *      - 0 = 正常退出
 *      - 124 = timeout 命令超时杀死
 *      - 137 = OOM Killer 杀死
 *      - 其他 = 程序自身异常退出
 *   2. output: 完整的 stdout + stderr（含 /usr/bin/time -v 的资源统计输出）
 *      上层通过正则从中解析 "Maximum resident set size" 和 "Elapsed time"
 *   3. timeout: K8s/Docker 层面的超时标记
 *      true = Pod/容器未在 activeDeadlineSeconds 内完成，视为整体超时
 * ============================================================
 */
public class SandboxResult {
    private final int exitCode;       // 容器退出码
    private final String output;      // 完整的 stdout + stderr（含 /usr/bin/time -v 输出）
    private final boolean timeout;    // 运行层面是否超时（Pod 未在时限内完成）

    public SandboxResult(int exitCode, String output, boolean timeout) {
        this.exitCode = exitCode;
        this.output = output != null ? output : "";
        this.timeout = timeout;
    }

    /** 工厂方法：创建超时结果 */
    public static SandboxResult timeout(long deadlineSeconds) {
        return new SandboxResult(124, "", true);
    }

    /** 工厂方法：创建错误结果（如 K8s API 异常） */
    public static SandboxResult error(String message) {
        return new SandboxResult(1, message, false);
    }

    public int getExitCode() { return exitCode; }
    public String getOutput() { return output; }
    public boolean isTimeout() { return timeout; }
}
