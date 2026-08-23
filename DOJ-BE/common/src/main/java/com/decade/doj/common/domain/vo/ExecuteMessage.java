package com.decade.doj.common.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Data
@Accessors(chain = true)
public class ExecuteMessage {

    private Integer exitValue;
    private String status;
    private String message;
    private double time;
    private Long memory;

    private static final Map<Integer, String> exitStatusMap = new HashMap<>();
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

        InfoStatus.add(0);
        InfoStatus.add(1);
        InfoStatus.add(2);
        InfoStatus.add(10);
        InfoStatus.add(11);
        InfoStatus.add(139);
        InfoStatus.add(134);
        InfoStatus.add(136);
    }

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

    public static boolean show(Integer exitValue) {
        if (exitValue == null) return false;
        if (exitValue > 128 && exitValue <= 255) return true; // 信号崩溃，展示输出便于调试
        return InfoStatus.contains(exitValue);
    }
}