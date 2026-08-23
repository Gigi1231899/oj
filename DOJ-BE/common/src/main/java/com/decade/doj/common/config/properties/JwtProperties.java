package com.decade.doj.common.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
/*负责 JWT 相关信息，例如密钥库位置、密码、别名、token 过期时间、授权头字段等。
作用是让各服务统一使用同一套 JWT 配置。*/

@Data
@ConfigurationProperties(prefix = "doj.jwt")
public class JwtProperties {
    private Resource location;
    private String password;
    private String alias;
    private Duration tokenTTL = Duration.ofMinutes(10);
    private Duration refreshTokenTTL = Duration.ofDays(7);

    private String authorization;
    private String secretKey;
}
