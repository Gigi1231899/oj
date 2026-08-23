package com.decade.doj.common.client;

import com.decade.doj.common.domain.R;
import com.decade.doj.common.domain.po.Submission;
import com.decade.doj.common.domain.vo.ProblemStatusVO;
import com.decade.doj.common.domain.vo.SubmissionStatsVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient("submission-service")
public interface SubmissionClient {

    @PostMapping("/submission/submit")
    R<Long> submit(@RequestBody Submission submission);

    @GetMapping("/submission/stats")
    R<SubmissionStatsVO> getStats();

    /**
     * 批量查询指定题目对指定用户的状态（0=未开始 1=已解决 2=尝试中）
     * 未提交过的题目不包含在返回列表中
     */
    @GetMapping("/submission/batch-status")
    R<List<ProblemStatusVO>> batchStatus(@RequestParam("problemIds") List<Long> problemIds,
                                         @RequestParam("userId") Long userId);

    /**
     * 查询指定用户提交过的所有题目状态（0=未开始 1=已解决 2=尝试中）
     * 未提交过的题目不包含在返回列表中
     */
    @GetMapping("/submission/my-status")
    R<List<ProblemStatusVO>> myStatus(@RequestParam("userId") Long userId);

}
