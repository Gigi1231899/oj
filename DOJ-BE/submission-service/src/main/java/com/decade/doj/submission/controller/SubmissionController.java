package com.decade.doj.submission.controller;

import com.decade.doj.common.domain.PageDTO;
import com.decade.doj.common.domain.R;
import com.decade.doj.common.domain.vo.ExecuteMessage;
import com.decade.doj.common.domain.vo.ProblemStatusVO;
import com.decade.doj.common.domain.vo.SubmissionStatsVO;
import com.decade.doj.common.utils.UserContext;
import com.decade.doj.submission.domain.dto.SubmissionPageQueryDTO;
import com.decade.doj.submission.domain.po.Submission;
import com.decade.doj.submission.service.ISubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/submission")
@Tag(name = "提交相关接口")
@Slf4j
@RequiredArgsConstructor
public class SubmissionController {

    private final ISubmissionService submissionService;

    @PostMapping("/submit")
    @Operation(summary = "提交记录")
    public R<Long> submit(@RequestBody Submission submission) {
        submissionService.save(submission);
        return R.ok(submission.getId());
    }

    @GetMapping("/page")
    @Operation(summary = "分页获取提交列表")
    public R<PageDTO<Submission>> page(SubmissionPageQueryDTO problemPageQueryDTO) {
        PageDTO<Submission> res = submissionService.pageQuery(problemPageQueryDTO);
        return R.ok(res);
    }

    @GetMapping("/stats")
    @Operation(summary = "获取提交统计")
    public R<SubmissionStatsVO> getStats() {
        return R.ok(submissionService.getStats());
    }

    @GetMapping("/match/{id}")
    @Operation(summary = "获取当前用户指定问题的提交详情")
    public R<Integer> getById(@PathVariable String id) {
        List<Submission> submissions = submissionService.lambdaQuery()
                .eq(Submission::getProblemId, id)
                .list();
        int f = 0;
        for (Submission submission : submissions) {
            if (submission.getUserId() != null && submission.getUserId().equals(UserContext.getCurrentUser())) {
                f = 1;
                if (ExecuteMessage.getStatus(submission.getExitValue()).equals("Accepted")) {
                    return R.ok(1); // 已经提交过且通过
                }
            }
        }
        if (f == 1) {
            return R.ok(2); // 已经提交过但未通过
        }
        return R.ok(0);
    }

    @GetMapping("/batch-status")
    @Operation(summary = "批量查询指定题目对指定用户的状态（0=未开始 1=已解决 2=尝试中）")
    public R<List<ProblemStatusVO>> batchStatus(@RequestParam("problemIds") List<Long> problemIds,
                                                @RequestParam("userId") Long userId) {
        return R.ok(toVOList(submissionService.getBatchStatus(problemIds, userId)));
    }

    @GetMapping("/my-status")
    @Operation(summary = "查询指定用户提交过的所有题目状态（0=未开始 1=已解决 2=尝试中）")
    public R<List<ProblemStatusVO>> myStatus(@RequestParam("userId") Long userId) {
        return R.ok(toVOList(submissionService.getMyStatus(userId)));
    }

    private List<ProblemStatusVO> toVOList(Map<Long, Integer> map) {
        return map.entrySet().stream()
                .map(e -> new ProblemStatusVO(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }
}
