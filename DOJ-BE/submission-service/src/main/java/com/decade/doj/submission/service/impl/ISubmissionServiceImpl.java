package com.decade.doj.submission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.decade.doj.common.domain.PageDTO;
import com.decade.doj.common.domain.vo.ExecuteMessage;
import com.decade.doj.common.domain.vo.SubmissionStatsVO;
import com.decade.doj.submission.domain.dto.SubmissionPageQueryDTO;
import com.decade.doj.submission.domain.po.Submission;
import com.decade.doj.submission.service.ISubmissionService;
import com.decade.doj.submission.mapper.SubmissionMapper;
import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* @author qzj
* @description 针对表【submission(代码提交记录表)】的数据库操作Service实现
* @createDate 2025-06-05 13:28:52
*/
@Service
@Slf4j
@RequiredArgsConstructor
public class ISubmissionServiceImpl extends ServiceImpl<SubmissionMapper, Submission>
    implements ISubmissionService {

    public PageDTO<Submission> pageQuery(SubmissionPageQueryDTO submissionPageQueryDTO) {
        // 如果 submissionId 存在，则执行精确查询
        if (submissionPageQueryDTO.getSubmissionId() != null) {
            Submission submission = this.getById(submissionPageQueryDTO.getSubmissionId());
            if (submission == null) {
                return PageDTO.empty(0L, 0L);
            }
            return PageDTO.fullPage(1L, 1L, List.of(submission));
        }

        log.info("分页查询提交列表: {}", submissionPageQueryDTO);
        log.info("userId={}, problemId={}, status={}, language={}",
                submissionPageQueryDTO.getUserId(),
                submissionPageQueryDTO.getProblemId(),
                submissionPageQueryDTO.getStatus(),
                submissionPageQueryDTO.getLanguage());

        String user = submissionPageQueryDTO.getUserId();
        String problem = submissionPageQueryDTO.getProblemId();

        Page<Submission> submissionList = lambdaQuery()
                .like(user != null && !user.isBlank(), Submission::getUserName, submissionPageQueryDTO.getUserId())
                .like(problem != null && !problem.isBlank(), Submission::getProblemName, submissionPageQueryDTO.getProblemId())
                .eq(submissionPageQueryDTO.getStatus() != null, Submission::getStatus, submissionPageQueryDTO.getStatus())
                .eq(submissionPageQueryDTO.getLanguage() != null, Submission::getLanguage, submissionPageQueryDTO.getLanguage())
                .page(submissionPageQueryDTO.toMpPage("submit_time", false));

        return PageDTO.fullPage(submissionList.getTotal(), submissionList.getPages(), submissionList.getRecords());
    }

    @Override
    public SubmissionStatsVO getStats() {
        // 获取总提交数
        long totalSubmissions = this.count();

        // 获取今日提交数
        LocalDate today = LocalDate.now();
        Date startOfDay = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
        long todaySubmissions = this.lambdaQuery()
                .ge(Submission::getSubmitTime, startOfDay)
                .count();

        return new SubmissionStatsVO(totalSubmissions, todaySubmissions);
    }

    @Override
    public Map<Long, Integer> getBatchStatus(List<Long> problemIds, Long userId) {
        if (CollUtil.isEmpty(problemIds) || userId == null) {
            return Collections.emptyMap();
        }
        List<Submission> submissions = lambdaQuery()
                .select(Submission::getProblemId, Submission::getExitValue)
                .in(Submission::getProblemId, problemIds)
                .eq(Submission::getUserId, userId)
                .list();
        return toStatusMap(submissions);
    }

    @Override
    public Map<Long, Integer> getMyStatus(Long userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        List<Submission> submissions = lambdaQuery()
                .select(Submission::getProblemId, Submission::getExitValue)
                .eq(Submission::getUserId, userId)
                .list();
        return toStatusMap(submissions);
    }

    /**
     * 将提交记录转为 problemId -> status（1=已解决 2=尝试中）
     * 同一道题只要有一条 Accepted 就算已解决
     */
    private Map<Long, Integer> toStatusMap(List<Submission> submissions) {
        Map<Long, Integer> result = new HashMap<>();
        for (Submission submission : submissions) {
            if (submission.getProblemId() == null) {
                continue;
            }
            int status = ExecuteMessage.getStatus(submission.getExitValue()).equals("Accepted") ? 1 : 2;
            result.merge(submission.getProblemId(), status, Math::min);
        }
        return result;
    }

}
