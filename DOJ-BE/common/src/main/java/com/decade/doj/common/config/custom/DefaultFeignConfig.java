package com.decade.doj.common.config.custom;

import com.decade.doj.common.config.properties.JwtProperties;
import feign.Logger;
import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.decade.doj.common.utils.UserContext;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
/*给 Feign 客户端做统一配置。
主要是开启详细日志，并把当前登录用户信息透传到下游服务。*/
public class DefaultFeignConfig {

    private final JwtProperties jwtProperties;

    @Bean
    public Logger.Level feignLogLevel(){
        return Logger.Level.FULL;
    }

    @Bean
    public RequestInterceptor userInfoRequestInterceptor(){
        return template -> {
            // 获取登录用户
            Long userId = UserContext.getCurrentUser();
            if(userId == null) {
                // 如果为空则直接跳过
                return;
            }
            // 如果不为空则放入请求头中，传递给下游微服务
            template.header(jwtProperties.getSecretKey(), userId.toString());
        };
    }
}

