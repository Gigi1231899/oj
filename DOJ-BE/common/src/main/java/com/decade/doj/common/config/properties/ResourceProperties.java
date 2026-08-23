package com.decade.doj.common.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
/*主要用于静态资源访问路径和文件存储路径，例如题目代码、上传文件等资源的映射。*/
@Data
@ConfigurationProperties(prefix = "doj.resource")
public class ResourceProperties {

    private String codePath;
    private String location;
    private String request;

}
