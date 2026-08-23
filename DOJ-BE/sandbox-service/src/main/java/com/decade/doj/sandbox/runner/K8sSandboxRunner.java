package com.decade.doj.sandbox.runner;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.ClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ============================================================
 * 【架构变更】K8s Job 判题执行器（替代 docker run + docker.sock）
 * ============================================================
 * 原方案：通过挂载宿主机 docker.sock，ProcessBuilder 执行 "docker run ..."
 *         安全隐患：docker.sock = root 权限，容器逃逸风险
 *
 * 新方案：调用 K8s API 创建 Job，每个判题任务 = 一个 K8s Job
 *         Job 运行在 gVisor (runsc) RuntimeClass 节点上，强隔离
 *
 * 判题 Job 生命周期：
 *   1. 创建 Job（用户代码 + 输入数据通过共享 PVC（Azure Files, RWX）挂载，subPath 隔离每个任务）
 *   2. 等待 Pod 完成（轮询 Pod 状态）
 *   3. 读取容器日志（stdout = /usr/bin/time -v 输出）
 *   4. 删除 Job + ConfigMap（清理资源）
 * ============================================================
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "doj.sandbox.runner", havingValue = "k8s")
public class K8sSandboxRunner implements SandboxRunner {

    private static final String NAMESPACE = "doj";
    // 【gVisor】判题 Job 使用 runsc RuntimeClass，确保在 gVisor 隔离的沙箱中运行。
    // 注意：若集群未安装 gVisor（RuntimeClass 不存在），Job 的 Pod 创建会被拒绝
    // （"RuntimeClass runsc not found"），判题全部超时失败。
    // 通过环境变量 DOJ_SANDBOX_RUNTIME_CLASS 可配，置空则 Pod 不指定 runtimeClassName，
    // 使用集群默认运行时（runc）。生产环境如需 gVisor，先在节点安装并创建 RuntimeClass。
    private static final String RUNTIME_CLASS_NAME =
            System.getenv().getOrDefault("DOJ_SANDBOX_RUNTIME_CLASS", "");
    // 【Seccomp】系统调用过滤级别：
    //   - RuntimeDefault：使用容器运行时（containerd）默认 seccomp profile，开箱即用，
    //     拦截 mount/reboot/kexec_load/unshare 等危险 syscall，返回 EPERM。
    //   - Localhost：自定义白名单 profile（需先通过 DaemonSet 把 JSON 分发到每个节点
    //     /var/lib/kubelet/seccomp/，再配合 localhostProfile 引用），拦截返回 SIGSYS。
    private static final String SECCOMP_PROFILE_TYPE =
            System.getenv().getOrDefault("DOJ_SANDBOX_SECCOMP_PROFILE", "RuntimeDefault");
    private static final String SANDBOX_LABEL = "sandbox-job";
    private static final String JOB_PREFIX = "doj-judge-";
    // 【ACR】判题 Job 从 ACR 拉取运行时镜像，必须携带 imagePullSecret
    // 与 values.yaml 中 global.imagePullSecrets[0].name（acr-secret）保持一致
    private static final String IMAGE_PULL_SECRET =
            System.getenv().getOrDefault("DOJ_SANDBOX_IMAGE_PULL_SECRET", "acr-secret");
    // 【PVC】判题代码共享卷名称，与 Helm chart 中 sandbox.codeStorage.pvcName 保持一致
    // 判题 Pod 通过 subPath 把 PVC 中本任务专属的 <uuid> 子目录挂载到容器 /app
    private static final String CODE_PVC_NAME =
            System.getenv().getOrDefault("DOJ_CODE_PVC_NAME", "doj-code-pvc");

    private ApiClient apiClient;
    private BatchV1Api batchV1Api;
    private CoreV1Api coreV1Api;

