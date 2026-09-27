package com.guide.service.llm;

import java.util.List;

/**
 * 情感分析（口径：分析【用户问题】而非 AI 回答——统计的是游客满意度，不是模型文风）
 */
public final class EmotionAnalyzer {

    private static final List<String> POSITIVE =
            List.of("好", "满意", "喜欢", "棒", "不错", "开心", "赞", "感谢", "有趣", "厉害");
    private static final List<String> NEGATIVE =
            List.of("差", "不好", "失望", "糟糕", "恶心", "烦", "差劲", "无聊", "贵");

    private EmotionAnalyzer() {
    }

    public static String analyze(String question) {
        if (question == null || question.isEmpty()) {
            return "neutral";
        }
        int score = 0;
        for (String w : POSITIVE) {
            if (question.contains(w)) {
                score++;
            }
        }
        for (String w : NEGATIVE) {
            if (question.contains(w)) {
                score--;
            }
        }
        if (score > 0) {
            return "positive";
        }
        if (score < 0) {
            return "negative";
        }
        return "neutral";
    }
}
