package com.decade.doj.common.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启发式复杂度分析器。
 * 通过代码特征（循环嵌套深度、算法模式、空间分配）估算时间/空间复杂度。
 * 精确度有限，可覆盖常见 OJ 代码模式。
 */
public class ComplexityAnalyzer {

    private ComplexityAnalyzer() {}

    public static ComplexityResult analyze(String code, String language) {
        // 清洗注释和字符串，避免干扰
        String clean = stripCommentsAndStrings(code, language);

        int maxLoopDepth = maxLoopDepth(clean);
        boolean hasBinarySearch = hasBinarySearch(clean);
        boolean hasSort = hasSort(clean);
        boolean hasRecursiveDivide = hasRecursiveDivide(clean);
        boolean hasHashMap = clean.contains("HashMap") || clean.contains("unordered_map")
                || clean.contains("dict") || clean.contains("Map<");
        boolean hasPQ = clean.contains("PriorityQueue") || clean.contains("priority_queue")
                || clean.contains("heapq") || clean.contains("Heap");
        int arrayAllocs = countArrayAllocations(clean);

        String timeComplexity = inferTime(maxLoopDepth, hasBinarySearch, hasSort,
                hasRecursiveDivide, hasPQ);
        String spaceComplexity = inferSpace(maxLoopDepth, hasHashMap, hasPQ, arrayAllocs);

        return new ComplexityResult(timeComplexity, spaceComplexity,
                "基于代码特征推断，仅供参考");
    }

    private static String inferTime(int depth, boolean binarySearch, boolean sort,
                                     boolean divide, boolean pq) {
        if (divide && depth >= 1) return "O(n log n)";
        if (sort && depth <= 1) return "O(n log n)";
        if (sort && depth >= 2) return "O(n² log n) 或更大";
        if (binarySearch && depth <= 1) return "O(log n)";
        if (pq && depth <= 1) return "O(n log n)";
        return switch (depth) {
            case 0 -> "O(1)";
            case 1 -> "O(n)";
            case 2 -> "O(n²)";
            case 3 -> "O(n³)";
            default -> "O(n^" + depth + ")";
        };
    }

    private static String inferSpace(int depth, boolean hashMap, boolean pq, int arrays) {
        StringBuilder sb = new StringBuilder();
        if (depth == 0 && !hashMap && arrays == 0) {
            sb.append("O(1)");
        } else {
            sb.append("O(");
            if (hashMap || pq) sb.append("n");
            else if (arrays == 1) sb.append("n");
            else if (arrays >= 2) sb.append("n^").append(arrays);
            else sb.append("1");
            sb.append(")");
        }
        // 递归栈空间
        if (depth > 1) sb.append("（含递归栈 O(n)）");
        return sb.toString();
    }

    /**
     * 最大循环嵌套深度：for/while 的花括号嵌套层数。
     */
    static int maxLoopDepth(String code) {
        int maxDepth = 0;
        int currentDepth = 0;
        // 简化：统计连续的 for/while 开始标记
        String[] lines = code.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.matches("^(for\\s*\\(|while\\s*\\(|do\\s*\\{).*")) {
                currentDepth++;
                maxDepth = Math.max(maxDepth, currentDepth);
            }
            if (trimmed.equals("}") || trimmed.startsWith("}")) {
                currentDepth = Math.max(0, currentDepth - 1);
            }
        }
        return maxDepth;
    }

    static boolean hasBinarySearch(String code) {
        return code.contains("binary_search") || code.contains("BinarySearch")
                || code.contains("bisect") || code.contains("lower_bound")
                || code.contains("upper_bound")
                || (code.contains("Arrays.binarySearch") || code.contains("Collections.binarySearch"))
                || hasMidSplitPattern(code);
    }

    /**
     * 检测 while (left <= right) { mid = ... 二分模式
     */
    private static boolean hasMidSplitPattern(String code) {
        return Pattern.compile("while\\s*\\(\\s*\\w+\\s*<=?\\s*\\w+")
                .matcher(code).find()
                && Pattern.compile("mid\\s*=").matcher(code).find();
    }

    static boolean hasSort(String code) {
        return code.contains(".sort(") || code.contains("sorted(")
                || code.contains("Sort(") || code.contains("qsort");
    }

    static boolean hasRecursiveDivide(String code) {
        // 函数体内调用自身 + 输入规模折半
        Matcher funcDef = Pattern.compile("(?:void|int|long|float|double|bool|auto|def|public|private|static)\\s+(\\w+)\\s*\\(")
                .matcher(code);
        while (funcDef.find()) {
            String name = funcDef.group(1);
            // 检查该函数内是否调用了自身
            if (Pattern.compile("\\b" + name + "\\s*\\(").matcher(code).find()) {
                // 检查是否有折半操作
                if (code.contains("/ 2") || code.contains("/2")
                        || code.contains(">> 1") || code.contains(">>1")) {
                    return true;
                }
            }
        }
        return false;
    }

    static int countArrayAllocations(String code) {
        int count = 0;
        // new int[n], vector<int>(n), [0]*n, 等
        count += countMatches(code, "new\\s+\\w+\\[");
        count += countMatches(code, "vector<[^>]+>\\s*\\(\\s*\\w+");
        count += countMatches(code, "malloc\\(");
        count += countMatches(code, "\\[0\\]\\s*\\*\\s*\\w+"); // Python [0]*n
        return Math.min(count, 2);
    }

    private static int countMatches(String text, String regex) {
        int c = 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) c++;
        return c;
    }

    static String stripCommentsAndStrings(String code, String lang) {
        // 去字符串字面量
        code = code.replaceAll("\"[^\"]*\"", "\"\"");
        code = code.replaceAll("'[^']*'", "''");
        // 去注释
        if ("python".equals(lang)) {
            code = code.replaceAll("#.*", "");
        } else {
            code = code.replaceAll("//.*", "");
            code = code.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        }
        return code;
    }

    public static class ComplexityResult {
        private final String timeComplexity;
        private final String spaceComplexity;
        private final String note;

        public ComplexityResult(String time, String space, String note) {
            this.timeComplexity = time;
            this.spaceComplexity = space;
            this.note = note;
        }

        public String getTimeComplexity() { return timeComplexity; }
        public String getSpaceComplexity() { return spaceComplexity; }
        public String getNote() { return note; }

        @Override
        public String toString() {
            return "时间复杂度: " + timeComplexity + ", 空间复杂度: " + spaceComplexity;
        }
    }
}