    @PostConstruct
    public void init() {
        try {
            apiClient = ClientBuilder.standard().build();
            log.info("K8sSandboxRunner 初始化成功");
            Configuration.setDefaultApiClient(apiClient);
            batchV1Api = new BatchV1Api(apiClient);
            coreV1Api = new CoreV1Api(apiClient);
            log.info("K8sSandboxRunner 就绪, namespace={}", NAMESPACE);
        } catch (IOException e) {
            log.error("K8sSandboxRunner 初始化失败，K8s API 不可用", e);
            throw new RuntimeException("无法连接 K8s API Server", e);
        }
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
        String jobName = JOB_PREFIX + jobId;
        // 【Job 超时】activeDeadlineSeconds = 判题时限 + 缓冲（编译 + 启动开销）
        long deadlineSeconds = (long) timeLimitSeconds + 20;

        try {
            // 1. 创建 Job
            V1Job job = buildJudgeJob(jobName, imageName, hostCodeDir, mountPath,
                    execCmd, memoryLimitMb, deadlineSeconds);
            batchV1Api.createNamespacedJob(NAMESPACE, job, null, null, null, null);
            log.debug("判题 Job 已创建: {}", jobName);

            // 2. 等待 Pod 完成
            V1Pod pod = waitForPod(jobName, deadlineSeconds + 30);
//            pod在deadline内没有完成，compile就返回编译失败
            if (pod == null) {
                // 超时未完成 → 视为 TLE
                log.warn("判题 Job {} 在 {}s 内未完成，判定超时", jobName, deadlineSeconds);
                return SandboxResult.timeout(deadlineSeconds);
            }
//能在deadline内完成编译，就返回退出吗+日志+未超时的沙箱执行结果
            // 3. 读取容器日志（stdout 包含 /usr/bin/time -v 的完整输出）
//            kubectl logs -f xx,pod -n doj
            String podName = pod.getMetadata().getName();
            String logs = coreV1Api.readNamespacedPodLog(
                    podName, NAMESPACE, null, null, null,
                    null, null, null, null, null, null);

            // 4. 获取退出码
            int exitCode = getContainerExitCode(pod);
            log.debug("Job {} 完成, exitCode={}, podName={}", jobName, exitCode, podName);

//            final块会在return之前执行，先销毁
            return new SandboxResult(exitCode, logs, false);

        } catch (ApiException e) {
            // HTTP 409 Conflict: Job 已存在（重试/幂等场景），先删再重试一次
            if (e.getCode() == 409) {
                log.warn("Job {} 已存在，删除后重试", jobName);
                deleteJobSilently(jobName);
                try {
                    V1Job job = buildJudgeJob(jobName, imageName, hostCodeDir, mountPath,
                            execCmd, memoryLimitMb, deadlineSeconds);
                    batchV1Api.createNamespacedJob(NAMESPACE, job, null, null, null, null);
                    V1Pod pod = waitForPod(jobName, deadlineSeconds + 30);
                    if (pod == null) {
                        return SandboxResult.timeout(deadlineSeconds);
                    }
                    String podName = pod.getMetadata().getName();
                    String logs = coreV1Api.readNamespacedPodLog(
                            podName, NAMESPACE, null, null, null,
                            null, null, null, null, null, null);
                    int exitCode = getContainerExitCode(pod);
                    return new SandboxResult(exitCode, logs, false);
                } catch (Exception retryEx) {
                    log.error("重试 Job {} 失败", jobName, retryEx);
                    return SandboxResult.error("判题容器执行失败: " + retryEx.getMessage());
                }
            }
            log.error("创建判题 Job {} 失败, {}", jobName, buildApiErrorMsg(e), e);
            return SandboxResult.error("判题容器创建失败: " + buildApiErrorMsg(e));
        } catch (Exception e) {
            log.error("判题 Job {} 执行异常", jobName, e);
            return SandboxResult.error("判题执行异常: " + e.getMessage());
        } finally {
            // 5. 清理：删除 Job 和对应的 Pod
            deleteJobSilently(jobName);
        }
    }

