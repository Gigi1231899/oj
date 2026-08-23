package com.decade.doj.sandbox.service;

import com.decade.doj.common.client.ProblemClient;
import com.decade.doj.common.config.properties.ResourceProperties;
import com.decade.doj.common.domain.po.Problem;
import com.decade.doj.common.utils.ComplexityAnalyzer;
import com.decade.doj.sandbox.domain.vo.ExecuteMessage;
import com.decade.doj.sandbox.domain.vo.JudgingTask;
import com.decade.doj.sandbox.enums.LanguageEnum;
import com.decade.doj.sandbox.runner.SandboxResult;
import com.decade.doj.sandbox.runner.SandboxRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SandboxServiceImpl implements ISandboxService {

    private final SandboxRunner sandboxRunner;
    private final RabbitTemplate rabbitTemplate;
    private final ProblemClient problemClient;
    private final ResourceProperties resourceProperties;

    public SandboxServiceImpl(
            SandboxRunner sandboxRunner,
            RabbitTemplate rabbitTemplate,
            ProblemClient problemClient,
            ResourceProperties resourceProperties) {
        this.sandboxRunner = sandboxRunner;
        this.rabbitTemplate = rabbitTemplate;
        this.problemClient = problemClient;
        this.resourceProperties = resourceProperties;
    }

    private static final String MOUNT_PATH = "/app";
    private static final int COMPILE_MEMORY_MB = 256;
    private static final int COMPILE_TIMEOUT_SEC = 30;
    private static final String CASE_END_PREFIX = "__CASE_END_";

    /** 从题目获取时间限制（秒），未设置时用语言默认值 */
    private double getTimeLimitSeconds(LanguageEnum langEnum, Long problemId) {
        if (problemId != null) {
            try {
                Problem p = problemClient.getProblemById(problemId).getData();
                if (p != null && p.getTimeLimit() != null && p.getTimeLimit() > 0) {
                    return p.getTimeLimit() / 1000.0;
                }
            } catch (Exception e) {
                log.warn("获取题目时间限制失败: {}", e.getMessage());
            }
        }
        return langEnum.getDefaultTimeLimitSeconds();
    }

    /** 从题目获取内存限制（MB），未设置时用语言默认值 */
    private int getMemoryLimitMb(LanguageEnum langEnum, Long problemId) {
        if (problemId != null) {
            try {
                Problem p = problemClient.getProblemById(problemId).getData();
                if (p != null && p.getMemoryLimit() != null && p.getMemoryLimit() > 0) {
                    return p.getMemoryLimit();
                }
            } catch (Exception e) {
                log.warn("获取题目内存限制失败: {}", e.getMessage());
            }
        }
        return langEnum.getDefaultMemoryLimitMb();
    }

    // 说明：run 功能（runCodeInSandbox）已整体下线，仅保留提交判题与测试用例生成

    // ============================================================
    // 测试用例生成：checkerConfig 变量规则 + 标程 → N 组 (input, output)
    // ============================================================
    @Override
    public List<Map<String, String>> generateTestCases(String checkerConfig, String standardCode,
                                                        String standardLang, int rounds) throws IOException {
        String folderName = UUID.randomUUID().toString();
        Path workDir = Path.of(resourceProperties.getCodePath(), "gen-" + folderName);
        Files.createDirectories(workDir);

        LanguageEnum stdLang = LanguageEnum.getLanguageEnum(standardLang);
        if (stdLang == null) throw new IOException("不支持的标程语言: " + standardLang);
        String ext = standardLang.equals("python") ? ".py" : standardLang.equals("java") ? ".java" : ".cpp";

        // 写入标程
        Files.writeString(workDir.resolve("Standard" + ext), standardCode);

        // 编译标程（Java/C++）
        if (stdLang != LanguageEnum.PYTHON) {
            ExecuteMessage compileErr = compileIfNeeded(stdLang, workDir.toString(), "Standard");
            if (compileErr != null) throw new IOException("标程编译失败: " + compileErr.getMessage());
        }

        // 生成 Python 脚本：解析 checkerConfig → 随机生成输入 → 跑标程 → 输出(test_data, test_ans)
        String script = buildGeneratorPython(checkerConfig, standardLang, rounds);
        Files.writeString(workDir.resolve("generate.py"), script);

        SandboxResult result = sandboxRunner.runAndWait(
                LanguageEnum.PYTHON.getImageName(), workDir.toString(), MOUNT_PATH,
                "python3 /app/generate.py", 256, (long) rounds * 30 + 60, "gen-" + folderName);

        if (result.getExitCode() != 0) {
            throw new IOException("生成脚本执行失败: " + result.getOutput());
        }

        // 解析输出: ---CASE_N_INPUT---\n...\n---CASE_N_OUTPUT---\n...
        List<Map<String, String>> cases = new ArrayList<>();
        String[] blocks = result.getOutput().split("---CASE_END---");
        for (String block : blocks) {
            String[] parts = block.split("---CASE_OUTPUT---");
            if (parts.length == 2) {
                cases.add(Map.of(
                    "input", parts[0].replace("---CASE_INPUT---", "").trim(),
                    "output", parts[1].trim()
                ));
            }
        }

        // 清理临时文件
        try { Files.walk(workDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}

        return cases;
    }

    private String buildGeneratorPython(String config, String stdLang, int rounds) {
        String safeConfig = config != null ? config.replace("%", "%%") : "";
        String stdRunCmd = buildRunOnlyCmd(LanguageEnum.getLanguageEnum(stdLang), "Standard");
        String safeCmd = stdRunCmd.replace("'", "'\\''");
        return """
import subprocess, random, sys, os, string

os.chdir('/app')

config_str = '''%s'''

def parse_config(s):
    items = []
    rounds = 20
    for idx, raw in enumerate(s.strip().split('\\n'), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('rounds'):
            pr = line.split()
            if len(pr) < 2:
                raise ValueError(f"配置第 {idx} 行缺少 rounds 次数: '{line}'，期望格式: rounds 次数")
            rounds = int(pr[1])
            continue
        parts = line.split()
        name = parts[0]
        def need(n, fmt):
            if len(parts) < n:
                raise ValueError(f"配置第 {idx} 行字段不足: '{line}'，期望格式: {fmt}")
        # 检测末尾排序标记 asc/desc
        sort_order = None
        if len(parts) > 1 and parts[-1] in ('asc', 'desc'):
            sort_order = parts[-1]
            parts = parts[:-1]
        if len(parts) < 2:
            raise ValueError(f"配置第 {idx} 行无法识别类型: '{line}'，期望格式: 名称 类型 [范围]")
        tk = parts[1]  # type keyword
        if tk == 'string':
            need(4, '名称 string 最小长度 最大长度 [charset]')
            charset = parts[4] if len(parts) > 4 else 'mixed'
            items.append({'name':name,'type':'string','min':int(parts[2]),'max':int(parts[3]),'charset':charset})
        elif tk == 'char':
            charset = parts[2] if len(parts) > 2 else 'mixed'
            items.append({'name':name,'type':'char','charset':charset})
        elif tk == 'bool':
            items.append({'name':name,'type':'bool'})
        elif tk == 'double':
            need(4, '名称 double 最小 最大')
            items.append({'name':name,'type':'double','min':parts[2],'max':parts[3]})
        elif tk == 'long':
            need(4, '名称 long 最小 最大')
            items.append({'name':name,'type':'long','min':parts[2],'max':parts[3]})
        elif tk == 'int':
            need(4, '名称 int 最小 最大')
            items.append({'name':name,'type':'int','min':parts[2],'max':parts[3]})
        elif tk.startswith('int['):
            need(4, '名称 int[n] 最小 最大 [asc|desc]')
            size_var = tk[4:-1]
            it = {'name':name,'type':'int[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('string['):
            need(4, '名称 string[n] 最小长度 最大长度 [charset] [asc|desc]')
            size_var = tk[7:-1]
            charset = parts[4] if len(parts) > 4 else 'mixed'
            it = {'name':name,'type':'string[]','size':size_var,'min':int(parts[2]),'max':int(parts[3]),'charset':charset}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('char['):
            need(2, '名称 char[n] [charset] [asc|desc]')
            size_var = tk[5:-1]
            charset = parts[2] if len(parts) > 2 else 'mixed'
            it = {'name':name,'type':'char[]','size':size_var,'charset':charset}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('bool['):
            need(2, '名称 bool[n] [asc|desc]')
            size_var = tk[5:-1]
            it = {'name':name,'type':'bool[]','size':size_var}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('double['):
            need(4, '名称 double[n] 最小 最大 [asc|desc]')
            size_var = tk[7:-1]
            it = {'name':name,'type':'double[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        elif tk.startswith('long['):
            need(4, '名称 long[n] 最小 最大 [asc|desc]')
            size_var = tk[5:-1]
            it = {'name':name,'type':'long[]','size':size_var,'min':int(parts[2]),'max':int(parts[3])}
            if sort_order: it['sort'] = sort_order
            items.append(it)
        else:
            need(3, '名称 最小 最大（旧格式，隐式 int）')
            # backward compat: name min max → implicit int
            items.append({'name':name,'type':'int','min':parts[1],'max':parts[2]})
    return items, rounds

config, rounds = parse_config(config_str)

def pick_chars(charset):
    if charset == 'lower': return string.ascii_lowercase
    elif charset == 'upper': return string.ascii_uppercase
    elif charset == 'digit': return string.digits
    else: return string.ascii_letters + string.digits

def rand_string(lo, hi, charset):
    length = random.randint(lo, hi)
    chars = pick_chars(charset)
    return ''.join(random.choice(chars) for _ in range(length))

def resolve(val, vars):
    try:
        return int(val)
    except (ValueError, TypeError):
        return vars.get(val, 0)

def resolve_float(val, vars):
    try:
        return float(val)
    except (ValueError, TypeError):
        return float(vars.get(val, 0))

def maybe_sort(elems, item, key=None):
    so = item.get('sort')
    if so == 'asc':
        elems.sort(key=key)
    elif so == 'desc':
        elems.sort(key=key, reverse=True)

def gen_input():
    lines = []
    vars = {}
    # 收集被数组引用的长度变量，这些变量不输出（只用于控制数组长度）
    size_vars = set()
    for item in config:
        if item.get('size'):
            size_vars.add(item['size'])
    for item in config:
        t = item['type']
        if t == 'int' or t == 'long':
            lo = resolve(item['min'], vars)
            hi = resolve(item['max'], vars)
            if lo > hi: lo, hi = hi, lo
            val = random.randint(lo, hi)
            vars[item['name']] = val
            if item['name'] not in size_vars:
                lines.append(str(val))
        elif t == 'double':
            lo = resolve_float(item['min'], vars)
            hi = resolve_float(item['max'], vars)
            if lo > hi: lo, hi = hi, lo
            val = round(random.uniform(lo, hi), 6)
            vars[item['name']] = val
            lines.append(str(val))
        elif t == 'int[]' or t == 'long[]':
            size = vars.get(item['size'], 10)
            elems = [str(random.randint(item['min'], item['max'])) for _ in range(size)]
            maybe_sort(elems, item, key=int)
            lines.append(' '.join(elems))
            vars[item['name']] = elems
        elif t == 'double[]':
            size = vars.get(item['size'], 10)
            elems = [str(round(random.uniform(item['min'], item['max']), 6)) for _ in range(size)]
            maybe_sort(elems, item, key=float)
            lines.append(' '.join(elems))
            vars[item['name']] = elems
        elif t == 'string':
            s = rand_string(item['min'], item['max'], item.get('charset','mixed'))
            lines.append(s)
            vars[item['name']] = s
        elif t == 'string[]':
            size = vars.get(item['size'], 10)
            elems = [rand_string(item['min'], item['max'], item.get('charset','mixed')) for _ in range(size)]
            maybe_sort(elems, item)
            lines.append(' '.join(elems))
            vars[item['name']] = elems
        elif t == 'char':
            chars = pick_chars(item.get('charset','mixed'))
            c = random.choice(chars)
            lines.append(c)
            vars[item['name']] = c
        elif t == 'char[]':
            size = vars.get(item['size'], 10)
            chars = pick_chars(item.get('charset','mixed'))
            elems = [random.choice(chars) for _ in range(size)]
            maybe_sort(elems, item)
            lines.append(' '.join(elems))
            vars[item['name']] = elems
        elif t == 'bool':
            val = random.choice(['true', 'false'])
            lines.append(val)
            vars[item['name']] = val
        elif t == 'bool[]':
            size = vars.get(item['size'], 10)
            elems = [random.choice(['true', 'false']) for _ in range(size)]
            maybe_sort(elems, item)
            lines.append(' '.join(elems))
            vars[item['name']] = elems
    return '\\n'.join(lines) + '\\n'

for r in range(rounds):
    inp = gen_input()
    res = subprocess.run(['sh','-c','%s'], input=inp, capture_output=True, text=True, timeout=30)
    print('---CASE_INPUT---')
    print(inp)
    print('---CASE_OUTPUT---')
    print(res.stdout.strip())
    print('---CASE_END---')
""".formatted(safeConfig, safeCmd);
    }

    // ============================================================
    // 判题入口：由 JudgingWorker 从 Redis 消费后调用
    // ============================================================
    @Override
    public void execute(JudgingTask task) throws IOException {
        LanguageEnum langEnum = LanguageEnum.getLanguageEnum(task.getLang());
        if (langEnum == null) {
            sendErrorResult(task.getSubmissionId(), "不支持的语言: " + task.getLang());
            return;
        }

        Path codeFile = Path.of(task.getLocalPath());
        String hostCodeDir = codeFile.getParent().toString();
        String baseName = stripExtension(task.getFilename());

//        获取题目时间+内存限制
        double timeLimitSeconds = getTimeLimitSeconds(langEnum, task.getProblemId());
        int memoryLimitMb = getMemoryLimitMb(langEnum, task.getProblemId());

        executeTraditionalMode(task, langEnum, hostCodeDir, baseName, timeLimitSeconds, memoryLimitMb);
    }

    // ============================================================
    // 传统模式：所有测试用例打包进一个 Job，串行执行
    // ============================================================
    private void executeTraditionalMode(JudgingTask task, LanguageEnum langEnum,
                                         String hostCodeDir, String baseName,
                                         double timeLimitSeconds, int memoryLimitMb) {
        Long submissionId = task.getSubmissionId();
        List<String> inputFileNames = task.getInputFileNames();
        List<String> outputAnswers = task.getOutputAnswers();

        if (inputFileNames == null || outputAnswers == null || inputFileNames.isEmpty()) {
            sendErrorResult(submissionId, "测试用例数据为空");
            return;
        }

        // Step 1: 编译
        ExecuteMessage compileErr = compileIfNeeded(langEnum, hostCodeDir, baseName);
        if (compileErr != null) {
            sendResult(submissionId, compileErr);
            return;
        }

        // Step 2: 生成批量运行脚本并写入文件
        int totalCases = inputFileNames.size();
        int perCaseTimeout = (int) Math.ceil(timeLimitSeconds) + 2;
        String runCmd = buildRunOnlyCmd(langEnum, baseName);
        String batchScript = buildBatchScript(runCmd, inputFileNames, perCaseTimeout, MOUNT_PATH);
        try {
            Files.writeString(Path.of(hostCodeDir, "run_cases.sh"), batchScript);
        } catch (IOException e) {
            sendErrorResult(submissionId, "生成判题脚本失败: " + e.getMessage());
            return;
        }

        // 整体超时 = 每用例时限 × 数量 + 缓冲
        long batchDeadline = (long) ((timeLimitSeconds + 5) * totalCases + 30);
        String jobId = submissionId + "-batch";
        SandboxResult batchResult = sandboxRunner.runAndWait(
                langEnum.getImageName(), hostCodeDir, MOUNT_PATH,
                "sh /app/run_cases.sh",
                Math.max(memoryLimitMb, 256),
                batchDeadline,
                jobId);

        // Step 3: 从批量输出中解析每用例结果
        String output = batchResult.getOutput();
        int acCount = 0;
        long maxTimeMs = 0;
        long maxMemoryKb = 0;
        ExecuteMessage firstNonAc = null;

        int lastEnd = 0;
        for (int i = 0; i < totalCases; i++) {
            String marker = CASE_END_PREFIX + i + "_";
            int markerPos = output.indexOf(marker, lastEnd);
            if (markerPos < 0) {
                if (firstNonAc == null) {
                    String inputContent = readInputContent(hostCodeDir, inputFileNames.get(i));
                    firstNonAc = new ExecuteMessage()
                            .setExitValue(124)
                            .setStatus("Time Limit Exceeded")
                            .setMessage("超出时间限制");
                }
                break;
            }

            // 解析标记 __CASE_END_N_exitCode__
            int codeStart = markerPos + marker.length();
            int codeEnd = output.indexOf("__", codeStart);
            int exitCode;
            try {
                exitCode = codeEnd > codeStart
                        ? Integer.parseInt(output.substring(codeStart, codeEnd))
                        : 1;
            } catch (NumberFormatException e) {
                exitCode = 1;
            }

            String caseOutput = output.substring(lastEnd, markerPos);
            lastEnd = output.indexOf("__", markerPos + marker.length());
            if (lastEnd < 0) lastEnd = output.length();
            else lastEnd += 2;

            ExecuteMessage caseResult = parseResult(
                    new SandboxResult(exitCode, caseOutput, false));

            double caseTimeSec = caseResult.getTime() != null ? caseResult.getTime() : 0;
            long caseMemoryKb = caseResult.getMemory() != null ? caseResult.getMemory() : 0;
            String programOutput = caseResult.getMessage() != null ? caseResult.getMessage() : "";

            // shell 内置 time 提供毫秒级精度: "real\t0m0.003s"
            Matcher stm = Pattern.compile("real\\s+(\\d+)m(\\d+(?:\\.\\d+)?)s")
                    .matcher(caseOutput);
            if (stm.find()) {
                double preciseTime = Integer.parseInt(stm.group(1)) * 60
                        + Double.parseDouble(stm.group(2));
                if (preciseTime > 0) caseTimeSec = preciseTime;
            }

            maxTimeMs = Math.max(maxTimeMs, (long) (caseTimeSec * 1000));
            maxMemoryKb = Math.max(maxMemoryKb, caseMemoryKb);

            String expectedOutput = outputAnswers.get(i);
            String inputContent = readInputContent(hostCodeDir, inputFileNames.get(i));

            if (exitCode == 124) {
                if (firstNonAc == null) {
                    firstNonAc = new ExecuteMessage()
                            .setExitValue(124)
                            .setStatus("Time Limit Exceeded")
                            .setMessage("超出时间限制");
                }
            } else if (exitCode == 137 || caseMemoryKb > memoryLimitMb * 1024L) {
                if (firstNonAc == null) {
                    firstNonAc = new ExecuteMessage()
                            .setExitValue(137)
                            .setStatus("Memory Limit Exceeded")
                            .setMessage("超出内存限制");
                }
            } else if (exitCode != 0) {
                if (firstNonAc == null) {
                    firstNonAc = new ExecuteMessage()
                            .setExitValue(exitCode)
                            .setStatus(ExecuteMessage.getStatus(exitCode))
                            .setMessage(programOutput);
                }
            } else if (caseTimeSec > timeLimitSeconds) {
                if (firstNonAc == null) {
                    firstNonAc = new ExecuteMessage()
                            .setExitValue(124)
                            .setStatus("Time Limit Exceeded")
                            .setMessage("超出时间限制");
                }
            } else {
                String actualOutput = programOutput;
                if (actualOutput != null
                        && normalizeOutput(actualOutput).equals(normalizeOutput(expectedOutput))) {
                    acCount++;
                } else {
                    if (firstNonAc == null) {
                        firstNonAc = new ExecuteMessage()
                                .setExitValue(11)
                                .setStatus("Wrong Answer")
                                .setMessage(buildWaMessage(inputContent, expectedOutput,
                                        actualOutput, i + 1, totalCases));
                    }
                }
            }
        }

        // Step 4: 汇总结果
        ExecuteMessage finalResult;
        if (firstNonAc != null) {
            finalResult = firstNonAc;
        } else {
            finalResult = new ExecuteMessage()
                    .setExitValue(10)
                    .setStatus("Accepted")
                    .setMessage(String.format("通过 %d/%d 个测试用例", acCount, totalCases));
        }
        // 只有 AC 和 TLE 展示耗时/内存，WA/RE/MLE/CE 不展示
        Integer finalCode = finalResult.getExitValue();
        if (finalCode != null && (finalCode == 10 || finalCode == 124 || finalCode == 137)) {
            // AC / TLE / MLE 展示耗时/内存
            finalResult.setTime(maxTimeMs / 1000.0);
            finalResult.setMemory(maxMemoryKb);
        }

        String complexity = null;
        try {
            String code = Files.readString(Path.of(task.getLocalPath()));
            ComplexityAnalyzer.ComplexityResult cr = ComplexityAnalyzer.analyze(code, task.getLang());
            complexity = cr.toString();
        } catch (Exception e) {
            log.debug("复杂度分析跳过: {}", e.getMessage());
        }

        sendResult(submissionId, finalResult, complexity);

        try {
            Files.deleteIfExists(Path.of(task.getLocalPath()));
            Path parentDir = Path.of(task.getLocalPath()).getParent();
            if (parentDir != null) {
                try (var files = Files.list(parentDir)) {
                    if (files.findAny().isEmpty()) Files.deleteIfExists(parentDir);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * 生成批量运行脚本，每个测试用例独立 timeout + /usr/bin/time -v 包裹，
     * 用例之间用 __CASE_END_{idx}_{exitCode}__ 分隔。
     */
    private String buildBatchScript(String runCmd, List<String> inputFiles,
                                    int perCaseTimeout, String mountPath) {
        // 将相对路径转为绝对路径，兼容 Windows Docker 下 -w 不生效的问题
        String absRunCmd = runCmd.replace("./", mountPath + "/");
        StringBuilder sb = new StringBuilder("#!/bin/sh\n");
        for (int i = 0; i < inputFiles.size(); i++) {
            sb.append(String.format(
                    "{ time timeout -k 2 %d /usr/bin/time -v sh -c '%s < %s/%s'; } 2>&1\n",
                    perCaseTimeout, absRunCmd.replace("'", "'\\''"),
                    mountPath, inputFiles.get(i)));
            sb.append("echo ").append(CASE_END_PREFIX).append(i).append("_$?__\n");
        }
        return sb.toString();
    }

    private String readInputContent(String hostCodeDir, String inputFile) {
        try {
            return Files.readString(Path.of(hostCodeDir, inputFile));
        } catch (IOException ignored) {
            return "(空)";
        }
    }


    // ============================================================
    // 编译工具方法
    // ============================================================

    private String buildCompileCmd(LanguageEnum langEnum, String baseName) {
        if (langEnum == LanguageEnum.JAVA) {
            return String.format("javac %s/%s.java", MOUNT_PATH, baseName);
        } else {
            return String.format("g++ -std=c++17 %s/%s.cpp -o %s/%s.out",
                    MOUNT_PATH, baseName, MOUNT_PATH, baseName);
        }
    }

    /**
     * 编译代码（Python 直接返回 null 表示无需编译）。
     * 返回非 null 表示编译失败，应中止后续流程。
     */
    private ExecuteMessage compileIfNeeded(LanguageEnum langEnum, String hostCodeDir,
                                            String baseName) {
        if (langEnum == LanguageEnum.PYTHON) {
            return null;
        }
        String jobId = "compile-" + UUID.randomUUID().toString().substring(0, 8);
//        沙箱运行结果
        SandboxResult result = sandboxRunner.runAndWait(
                langEnum.getImageName(), hostCodeDir, MOUNT_PATH,
                buildCompileCmd(langEnum, baseName),
                COMPILE_MEMORY_MB, COMPILE_TIMEOUT_SEC, jobId);

//        如果不是正常退出，就是compile error
        if (result.getExitCode() != 0) {
            String output = result.getOutput();
            return new ExecuteMessage()
                    .setExitValue(2)
                    .setStatus("Compile Error")
                    .setMessage(output != null ? output : "编译失败，无错误输出");
        }
        return null;
    }

    /** 仅执行命令（不含编译），判题时编译已在 compileIfNeeded 中完成 */
    private String buildRunOnlyCmd(LanguageEnum langEnum, String baseName) {
        return String.format(langEnum.getRunOnlyCmd(), baseName, baseName, baseName);
    }

    private String stripExtension(String filename) {
        if (filename == null) return "Main";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    /**
     * 解析 SandboxResult → ExecuteMessage，从 /usr/bin/time -v stderr 中提取 time/memory。
     */
    private ExecuteMessage parseResult(SandboxResult result) {
        ExecuteMessage msg = new ExecuteMessage();
        msg.setExitValue(result.getExitCode());
        msg.setStatus(ExecuteMessage.getStatus(result.getExitCode()));

        String output = result.getOutput();
        if (output == null || output.isEmpty()) {
            msg.setMessage("");
            return msg;
        }

        // 提取 Elapsed time，格式: "Elapsed (wall clock) time (h:mm:ss or m:ss): 0:01.23"
        Matcher timeMatcher = Pattern.compile(
                "Elapsed \\(wall clock\\) time.*?: (\\d+):(\\d+(?:\\.\\d+)?)").matcher(output);
        if (timeMatcher.find()) {
            int minutes = Integer.parseInt(timeMatcher.group(1));
            double seconds = Double.parseDouble(timeMatcher.group(2));
            msg.setTime(minutes * 60 + seconds);
        }

        // 提取 Maximum resident set size (kbytes)
        Matcher memMatcher = Pattern.compile(
                "Maximum resident set size \\(kbytes\\): (\\d+)").matcher(output);
        if (memMatcher.find()) {
            msg.setMemory(Long.parseLong(memMatcher.group(1)));
        }

        // 去掉 /usr/bin/time 输出，只保留程序的实际 stdout/stderr
        String userOutput = stripTimeOutput(output);
        if (ExecuteMessage.show(result.getExitCode())) {
            msg.setMessage(userOutput);
        } else {
            msg.setMessage(msg.getStatus());
        }

        return msg;
    }

    /**
     * 从混合输出中去掉 /usr/bin/time -v 的诊断信息块。
     */
    private String stripTimeOutput(String output) {
        int idx = output.indexOf("Command being timed:");
        if (idx < 0) {
            return output.trim();
        }
        // time 输出在末尾（stderr），去掉从 "Command being timed:" 开始的全部内容
        return output.substring(0, idx).trim();
    }

    private String normalizeOutput(String s) {
        if (s == null) return "";
        return s.trim().replace("\r\n", "\n").replace("\r", "\n");
    }

    private String buildWaMessage(String input, String expected, String actual, int caseIdx, int total) {
        return String.format(
                "第 %d/%d 个测试用例答案错误\n测试输入:\n%s\n\n期望输出:\n%s\n\n实际输出:\n%s",
                caseIdx, total,
                input != null && !input.isEmpty() ? input : "(空)",
                expected != null ? expected : "(空)",
                actual != null ? actual : "(空)");
    }

    /**
     * 生成 Python checker 脚本（写入文件执行，不做 shell 转义）。
     * checkerConfig 格式: 每行 \"name type min max [charset]\"，末行 \"rounds N\"。
     */
    // ============================================================
    // MQ 结果发送
    // ============================================================

    private void sendResult(Long submissionId, ExecuteMessage result) {
        sendResult(submissionId, result, null);
    }

    private void sendResult(Long submissionId, ExecuteMessage result, String complexity) {
        Map<String, Object> message = new HashMap<>();
        message.put("submissionId", submissionId);
        message.put("executeMessage", result);
        if (complexity != null) {
            message.put("complexity", complexity);
        }
        rabbitTemplate.convertAndSend("doj.topic", "judging.result", message);
        log.info("判题结果已发送 MQ: submissionId={}, status={}", submissionId, result.getStatus());
    }

    private void sendErrorResult(Long submissionId, String errorMessage) {
        ExecuteMessage error = new ExecuteMessage()
                .setExitValue(1)
                .setStatus("System Error")
                .setMessage(errorMessage);
        sendResult(submissionId, error);
    }
}
