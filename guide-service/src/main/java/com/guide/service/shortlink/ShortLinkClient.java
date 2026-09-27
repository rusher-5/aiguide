package com.guide.service.shortlink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guide.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 短链平台 HTTP 客户端（对接 urllink / Spring Boot 短链平台）：
 * - 服务账号登录换 JWT（401 自动重登一次），登录态缓存复用
 * - 创建短链 / 查询统计，响应按 JsonNode 解析，避免与短链平台类结构强耦合
 */
@Service
public class ShortLinkClient {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final RestClient restClient;
    private final String serviceUsername;
    private final String servicePassword;

    /** 服务账号登录态；volatile 双检锁保证并发下只重登一次 */
    private volatile String token;

    public ShortLinkClient(@Value("${guide.shortlink.base-url:http://localhost:8080}") String baseUrl,
                           @Value("${guide.shortlink.service-username:guide_bot}") String serviceUsername,
                           @Value("${guide.shortlink.service-password:}") String servicePassword) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.serviceUsername = serviceUsername;
        this.servicePassword = servicePassword;
    }

    /**
     * 创建短链，返回 {shortCode, shortUrl}；平台不可用/参数非法抛异常，由上层降级长链。
     */
    public Map<String, String> createLink(String longUrl, String customCode, int expireDays) {
        return withAuth(token -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("longUrl", longUrl);
            if (customCode != null && !customCode.isBlank()) {
                body.put("customCode", customCode);
            }
            body.put("expireDays", expireDays);

            String resp = restClient.post()
                    .uri("/api/v1/link/create")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(resp);
            ensureSuccess(root, "创建短链");
            JsonNode data = root.path("data");
            return Map.of(
                    "shortCode", data.path("shortCode").asText(""),
                    "shortUrl", data.path("shortUrl").asText(""));
        });
    }

    /**
     * 查询短链统计，返回 {pv, uv, todayPv}；失败抛异常，由上层降级（漏斗数据缺失但不影响主流程）。
     */
    public Map<String, Long> getStats(String shortCode) {
        return withAuth(token -> {
            String resp = restClient.get()
                    .uri("/api/v1/link/{code}/stats", shortCode)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(resp);
            ensureSuccess(root, "查询统计");
            JsonNode data = root.path("data");
            return Map.of(
                    "pv", data.path("pv").asLong(0),
                    "uv", data.path("uv").asLong(0),
                    "todayPv", data.path("todayPv").asLong(0));
        });
    }

    public boolean isConfigured() {
        return serviceUsername != null && !serviceUsername.isBlank()
                && servicePassword != null && !servicePassword.isBlank();
    }

    // ============================================================
    // 登录态管理
    // ============================================================

    private <T> T withAuth(AuthedCall<T> call) {
        String current;
        try {
            current = ensureToken();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("短链平台登录失败: " + e.getMessage(), e);
        }
        try {
            return call.apply(current);
        } catch (UnauthorizedException e) {
            // token 过期：重登一次后重试
            invalidateToken();
            try {
                String refreshed = ensureToken();
                return call.apply(refreshed);
            } catch (UnauthorizedException e2) {
                throw new IllegalStateException("短链平台鉴权失败", e2);
            } catch (Exception e2) {
                throw new IllegalStateException("短链平台调用失败: " + e2.getMessage(), e2);
            }
        } catch (Exception e) {
            throw new IllegalStateException("短链平台调用失败: " + e.getMessage(), e);
        }
    }

    private String ensureToken() {
        String current = token;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (token != null) {
                return token;
            }
            if (!isConfigured()) {
                throw new IllegalStateException("短链平台服务账号未配置（guide.shortlink.service-username/password）");
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("username", serviceUsername);
            body.put("password", servicePassword);
            try {
                String resp = restClient.post()
                        .uri("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body.toString())
                        .retrieve()
                        .body(String.class);
                JsonNode root = mapper.readTree(resp);
                ensureSuccess(root, "服务账号登录");
                String newToken = root.path("data").path("token").asText("");
                if (newToken.isEmpty()) {
                    throw new IllegalStateException("短链平台登录返回空 token");
                }
                token = newToken;
                log.info("短链平台服务账号登录成功（{}）", serviceUsername);
                return token;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("短链平台登录失败: " + e.getMessage(), e);
            }
        }
    }

    private void invalidateToken() {
        token = null;
    }

    private void ensureSuccess(JsonNode root, String action) {
        int code = root.path("code").asInt(-1);
        if (code == ErrorCode.UNAUTHORIZED.getCode()) {
            throw new UnauthorizedException();
        }
        if (code != 0) {
            String message = root.path("message").asText("unknown");
            throw new IllegalStateException(action + "失败: code=" + code + " message=" + message);
        }
    }

    @FunctionalInterface
    private interface AuthedCall<T> {
        T apply(String token) throws Exception;
    }

    /** 401 专用信号：触发重登重试 */
    private static class UnauthorizedException extends RuntimeException {
    }
}
