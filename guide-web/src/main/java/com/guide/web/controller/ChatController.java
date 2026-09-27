package com.guide.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.common.result.Result;
import com.guide.dao.entity.QuickQaDO;
import com.guide.service.chat.ChatStreamService;
import com.guide.service.interaction.InteractionService;
import com.guide.service.llm.ChatMessage;
import com.guide.service.llm.DeepSeekClient;
import com.guide.service.llm.EmotionAnalyzer;
import com.guide.service.llm.HistorySanitizer;
import com.guide.service.quickqa.QuickQaService;
import com.guide.service.shortlink.ShortLinkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答接口：
 * - POST /api/chat/stream  流式（SSE）：token 流 + 按序音频 + 短链卡片
 * - POST /api/chat         同步基线（A/B 对比与降级用）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatStreamService chatStreamService;
    private final InteractionService interactionService;
    private final DeepSeekClient deepSeekClient;
    private final ShortLinkService shortLinkService;
    private final QuickQaService quickQaService;

    @Value("${guide.chat.system-prompt}")
    private String systemPrompt;

    @Value("${guide.chat.history-max:10}")
    private int historyMax;

    @Value("${guide.chat.message-max-chars:2000}")
    private int messageMaxChars;

    public ChatController(ChatStreamService chatStreamService,
                          InteractionService interactionService,
                          DeepSeekClient deepSeekClient,
                          ShortLinkService shortLinkService,
                          QuickQaService quickQaService) {
        this.chatStreamService = chatStreamService;
        this.interactionService = interactionService;
        this.deepSeekClient = deepSeekClient;
        this.shortLinkService = shortLinkService;
        this.quickQaService = quickQaService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody JsonNode body) {
        String question = body.path("question").asText("").trim();
        if (question.isEmpty()) {
            throw new BizException(ErrorCode.QUESTION_EMPTY);
        }
        List<ChatMessage> history = HistorySanitizer.sanitize(body.get("history"), historyMax, messageMaxChars);
        // 快问快答优先：命中预制条目直接走秒回链路，不消耗 LLM
        QuickQaDO quick = quickQaService.match(question);
        if (quick != null) {
            return chatStreamService.streamQuick(quick, question);
        }
        return chatStreamService.streamChat(question, history, systemPrompt);
    }

    @PostMapping
    public Result<Map<String, Object>> chat(@RequestBody JsonNode body) {
        String question = body.path("question").asText("").trim();
        if (question.isEmpty()) {
            throw new BizException(ErrorCode.QUESTION_EMPTY);
        }
        List<ChatMessage> history = HistorySanitizer.sanitize(body.get("history"), historyMax, messageMaxChars);
        Map<String, Object> result;
        String emotion;
        QuickQaDO quick = quickQaService.match(question);
        if (quick != null) {
            result = chatStreamService.chatQuick(quick);
            emotion = EmotionAnalyzer.analyze(question);
        } else {
            result = chatStreamService.chatSync(question, history, systemPrompt);
            emotion = EmotionAnalyzer.analyze(question);
        }

        // 落库 + 短链（与流式口径一致，便于两版 A/B 数据可比）
        String answer = (String) result.get("answer");
        List<ShortLinkService.LinkItem> links = shortLinkService.buildLinksFor(question, answer);
        result.put("links", links);
        interactionService.save(question, answer, emotion, links);
        return Result.success(result);
    }

    /** 游客端「猜你想问」chips：启用中的预制问题（不含答案，答案走正常问答链路返回） */
    @org.springframework.web.bind.annotation.GetMapping("/quick-qa")
    public Result<List<Map<String, Object>>> quickQa() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (QuickQaDO qa : quickQaService.listEnabled()) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", qa.getId());
            item.put("question", qa.getQuestion());
            items.add(item);
        }
        return Result.success(items);
    }

    /** 供前端探活：Key 未配置时前端直接提示 */
    @org.springframework.web.bind.annotation.GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("llmConfigured", deepSeekClient.isConfigured());
        return Result.success(result);
    }
}
