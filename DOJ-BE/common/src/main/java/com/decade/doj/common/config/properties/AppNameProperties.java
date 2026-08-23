package com.decade.doj.common.config.properties;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/*方便在代码里拿到当前应用名称*/
@Data
@Component
public class AppNameProperties {

    @Value("${spring.application.name}")
    private String name;
}
