package com.guide.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * DeepSeek 客户端（OpenAI 兼容协议），基于 JDK HttpClient 零依赖实现：
 * - streamChat：SSE 流式，token 逐段回调（供前端逐字渲染 + 切句 TTS 流水线）
 * - chat：同步整体返回（供降级接口与 A/B 基线对比）
 */
@Service
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public DeepSeekClient(@Value("${guide.deepseek.api-key:}") String apiKey,
                          @Value("${guide.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                          @Value("${guide.deepseek.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 流式对话：逐 token 回调 onDelta，返回完整回答文本。
     */
    public String streamChat(java.util.List<ChatMessage> messages, Consumer<String> onDelta) {
        ObjectNode body = buildRequest(messages, true);
        HttpRequest request = buildRequest(body);
        try {
            HttpResponse<java.util.stream.Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String first = response.body().findFirst().orElse("");
                log.warn("DeepSeek 流式返回 {}：{}", response.statusCode(), first);
                throw new BizException(ErrorCode.LLM_UNAVAILABLE);
            }
            StringBuilder full = new StringBuilder();
            response.body().forEach(line -> {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (line == null || !line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    JsonNode node = mapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        full.append(delta.asText());
                        onDelta.accept(delta.asText());
                    }
                } catch (IOException e) {
                    // 单行解析失败不中断整体流（保序容错）
                    log.debug("忽略无法解析的 SSE 行: {}", data);
                }
            });
            return full.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LLM_UNAVAILABLE);
        } catch (IOException e) {
            log.warn("DeepSeek 流式请求失败: {}", e.getMessage());
            throw new BizException(ErrorCode.LLM_UNAVAILABLE);
        }
    }

    /**
     * 同步对话（A/B 基线 / 降级接口用）
     */
    public String chat(java.util.List<ChatMessage> messages) {
        ObjectNode body = buildRequest(messages, false);
        HttpRequest request = buildRequest(body);
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("DeepSeek 返回 {}：{}", response.statusCode(), truncate(response.body()));
                throw new BizException(ErrorCode.LLM_UNAVAILABLE);
            }
            JsonNode node = mapper.readTree(response.body());
            return node.path("choices").path(0).path("message").path("content").asText("");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LLM_UNAVAILABLE);
        } catch (IOException e) {
            log.warn("DeepSeek 请求失败: {}", e.getMessage());
            throw new BizException(ErrorCode.LLM_UNAVAILABLE);
        }
    }

    private ObjectNode buildRequest(java.util.List<ChatMessage> messages, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);
        body.put("stream", stream);
        ArrayNode arr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode item = arr.addObject();
            item.put("role", m.role());
            item.put("content", m.content());
        }
        return body;
    }

    private HttpRequest buildRequest(ObjectNode body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
