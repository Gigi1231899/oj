package com.decade.doj.sandbox.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================
 * 判题执行结果 — 单次沙箱运行的完整反馈
 * ============================================================
 *
 * 字段说明：
 *   exitValue: 退出码，映射到判题状态
 *     0=Finished, 1=Runtime Error, 2=Compile Error,
 *     10=Accepted, 11=Wrong Answer,
 *     124=Time Limit Exceeded (timeout 命令杀死),
 *     137=Memory Limit Exceeded (OOM Killer)
 *   status:   判题状态的可读字符串（Accepted/Wrong Answer/TLE/MLE/CE/RE）
 *   message:  详细信息（错误信息/程序输出/对比结果）
 *   time:     实际运行耗时（秒），来自 /usr/bin/time -v
 *   memory:   实际内存使用（KB），来自 /usr/bin/time -v 的 Maximum resident set size
 * ============================================================
 */
@Data
@Accessors(chain = true)
public class ExecuteMessage {

    private Integer exitValue;   // 退出码
    private String status;       // 状态描述
    private String message;      // 详细信息
    private Double time;         // 实际耗时（秒），null=不适用（CE/WA/RE）
    private Long memory;         // 实际内存（KB）

    // 退出码 → 状态字符串映射
    private static final Map<Integer, String> exitStatusMap = new HashMap<>();
    // 哪些退出码需要在前端展示详细输出信息
    private static final Set<Integer> InfoStatus = new HashSet<>();

    static {
        exitStatusMap.put(0, "Finished");
        exitStatusMap.put(1, "Runtime Error");
        exitStatusMap.put(2, "Compile Error");
        exitStatusMap.put(124, "Time Limit Exceeded");
        exitStatusMap.put(137, "Memory Limit Exceeded");
        exitStatusMap.put(10, "Accepted");
        exitStatusMap.put(11, "Wrong Answer");
        // Docker / shell 系统级错误
        exitStatusMap.put(125, "System Error");
        exitStatusMap.put(126, "System Error");
        exitStatusMap.put(127, "System Error");
        exitStatusMap.put(139, "Runtime Error");   // SIGSEGV
        exitStatusMap.put(134, "Runtime Error");   // SIGABRT
        exitStatusMap.put(136, "Runtime Error");   // SIGFPE

        // Finished/RE/CE/AC/WA 时展示程序输出；TLE/MLE 不展示（无意义）
        InfoStatus.add(0);
        InfoStatus.add(1);
        InfoStatus.add(2);
        InfoStatus.add(10);
        InfoStatus.add(11);
        InfoStatus.add(139);
        InfoStatus.add(134);
        InfoStatus.add(136);
    }

    /** 退出码 → 状态描述 */
    public static String getStatus(Integer exitValue) {
        if (exitValue == null) {
            return "Unknown Error";
        }
        String known = exitStatusMap.get(exitValue);
        if (known != null) {
            return known;
        }
        // 128+ = 被信号杀死
        if (exitValue > 128 && exitValue <= 255) {
            return "Runtime Error";
        }
        // 未预期的退出码，带上数值便于排查
        return "Runtime Error (code: " + exitValue + ")";
    }

    /** 是否需要在结果中展示程序输出信息 */
    public static boolean show(Integer exitValue) {
        if (exitValue == null) return false;
        if (exitValue > 128 && exitValue <= 255) return true; // 信号崩溃，展示输出便于调试
        return InfoStatus.contains(exitValue);
    }
}