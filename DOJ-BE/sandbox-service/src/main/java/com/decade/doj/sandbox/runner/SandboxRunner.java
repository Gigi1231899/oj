package com.decade.doj.sandbox.runner;

/**
 * ============================================================
 * 沙箱执行器接口 — 代码隔离执行的核心抽象
 * ============================================================
 *
 * 职责：在隔离环境中执行用户代码，返回运行结果。
 * 当前实现：K8sSandboxRunner（通过 K8s Job + gVisor 沙箱执行）
 * 备选实现：DockerSandboxRunner（通过 docker run 执行，已废弃）
 *
 * @param imageName        容器镜像名（如 python:3.11-slim, gcc:13）
 * @param hostCodeDir      宿主机代码目录（会被 hostPath 挂载到容器内 mountPath）
 * @param mountPath        容器内挂载路径（统一为 /app）
 * @param execCmd          在容器内执行的 shell 命令
 * @param memoryLimitMb    内存限制（MB），映射为 K8s resources.limits.memory
 * @param timeLimitSeconds 时间限制（秒），映射为 K8s activeDeadlineSeconds
 * @param jobId            唯一任务 ID，用于构造 K8s Job 名称
 * @return SandboxResult   包含退出码、完整输出、是否超时
 * ============================================================
 */
public interface SandboxRunner {
    SandboxResult runAndWait(
            String imageName,
            String hostCodeDir,
            String mountPath,
            String execCmd,
            int memoryLimitMb,
            double timeLimitSeconds,
            String jobId);
}
