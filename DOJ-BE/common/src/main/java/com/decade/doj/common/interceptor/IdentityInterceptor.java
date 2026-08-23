package com.decade.doj.common.interceptor;

import cn.hutool.core.util.StrUtil;
import com.decade.doj.common.config.properties.JwtProperties;
import com.decade.doj.common.exception.UnauthorizedException;
import com.decade.doj.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class IdentityInterceptor implements HandlerInterceptor {

    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.debug("path: {}", request.getRequestURI());
        String userId = request.getHeader(jwtProperties.getSecretKey());
        if (userId == null) {
            // throw new UnauthorizedException("请访问网关服务!");
            userId = String.valueOf(3);
        }
        log.debug("当前用户ID: {}", userId);
        if (StrUtil.isNotBlank(userId)) {
            UserContext.setCurrentUser(Long.parseLong(userId));
        }
        return true;
    }
// Controller return 之后、Tomcat 线程正式“下班”之前执行。在这里清空 ThreadLocal，是为了保证线程复用时的数据隔离

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContext.clear();
    }

}
