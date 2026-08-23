package com.decade.doj.problem.config;

import com.decade.doj.problem.domain.document.ProblemDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProblemIndexInitializer {

    private final ElasticsearchRestTemplate elasticsearchTemplate;
    /*程序启动时自动检查
    如果 ES 索引还没创建，就帮你创建
            避免因为索引不存在导致后续写入失败*/
    @PostConstruct
    public void initIndex() {
        IndexOperations indexOps = elasticsearchTemplate.indexOps(ProblemDocument.class);
        if (!indexOps.exists()) {
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping());
            log.info("Created Elasticsearch index: {}", indexOps.getIndexCoordinates().getIndexName());
        }
    }
}
