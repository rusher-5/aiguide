package com.guide.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 管理端鉴权（双通道）：
 * - Basic Auth：浏览器访问管理页的传统方式，首次 401 挑战后浏览器自动携带凭据
 * - Bearer JWT：与短链平台共享的用户体系——统一控制台携带短链平台 token 可直接调用
 */
@Service
public class AdminAuthService {

    private final JwtVerifier jwtVerifier;
    private final String adminUser;
    private final String adminPass;

    public AdminAuthService(JwtVerifier jwtVerifier,
                            @Value("${guide.admin.user:admin}") String adminUser,
                            @Value("${guide.admin.pass:admin123}") String adminPass) {
        this.jwtVerifier = jwtVerifier;
        this.adminUser = adminUser;
        this.adminPass = adminPass;
    }

    /**
     * 校验 Authorization 头：Basic（管理账号）或 Bearer（短链平台共享 JWT）任一通过即可。
     */
    public boolean authorize(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }
        if (authHeader.startsWith("Basic ")) {
            return checkBasic(authHeader.substring(6));
        }
        if (authHeader.startsWith("Bearer ")) {
            return jwtVerifier.verify(authHeader.substring(7)) != null;
        }
        return false;
    }

    private boolean checkBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx <= 0) {
                return false;
            }
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            return adminUser.equals(user) && adminPass.equals(pass);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
