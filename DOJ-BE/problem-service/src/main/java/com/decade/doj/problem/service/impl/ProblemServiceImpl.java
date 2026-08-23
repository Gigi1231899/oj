package com.decade.doj.problem.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.decade.doj.common.client.SubmissionClient;
import com.decade.doj.common.domain.PageDTO;
import com.decade.doj.common.domain.R;
import com.decade.doj.common.domain.vo.ProblemStatusVO;
import com.decade.doj.common.utils.UserContext;
import com.decade.doj.problem.domain.document.ProblemDocument;
import com.decade.doj.problem.domain.dto.ProblemPageQueryDTO;
import com.decade.doj.problem.domain.po.Problem;
import com.decade.doj.problem.domain.po.ProblemTag;
import com.decade.doj.problem.domain.po.Tag;
import com.decade.doj.problem.mapper.ProblemMapper;
import com.decade.doj.problem.mapper.ProblemTagMapper;
import com.decade.doj.problem.mapper.TagMapper;
import com.decade.doj.problem.repository.ProblemRepository;
import com.decade.doj.problem.service.IProblemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题目服务实现
 *
 * 说明：题目对当前用户的状态（已解决/尝试中/未开始）来自 submission-service， 本服务不再跨库访问
 * doj_submission.submission 表（ShardingSphere 单数据源限制）。 分页查询先取当前页题目，再调用
 * submission-service 批量查询状态后在内存合并。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemServiceImpl extends ServiceImpl<ProblemMapper, Problem> implements IProblemService {

    private static final String CACHE_NAME = "problemDetail";
    private static final String CACHE_UPDATE_EXCHANGE = "cache.update.exchange";

    private final CacheManager caffeineCacheManager;
    private final CacheManager redisCacheManager;
    private final RabbitTemplate rabbitTemplate;
    private final ProblemRepository problemRepository;
    private final ElasticsearchRestTemplate elasticsearchTemplate;
    private final TagMapper tagMapper;
    private final ProblemTagMapper problemTagMapper;
    private final SubmissionClient submissionClient;

    // ==================== 基础查询 ====================
    @Override
    public List<Problem> list() {
        List<Problem> problems = baseMapper.selectList(null);
        if (CollUtil.isNotEmpty(problems)) {
            attachTags(problems);
        }
        return problems;
    }

    @Override
    public Problem getById(Serializable id) {
        String cacheKey = CACHE_NAME + ":" + id;
        Cache caffeine = caffeineCacheManager.getCache(CACHE_NAME);
        if (caffeine != null) {
            Problem cached = caffeine.get(cacheKey, Problem.class);
            if (cached != null) {
                return cached;
            }
        }
        Cache redis = redisCacheManager.getCache(CACHE_NAME);
        if (redis != null) {
            Problem cached = redis.get(cacheKey, Problem.class);
            if (cached != null) {
                if (caffeine != null) {
                    caffeine.put(cacheKey, cached);
                }
                return cached;
            }
        }
        Problem problem = baseMapper.selectById(id);
        if (problem != null) {
            attachTags(Collections.singletonList(problem));
            if (redis != null) {
                redis.put(cacheKey, problem);
            }
            if (caffeine != null) {
                caffeine.put(cacheKey, problem);
            }
        }
        return problem;
    }

    @Override
    public PageDTO<Problem> pageQuery(ProblemPageQueryDTO query) {
        Long userId = UserContext.getCurrentUser();

        // 1. 需要按状态筛选时，先从 submission-service 取该用户的状态映射，
        //    转为题目 id 集合（已解决/尝试中 -> ids IN；未开始 -> excludeIds NOT IN）
        List<Long> ids = null;
        List<Long> excludeIds = null;
        if (StrUtil.isNotBlank(query.getStatus()) && userId != null) {
            Map<Long, Integer> myStatus = toStatusMap(submissionClient.myStatus(userId));
            if ("已解决".equals(query.getStatus())) {
                ids = filterByStatus(myStatus, 1);
                if (CollUtil.isEmpty(ids)) {
                    return PageDTO.empty(0L, 0L);
                }
            } else if ("尝试中".equals(query.getStatus())) {
                ids = filterByStatus(myStatus, 2);
                if (CollUtil.isEmpty(ids)) {
                    return PageDTO.empty(0L, 0L);
                }
            } else if ("未开始".equals(query.getStatus())) {
                excludeIds = new ArrayList<>(myStatus.keySet());
            }
        }

        // 2. 纯单库分页查询（不再触碰 doj_submission）
        Page<Problem> page = query.toMpPage("id", true);
        Page<Problem> result = baseMapper.selectPageWithFilters(page, ids, excludeIds,
                query.getDifficulty(), query.getTags());

        // 3. 填充标签，并调用 submission-service 批量合并题目状态
        List<Problem> records = result.getRecords();
        if (CollUtil.isNotEmpty(records)) {
            attachTags(records);
            if (userId != null) {
                List<Long> problemIds = records.stream().map(Problem::getId).collect(Collectors.toList());
                Map<Long, Integer> statusMap = toStatusMap(submissionClient.batchStatus(problemIds, userId));
                for (Problem p : records) {
                    p.setStatus(formatStatus(statusMap.getOrDefault(p.getId(), 0)));
                }
            }
        }
        return PageDTO.of(result);
    }

    // ==================== 新增 / 修改 / 删除 ====================
    @Override
    @Transactional
    public boolean save(Problem problem) {
        problem.setSourceType(normalizeProblemSourceType(problem.getSourceType()));
        boolean saved = super.save(problem);
        if (saved) {
            syncTags(problem);
            problemRepository.save(toDocument(problem));
            evictAndBroadcast(problem.getId());
        }
        return saved;
    }

    @Override
    @Transactional
    public boolean updateById(Problem problem) {
        problem.setSourceType(normalizeProblemSourceType(problem.getSourceType()));
        boolean updated = super.updateById(problem);
        if (updated) {
            syncTags(problem);
            Problem fresh = baseMapper.selectById(problem.getId());
            problemRepository.save(toDocument(fresh != null ? fresh : problem));
            evictAndBroadcast(problem.getId());
        }
        return updated;
    }

    @Override
    @Transactional
    public boolean removeById(Serializable id) {
        boolean removed = super.removeById(id);
        if (removed) {
            problemTagMapper.delete(new LambdaQueryWrapper<ProblemTag>().eq(ProblemTag::getProblemId, id));
            try {
                problemRepository.deleteById((Long) id);
            } catch (Exception e) {
                log.warn("删除ES文档失败: id={}", id, e);
            }
            evictAndBroadcast(String.valueOf(id));
        }
        return removed;
    }

    // ==================== 统计更新（MQ 回调） ====================
    @Override
    public void updateProblemStats(Long problemId, boolean isAccepted) {
        Problem problem = baseMapper.selectById(problemId);
        if (problem == null) {
            log.warn("更新题目统计失败，题目不存在: id={}", problemId);
            return;
        }
        int attempt = (problem.getTotalAttempt() == null ? 0 : problem.getTotalAttempt()) + 1;
        int pass = (problem.getTotalPass() == null ? 0 : problem.getTotalPass()) + (isAccepted ? 1 : 0);
        problem.setTotalAttempt(attempt);
        problem.setTotalPass(pass);
        baseMapper.updateById(problem);
        problemRepository.save(toDocument(problem));
        evictAndBroadcast(problemId);
    }

    // ==================== ES 管理 ====================
    @Override
    public int syncAllToElasticsearch() {
        List<Problem> problems = baseMapper.selectList(null);
        if (CollUtil.isNotEmpty(problems)) {
            attachTags(problems);
        }
        List<ProblemDocument> docs = problems.stream().map(this::toDocument).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(docs)) {
            problemRepository.saveAll(docs);
        }
        log.info("同步题目到 ES 完成，共 {} 条", docs.size());
        return docs.size();
    }

    @Override
    public int reindexAll() {
        IndexOperations indexOps = elasticsearchTemplate.indexOps(ProblemDocument.class);
        try {
            indexOps.delete();
        } catch (Exception e) {
            log.warn("删除 ES 索引失败（可能不存在）: {}", e.getMessage());
        }
        indexOps.create();
        indexOps.putMapping(indexOps.createMapping());
        return syncAllToElasticsearch();
    }

    @Override
    public void resetProblems() {
        List<Problem> problems = baseMapper.selectList(null);
        if (CollUtil.isEmpty(problems)) {
            return;
        }
        for (Problem p : problems) {
            p.setTotalAttempt(0);
            p.setTotalPass(0);
            baseMapper.updateById(p);
        }
        log.info("已重置 {} 道题的统计数据", problems.size());
    }

    // ==================== 缓存 ====================
    @RabbitListener(queues = "#{problemCacheUpdateQueue.name}")
    public void onCacheUpdate(String cacheKey) {
        log.info("收到缓存更新广播: {}", cacheKey);
        evictCache(cacheKey);
    }

    private void evictAndBroadcast(Long problemId) {
        evictCache(problemId);
        try {
            rabbitTemplate.convertAndSend(CACHE_UPDATE_EXCHANGE, "", String.valueOf(problemId));
        } catch (Exception e) {
            log.warn("广播缓存更新失败: id={}", problemId, e);
        }
    }

    private void evictAndBroadcast(String problemId) {
        evictCache(problemId);
        try {
            rabbitTemplate.convertAndSend(CACHE_UPDATE_EXCHANGE, "", problemId);
        } catch (Exception e) {
            log.warn("广播缓存更新失败: id={}", problemId, e);
        }
    }

    private void evictCache(Long problemId) {
        evictCache(String.valueOf(problemId));
    }

    private void evictCache(String problemId) {
        String cacheKey = CACHE_NAME + ":" + problemId;
        Cache caffeine = caffeineCacheManager.getCache(CACHE_NAME);
        if (caffeine != null) {
            caffeine.evict(cacheKey);
        }
        Cache redis = redisCacheManager.getCache(CACHE_NAME);
        if (redis != null) {
            redis.evict(cacheKey);
        }
    }

    // ==================== 私有辅助方法 ====================
    private void attachTags(List<Problem> problems) {
        if (CollUtil.isEmpty(problems)) {
            return;
        }
        List<Long> problemIds = problems.stream().map(Problem::getId).collect(Collectors.toList());
        List<ProblemTag> ptList = problemTagMapper.selectList(
                new LambdaQueryWrapper<ProblemTag>().in(ProblemTag::getProblemId, problemIds));
        if (CollUtil.isEmpty(ptList)) {
            return;
        }
        List<Long> tagIds = ptList.stream().map(ProblemTag::getTagId).distinct().collect(Collectors.toList());
        Map<Long, String> tagMap = tagMapper.selectBatchIds(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName, (a, b) -> a));
        Map<Long, List<String>> problemTagsMap = new HashMap<>();
        for (ProblemTag pt : ptList) {
            String name = tagMap.get(pt.getTagId());
            if (name != null) {
                problemTagsMap.computeIfAbsent(pt.getProblemId(), k -> new ArrayList<>()).add(name);
            }
        }
        for (Problem p : problems) {
            p.setTags(problemTagsMap.getOrDefault(p.getId(), Collections.emptyList()));
        }
    }

    private void syncTags(Problem problem) {
        if (problem.getId() == null) {
            return;
        }
        problemTagMapper.delete(new LambdaQueryWrapper<ProblemTag>().eq(ProblemTag::getProblemId, problem.getId()));
        if (CollUtil.isEmpty(problem.getTags())) {
            return;
        }
        List<Tag> existing = tagMapper.selectList(new LambdaQueryWrapper<Tag>().in(Tag::getName, problem.getTags()));
        Map<String, Long> nameIdMap = existing.stream()
                .collect(Collectors.toMap(Tag::getName, Tag::getId, (a, b) -> a));
        for (String tagName : problem.getTags()) {
            Long tagId = nameIdMap.get(tagName);
            if (tagId == null) {
                Tag tag = new Tag().setName(tagName);
                tagMapper.insert(tag);
                tagId = tag.getId();
            }
            problemTagMapper.insert(new ProblemTag(problem.getId(), tagId));
        }
    }

    private ProblemDocument toDocument(Problem problem) {
        return new ProblemDocument(problem.getId(), problem.getName(), problem.getDescription());
    }

    /**
     * 把 submission-service 返回的批量状态转为 problemId -> status 映射
     */
    private Map<Long, Integer> toStatusMap(R<List<ProblemStatusVO>> r) {
        Map<Long, Integer> map = new HashMap<>();
        if (r != null && r.success() && CollUtil.isNotEmpty(r.getData())) {
            for (ProblemStatusVO vo : r.getData()) {
                if (vo.getProblemId() != null && vo.getStatus() != null) {
                    map.put(vo.getProblemId(), vo.getStatus());
                }
            }
        }
        return map;
    }

    private List<Long> filterByStatus(Map<Long, Integer> statusMap, int status) {
        return statusMap.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() == status)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private String formatStatus(int status) {
        switch (status) {
            case 1:
                return "已解决";
            case 2:
                return "尝试中";
            default:
                return "未开始";
        }
    }

    public static String normalizeProblemSourceType(String sourceType) {
        return StrUtil.isBlank(sourceType) ? "custom" : sourceType.trim();
    }
}
