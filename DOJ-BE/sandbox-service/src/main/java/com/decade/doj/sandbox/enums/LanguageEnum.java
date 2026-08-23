package com.decade.doj.sandbox.enums;

import lombok.Getter;

import java.util.*;

/**
 * 语言枚举 — 只包含语言相关的静态元数据（不再有可变状态）。
 * 时间/内存限制在判题时由调用方通过参数传入，保证线程安全。
 */
@Getter
public enum LanguageEnum {

    PYTHON("python3 %s.py", "python", 128, 5, "doj-python", "python3 %s.py"),
    JAVA(  "sh -c 'javac %s.java && java -Xmx1024m %s'", "java", 128, 2, "doj-java", "java -Xmx1024m %s"),
    CPP(   "g++ -std=c++17 %s.cpp -o %s.out && ./%s.out", "cpp", 128, 6, "myoj_time:1.0", "./%s.out");

    /** 完整运行命令模板（编译 + 执行，用于在线运行。%s 会被替换为文件名） */
    private final String runCmd;
    /** 语言标识小写 */
    private final String language;
    /** 默认内存限制（MB） */
    private final int defaultMemoryLimitMb;
    /** 默认时间限制（秒） */
    private final int defaultTimeLimitSeconds;
    /** Docker 镜像名称 */
    private final String imageName;
    /** 仅执行命令模板（判题用，编译已在 compileIfNeeded 阶段完成） */
    private final String runOnlyCmd;

    /**
     * 判题运行时镜像仓库前缀（如 dojacr.azurecr.io/doj/）。
     * 通过环境变量 DOJ_SANDBOX_IMAGE_PREFIX 注入，K8s 部署时指向 ACR，
     * 本地 Docker 模式不设置则为空（直接用短镜像名）。
     */
    private static final String IMAGE_PREFIX =
            System.getenv().getOrDefault("DOJ_SANDBOX_IMAGE_PREFIX", "");

    private static final Map<String, LanguageEnum> NAME_MAP;

    static {
        Map<String, LanguageEnum> map = new HashMap<>();
        for (LanguageEnum le : LanguageEnum.values()) {
            map.put(le.language.toLowerCase(Locale.ROOT), le);
        }
        NAME_MAP = Collections.unmodifiableMap(map);
    }

    LanguageEnum(String runCmd,
            String language,
            int defaultMemoryLimitMb,
            int defaultTimeLimitSeconds,
            String imageName,
            String runOnlyCmd) {
        this.runCmd = runCmd;
        this.language = language;
        this.defaultMemoryLimitMb = defaultMemoryLimitMb;
        this.defaultTimeLimitSeconds = defaultTimeLimitSeconds;
        this.imageName = imageName;
        this.runOnlyCmd = runOnlyCmd;
    }

    /**
     * 返回带仓库前缀的完整镜像名（覆盖 Lombok @Getter 生成的 getter）。
     * 本地模式（无前缀）返回 "doj-python" 等短名；
     * K8s 模式返回 "dojacr.azurecr.io/doj/doj-python" 等完整路径。
     */
    public String getImageName() {
        return IMAGE_PREFIX + imageName;
    }

    /**
     * 检查给定语言字符串是否不在支持列表中。
     *
     * @param lang 待校验的语言，比如 "java"、"python"、"cpp"
     * @return 如果 lang 不在 NAME_MAP 中，返回 true；否则返回 false
     */
    public static boolean isInValidLanguage(String lang) {
        if (lang == null) {
            return true;
        }
        return !NAME_MAP.containsKey(lang.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 根据语言字符串获取对应的枚举实例，大小写不敏感。
     *
     * @param lang 语言名称，比如 "java"、"python"、"cpp"
     * @return 对应的 LanguageEnum 实例；若找不到则返回 null
     */
    public static LanguageEnum getLanguageEnum(String lang) {
        if (lang == null) {
            return null;
        }
        return NAME_MAP.get(lang.trim().toLowerCase(Locale.ROOT));
    }
}
