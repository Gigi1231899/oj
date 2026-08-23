package com.decade.doj.submission.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.decade.doj.common.domain.PageDTO;
import com.decade.doj.common.domain.vo.SubmissionStatsVO;
import com.decade.doj.submission.domain.dto.SubmissionPageQueryDTO;
import com.decade.doj.submission.domain.po.Submission;

import java.util.List;
import java.util.Map;

/**
* @author qzj
* @description 针对表【submission(代码提交记录表)】的数据库操作Service
* @createDate 2025-06-05 13:28:52
*/
public interface ISubmissionService extends IService<Submission> {
    PageDTO<Submission> pageQuery(SubmissionPageQueryDTO submissionPageQueryDTO);

    SubmissionStatsVO getStats();

    /**
     * 批量查询指定题目对指定用户的状态
     * 返回 problemId -> status（1=已解决 2=尝试中），未提交过的题目不出现在结果中
     */
    Map<Long, Integer> getBatchStatus(List<Long> problemIds, Long userId);

    /**
     * 查询指定用户提交过的所有题目状态
     * 返回 problemId -> status（1=已解决 2=尝试中），未提交过的题目不出现在结果中
     */
    Map<Long, Integer> getMyStatus(Long userId);
}
