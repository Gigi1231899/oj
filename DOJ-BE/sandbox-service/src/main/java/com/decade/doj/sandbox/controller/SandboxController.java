package com.decade.doj.sandbox.controller;

import com.alibaba.fastjson.JSON;
import com.decade.doj.common.client.ProblemClient;
import com.decade.doj.common.client.SubmissionClient;
import com.decade.doj.common.config.properties.ResourceProperties;
import com.decade.doj.common.domain.R;
import com.decade.doj.common.domain.po.Problem;
import com.decade.doj.common.domain.po.Submission;
import com.decade.doj.common.utils.UserContext;
import com.decade.doj.sandbox.domain.vo.JudgingTask;
import com.decade.doj.sandbox.enums.LanguageEnum;
import com.decade.doj.sandbox.service.ISandboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/sandbox")
@Tag(name = "沙箱相关接口")
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(ResourceProperties.class)
public class SandboxController {

    private final ResourceProperties resourceProperties;

    private final ISandboxService sandboxService;
    private final SubmissionClient submissionClient;
    private final ProblemClient problemClient;
    private final StringRedisTemplate redisTemplate;

    private static final Pattern TEST_CASE_SPLIT_PATTERN = Pattern.compile("(?m)^---$");

    // 说明：run 功能（/sandbox/code、/sandbox/problem）已整体下线，
    // 仅保留提交判题（/sandbox/validate）与测试用例生成（/sandbox/generate-testcases）

    @PostMapping("/generate-testcases")
    @Operation(summary = "根据变量配置和标程生成测试用例")
    public R<List<Map<String, String>>> generateTestCases(@RequestBody Map<String, Object> body) {
        String checkerConfig = (String) body.getOrDefault("checkerConfig", "");
        String standardCode = (String) body.getOrDefault("standardCode", "");
        String standardLang = (String) body.getOrDefault("standardLang", "cpp");
        int rounds = body.containsKey("rounds") ? ((Number) body.get("rounds")).intValue() : 10;
        if (rounds < 1) rounds = 1;
        if (rounds > 20) rounds = 20;

        if (checkerConfig.isBlank() || standardCode.isBlank()) {
            return R.error(400, "checkerConfig 和 standardCode 不能为空");
        }

        try {
            List<Map<String, String>> testCases = sandboxService.generateTestCases(
                    checkerConfig, standardCode, standardLang, rounds);
            return R.ok(testCases);
        } catch (Exception e) {
            log.error("生成测试用例失败", e);
            return R.error(500, "生成失败: " + e.getMessage());
        }
    }

    @PostMapping("/validate")
    @Operation(summary = "验证题目代码(异步提交)")
    public R<Long> runProblemValidate(@RequestParam("file") MultipartFile file,
            @RequestParam("language") @NotBlank String lang,
            @RequestParam("pid") Long pid) throws IOException {
        if (file.isEmpty()) {
            return R.error(400, "上传的文件不能为空!");
        }
        if (LanguageEnum.isInValidLanguage(lang)) {
            return R.error(400, "不支持的编程语言: " + lang);
        }

        String[] Paths = saveFile(file, resourceProperties.getCodePath(), null);
        String codePath = Paths[0];
        TestCaseInfo data = saveText2File(pid, resourceProperties.getCodePath(), Paths[1]);

        // 创建 PENDING 提交记录
        Submission submissionDTO = new Submission();
        submissionDTO.setProblemId(pid);
        submissionDTO.setUserId(UserContext.getCurrentUser());
        submissionDTO.setLanguage(lang);
        submissionDTO.setCode(Paths[2]);
        R<Long> response = submissionClient.submit(submissionDTO);
        if (!response.success()) {
            return R.error(500, "创建提交记录失败: " + response.getMsg());
        }
        Long submissionId = response.getData();

        // 组装判题任务
        JudgingTask task = new JudgingTask()
                .setSubmissionId(submissionId)
                .setLocalPath(codePath)
                .setCode(Paths[2])
                .setInputFileNames(data.getInputFileNames())
                .setOutputAnswers(data.getOutputAnswers())
                .setFilename(file.getOriginalFilename())
                .setLang(lang)
                .setProblemId(pid)
                .setUid(UserContext.getCurrentUser());

//        判题任务入队
        redisTemplate.opsForList().leftPush("judging:queue", JSON.toJSONString(task));
        log.info("判题任务已推入队列, submissionId: {}", submissionId);
        return R.ok(submissionId);
    }

