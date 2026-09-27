package com.guide.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端净化客户端提交的对话历史：
 * - 只允许 user/assistant 角色（拦截伪造 system 角色的提示词注入）
 * - 只保留最近 N 条（防 token 无限膨胀）
 * - 单条长度截断
 */
public final class HistorySanitizer {

    private HistorySanitizer() {
    }

    public static List<ChatMessage> sanitize(JsonNode historyNode, int maxMessages, int maxChars) {
        List<ChatMessage> cleaned = new ArrayList<>();
        if (historyNode == null || !historyNode.isArray()) {
            return cleaned;
        }
        for (JsonNode item : historyNode) {
            if (item == null || !item.isObject()) {
                continue;
            }
            JsonNode roleNode = item.get("role");
            JsonNode contentNode = item.get("content");
            if (roleNode == null || contentNode == null || !contentNode.isTextual()) {
                continue;
            }
            String role = roleNode.asText("");
            String content = contentNode.asText("").trim();
            if (("user".equals(role) || "assistant".equals(role)) && !content.isEmpty()) {
                cleaned.add(new ChatMessage(role, truncate(content, maxChars)));
            }
        }
        // 只保留最近 N 条
        return cleaned.size() <= maxMessages
                ? cleaned
                : new ArrayList<>(cleaned.subList(cleaned.size() - maxMessages, cleaned.size()));
    }

    private static String truncate(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
