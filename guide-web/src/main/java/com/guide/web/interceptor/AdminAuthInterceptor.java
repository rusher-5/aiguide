package com.guide.web.interceptor;

import com.guide.service.auth.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理端鉴权拦截器：Basic Auth（管理账号）或 Bearer（短链平台共享 JWT）双通道。
 * 受保护页面（/admin）返回 401 + WWW-Authenticate 挑战头触发浏览器弹窗。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private static final String CHALLENGE_BODY =
            "{\"code\":40100,\"message\":\"需要管理员登录\",\"data\":null}";

    private final AdminAuthService adminAuthService;

    public AdminAuthInterceptor(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        boolean pass = adminAuthService.authorize(request.getHeader("Authorization"));
        if (pass) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Basic 挑战：浏览器收到后弹出登录框
        response.setHeader("WWW-Authenticate", "Basic realm=\"AI-Guide-Admin\"");
        response.getWriter().write(CHALLENGE_BODY);
        return false;
    }
}