    /*生成测试用例文件*/
    private TestCaseInfo saveText2File(Long pid, String basePath, String folderName) throws IOException {
        if (folderName == null) {
            folderName = UUID.randomUUID().toString();
        }
        String subFolderPathStr = basePath + folderName + FileSystems.getDefault().getSeparator();
        Path subFolderPath = Paths.get(subFolderPathStr);
        Files.createDirectories(subFolderPath);

        Problem problem = problemClient.getProblemById(pid).getData();

        String inputdata = normalizeTestCase(problem.getTestData());
        String outputdata = normalizeTestCase(problem.getTestAns());
        List<String> inputCases = splitTestCases(inputdata);
        List<String> outputCases = splitTestCases(outputdata);
        if (inputCases.size() != outputCases.size()) {
            throw new IOException("测试用例输入与答案数量不匹配");
        }
        if (inputCases.isEmpty()) {
            throw new IOException("题目没有配置测试用例");
        }

        List<String> inputFileNames = new ArrayList<>();
        for (int i = 0; i < inputCases.size(); i++) {
            String caseInputFileName = pid + "_p_input_" + (i + 1) + ".txt";
            Path caseInputFilePath = subFolderPath.resolve(caseInputFileName);
            Files.writeString(caseInputFilePath, inputCases.get(i), StandardCharsets.UTF_8);
            inputFileNames.add(caseInputFileName);
        }
        return new TestCaseInfo(inputFileNames, outputCases);
    }

    private List<String> splitTestCases(String raw) {
        List<String> cases = new ArrayList<>();
        if (raw == null || raw.isBlank()) return cases;
        String[] parts = TEST_CASE_SPLIT_PATTERN.split(raw.trim());
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) cases.add(trimmed);
        }
        return cases;
    }

    /**
     * 将 LeetCode 格式的测试数据转换为 OJ 标准纯文本：
     * "abc" → abc, [1,2,3] → 1 2 3
     */
    private String normalizeTestCase(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        return raw.lines()
                .map(this::normalizeLine)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String normalizeLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) return "";
            return inner.replaceAll("\\s*,\\s*", " ");
        }
        return trimmed;
    }

//    测试用例
    private static class TestCaseInfo {
        private final List<String> inputFileNames;
        private final List<String> outputAnswers;

        public TestCaseInfo(List<String> inputFileNames, List<String> outputAnswers) {
            this.inputFileNames = inputFileNames;
            this.outputAnswers = outputAnswers;
        }
        public List<String> getInputFileNames() { return inputFileNames; }
        public List<String> getOutputAnswers() { return outputAnswers; }
    }

    /*生成代码文件夹，创建代码文件夹，byte写入代码到代码目录下，返回目标filepath,文件夹名,代码内容*/
    private String[] saveFile(MultipartFile file, String basePath, String folderName) throws IOException {
        if (folderName == null) {
            folderName = UUID.randomUUID().toString();
        }
        String subFolderPathStr = basePath + folderName + FileSystems.getDefault().getSeparator();
        Path subFolderPath = Paths.get(subFolderPathStr);
        Files.createDirectories(subFolderPath);
        String origFilename = file.getOriginalFilename();
        if (origFilename == null || origFilename.isBlank()) {
            throw new IOException("上传文件原始文件名为空");
        }
        Path destinationFilePath = subFolderPath.resolve(origFilename);
        byte[] bytes = file.getBytes();
        String content = new String(bytes, StandardCharsets.UTF_8);
        Files.write(destinationFilePath, bytes);
        return new String[]{destinationFilePath.toString(), folderName, content};
    }
}