    /**
     * 构建判题 K8s Job 对象。
     * 通过 spec.template.spec.runtimeClassName = "" 指定默认运行时。
     */
    private V1Job buildJudgeJob(
            String jobName,
            String imageName,
            String hostCodeDir,
            String mountPath,
            String execCmd,
            int memoryLimitMb,
            long deadlineSeconds
    ) {
        // 容器资源限制
        V1ResourceRequirements resources = new V1ResourceRequirements()
                .putLimitsItem("memory", new Quantity(memoryLimitMb + "Mi"))
                .putLimitsItem("cpu", new Quantity("1"))
                .putRequestsItem("memory", new Quantity("64Mi"))
                .putRequestsItem("cpu", new Quantity("100m"));

        // 卷挂载：代码目录
        // 【PVC + subPath】判题 Pod 挂载共享 PVC 中本任务专属的 <uuid> 子目录到 /app，
        // 而不是把整个共享卷暴露给判题容器（每个任务只看得到自己的代码，目录间隔离）
        // 【安全说明】Checker 模式需要在容器内写输出文件（通过 stdout 重定向 > /app/xxx.txt），
        
        String subPath = new File(hostCodeDir).getName();
        V1VolumeMount volumeMount = new V1VolumeMount()
                .name("code-volume")
                .mountPath(mountPath)
                .subPath(subPath)
                .readOnly(false);

        // 容器定义
        V1Container container = new V1Container()
                .name("judge")
                .image(imageName)
                .imagePullPolicy("IfNotPresent")
                // 【工作目录】必须与挂载路径一致，否则相对路径命令
                // （javac Main.java / g++ Main.cpp / ./Main.out / python3 Main.py）
                // 会基于镜像默认 WORKDIR（通常是 /）执行，找不到代码文件导致在线运行( run )不执行。
                // Docker 运行器通过 -w /app 已做相同处理，此处保持一致。
                .workingDir(mountPath)
                .command(Arrays.asList("sh", "-c", execCmd))
                .resources(resources)
                // 【安全·容器级】权限降级，阻断任何"操作 OS"的能力：
                // 1) capabilities：drop ALL，一个特权 cap 都不保留
                //    （无 CAP_NET_RAW 伪造网络包 / CAP_SYS_ADMIN 挂载设备 / CAP_SYS_BOOT 关机等）
                //    编译/运行代码只需 uid=1000 对 /app 的属主读写权限，不需要任何 cap。
                // 2) allowPrivilegeEscalation=false：禁止 setuid 提权（等效 no_new_privs）
                // 3) readOnlyRootFilesystem=true：根文件系统只读，容器内 /etc、/bin 等物理不可写
                .securityContext(new V1SecurityContext()
                        .capabilities(new V1Capabilities().addDropItem("ALL"))
                        .allowPrivilegeEscalation(false)
                        .readOnlyRootFilesystem(true))
                .addVolumeMountsItem(volumeMount)
                // 【tmp】rootfs 只读后，g++/javac/JVM 需要可写临时目录（/tmp），
                // 挂独立 emptyDir，编译产物仍写 /app（PVC 卷，不受 rootfs 只读影响）
                .addVolumeMountsItem(new V1VolumeMount()
                        .name("tmp")
                        .mountPath("/tmp"));

        // Pod 模板
        V1PodTemplateSpec podTemplate = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta()
                        .putLabelsItem("app", SANDBOX_LABEL)
                        .putLabelsItem("job-name", jobName))
                .spec(new V1PodSpec()
                        // 指定 runsc RuntimeClass（为空则用集群默认运行时 runc）
                        .runtimeClassName(RUNTIME_CLASS_NAME.isEmpty() ? null : RUNTIME_CLASS_NAME)
                        // 【ACR】私有仓库拉取凭证，否则 Job Pod 无法拉取运行时镜像
                        .addImagePullSecretsItem(new V1LocalObjectReference().name(IMAGE_PULL_SECRET))
                        .restartPolicy("Never")
                        .addContainersItem(container)
                        .addVolumesItem(new V1Volume()
                                .name("code-volume")
                                .persistentVolumeClaim(new V1PersistentVolumeClaimVolumeSource()
                                        .claimName(CODE_PVC_NAME)))
                        .addVolumesItem(new V1Volume()
                                .name("tmp")
                                .emptyDir(new V1EmptyDirVolumeSource()))
                        // 【安全】以非 root 用户运行（g++/javac/python3 均不需要 root 权限）
                       
                        .securityContext(new V1PodSecurityContext()
                                .runAsNonRoot(true)
                                .runAsUser(1000L)
                                .runAsGroup(1000L)
                                .fsGroup(1000L)
                                // 【安全·Seccomp】系统调用过滤：RuntimeDefault 使用 containerd 默认 profile，
                                // 高危 syscall（mount/reboot/kexec_load 等）被内核拦截（EPERM），
                                // 收窄内核漏洞利用面；可切换 Localhost 自定义白名单（需节点放 profile）
                                .seccompProfile(new V1SeccompProfile().type(SECCOMP_PROFILE_TYPE))));

        // Job 元数据
        V1ObjectMeta jobMeta = new V1ObjectMeta()
                .name(jobName)
                .namespace(NAMESPACE)
                .putLabelsItem("app", SANDBOX_LABEL);

        // Job 规格
        V1JobSpec jobSpec = new V1JobSpec()
                .ttlSecondsAfterFinished(60)  // 完成后 60s 自动清理
                .activeDeadlineSeconds(deadlineSeconds)
                .backoffLimit(0)  // 失败不重试
                .template(podTemplate);

        return new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(jobMeta)
                .spec(jobSpec);
    }

    /**
     * 轮询等待 Job 对应的 Pod 完成（Succeeded 或 Failed）。
     * 使用 label selector 精确匹配 Job。
     *
     * @return 完成的 Pod 对象，超时返回 null
     */
    private V1Pod waitForPod(String jobName, long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        String labelSelector = "job-name=" + jobName;

        while (System.currentTimeMillis() < deadline) {
            try {
                V1PodList podList = coreV1Api.listNamespacedPod(
                        NAMESPACE, null, null, null, null,
                        labelSelector, null, null, null, null, null);

                for (V1Pod pod : podList.getItems()) {
                    String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                    if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                        return pod;
                    }
                }
            } catch (ApiException e) {
                log.warn("查询 Pod 状态失败: {}", buildApiErrorMsg(e));
            }

            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 从容器的 terminated 状态中获取退出码。
     */
    private int getContainerExitCode(V1Pod pod) {
        try {
            List<V1ContainerStatus> containerStatuses =
                    pod.getStatus().getContainerStatuses();
            if (containerStatuses != null) {
                for (V1ContainerStatus status : containerStatuses) {
                    V1ContainerStateTerminated terminated = status.getState().getTerminated();
                    if (terminated != null && terminated.getExitCode() != null) {
                        return terminated.getExitCode();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("无法获取容器退出码: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 静默删除 Job（同时删除关联 Pod）。
     * propagationPolicy=Background 确保级联删除。
     */
    private void deleteJobSilently(String jobName) {
        try {
            batchV1Api.deleteNamespacedJob(
                    jobName, NAMESPACE, null, null, null, null,
                    "Background", null);
            log.debug("判题 Job {} 已删除", jobName);
        } catch (ApiException e) {
            if (e.getCode() != 404) {  // 404 = 不存在，忽略
                log.warn("删除 Job {} 失败: {}", jobName, buildApiErrorMsg(e));
            }
        }
    }

    /**
     * 提取 ApiException 的可读错误信息。
     * io.kubernetes.client 的 ApiException.getMessage() 经常为 null/空字符串，
     * 真实原因在 responseBody（K8s API Server 返回的 Status JSON）里，
     * 例如权限不足时返回：
     *   {"kind":"Status","reason":"Forbidden","message":"jobs.batch ... is forbidden: ..."}
     * 只打印 getMessage() 会导致日志只有"判题容器创建失败:"而看不到原因。
     */
    private String buildApiErrorMsg(ApiException e) {
        if (e == null) {
            return "未知错误";
        }
        String body = e.getResponseBody();
        if (body != null && !body.trim().isEmpty()) {
            return "HTTP " + e.getCode() + ": " + body.trim();
        }
        String msg = e.getMessage();
        return "HTTP " + e.getCode() + (msg != null && !msg.trim().isEmpty() ? ": " + msg.trim() : "");
    }

}
