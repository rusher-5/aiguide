package com.guide.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.dao.entity.QuickQaDO;
import com.guide.service.interaction.InteractionService;
import com.guide.service.llm.ChatMessage;
import com.guide.service.llm.DeepSeekClient;
import com.guide.service.llm.EmotionAnalyzer;
import com.guide.service.quickqa.QuickQaService;
import com.guide.service.rag.RagService;
import com.guide.service.shortlink.ShortLinkService;
import com.guide.service.tts.AudioRegistry;
import com.guide.service.tts.EdgeTtsClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式问答编排：LLM token 流 → 按句切分 → 线程池并行 TTS → SSE 按序推送。
 *
 * 与 Python 版一致的两个关键设计：
 * 1. 流水线泵挂在最快的水源上——每个 delta 都检查排水（drain），
 *    否则 LLM 出完所有句子后无人触发推送，音频会堆积到流结束（实测教训）。
 * 2. 音频事件携带自增 seq，前端乱序到达、按序播放；服务端保持「队首完成即推」的顺序性。
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /** 句界：。！？!?；; */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;]");

    /** 无标点长句按字符数强制切句，保证语音首包及时 */
    @Value("${guide.chat.stream-flush-chars:60}")
    private int streamFlushChars;

    private final RagService ragService;
    private final DeepSeekClient deepSeekClient;
    private final EdgeTtsClient edgeTtsClient;
    private final AudioRegistry audioRegistry;
    private final ShortLinkService shortLinkService;
    private final QuickQaService quickQaService;
    private final InteractionService interactionService;
    private final ObjectMapper mapper;

    /** 问答流水线线程池：一个请求占一条线程直到 SSE 结束 */
    private final ExecutorService pipelineExecutor = Executors.newFixedThreadPool(8);

    /** TTS 线程池：句级任务短小，2 个 worker 足够流水线化（与 Python 版一致） */
    private final ExecutorService ttsExecutor = Executors.newFixedThreadPool(2);

    public ChatStreamService(RagService ragService,
                             DeepSeekClient deepSeekClient,
                             EdgeTtsClient edgeTtsClient,
                             AudioRegistry audioRegistry,
                             ShortLinkService shortLinkService,
                             QuickQaService quickQaService,
                             InteractionService interactionService,
                             ObjectMapper mapper) {
        this.ragService = ragService;
        this.deepSeekClient = deepSeekClient;
        this.edgeTtsClient = edgeTtsClient;
        this.audioRegistry = audioRegistry;
        this.shortLinkService = shortLinkService;
        this.quickQaService = quickQaService;
        this.interactionService = interactionService;
        this.mapper = mapper;
    }

    /** 队列元素：(seq, TTS future)；future 完成值为 null 表示该句合成失败（跳过，文本体验不受影响） */
    private record PendingAudio(int seq, Future<byte[]> future) {
    }

    /**
     * 流式问答入口：立即返回 SseEmitter，流水线在独立线程上推进。
     * 事件协议（与前端约定）：
     * - text   {delta}                     逐 token 文本
     * - audio  {seq, url}                  按序音频（url 为 /api/audio/{id}）
     * - links  {items:[{scene,shortUrl,longUrl,degraded}]}  命中景点的短链卡片
     * - done   {answer, emotion}           完整回答与情感
     * - error  {message}                   兜底错误
     */
    public SseEmitter streamChat(String question, List<ChatMessage> history, String systemPrompt) {
        SseEmitter emitter = new SseEmitter(120_000L);
        pipelineExecutor.submit(() -> runPipeline(question, history, systemPrompt, emitter));
        return emitter;
    }

    /**
     * 快问快答流式入口：命中预制条目，跳过 LLM/RAG。
     * 体验对齐 LLM 链路：文本按块推送（保留打字机动画），音频取进程内缓存（首次命中现场合成）。
     * done 事件多带 quick=true，前端可展示「秒回」标识。
     */
    public SseEmitter streamQuick(QuickQaDO qa, String question) {
        SseEmitter emitter = new SseEmitter(60_000L);
        pipelineExecutor.submit(() -> runQuickPipeline(qa, question, emitter));
        return emitter;
    }

    /**
     * 同步问答（A/B 基线 / 降级接口）：全量生成 → 整段 TTS → 一次性返回。
     */
    public Map<String, Object> chatSync(String question, List<ChatMessage> history, String systemPrompt) {
        String answer = deepSeekClient.chat(buildMessages(question, history, systemPrompt));
        String audioUrl = synthesizeOnce(answer);
        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("audio_url", audioUrl);
        return result;
    }

    /** 同步快问快答：命中预制条目，毫秒级返回（音频走缓存） */
    public Map<String, Object> chatQuick(QuickQaDO qa) {
        byte[] audio = quickQaService.audioOf(qa, this::synthesize);
        Map<String, Object> result = new HashMap<>();
        result.put("answer", qa.getAnswer());
        result.put("audio_url", audio.length > 0 ? "/api/audio/" + audioRegistry.put(audio) : null);
        result.put("quick", true);
        return result;
    }

    // ============================================================
    // 流水线主体（单线程驱动：所有 emitter.send 都来自本线程，天然无并发竞争）
    // ============================================================

    private void runPipeline(String question, List<ChatMessage> history, String systemPrompt,
                             SseEmitter emitter) {
        try {
            List<ChatMessage> messages = buildMessages(question, history, systemPrompt);

            StringBuilder full = new StringBuilder();
            StringBuilder buf = new StringBuilder();
            ArrayDeque<PendingAudio> pending = new ArrayDeque<>();
            int[] seq = {0};

            String answer = deepSeekClient.streamChat(messages, delta -> {
                full.append(delta);
                buf.append(delta);
                send(emitter, "text", Map.of("delta", delta));
                // 句界或超长缓冲 → 切句提交 TTS
                while (true) {
                    Matcher m = SENTENCE_END.matcher(buf);
                    int cut = m.find() ? m.end()
                            : (buf.length() >= streamFlushChars ? buf.length() : 0);
                    if (cut == 0) {
                        break;
                    }
                    String sentence = buf.substring(0, cut);
                    buf.delete(0, cut);
                    if (!sentence.isBlank()) {
                        int cur = seq[0]++;
                        pending.add(new PendingAudio(cur, ttsExecutor.submit(() -> synthesize(sentence))));
                    }
                }
                // 关键：每个 delta 都排水，而不是只在切句时排水
                drainReady(emitter, pending);
            });

            // 尾句提交
            if (!buf.toString().isBlank()) {
                int cur = seq[0]++;
                pending.add(new PendingAudio(cur, ttsExecutor.submit(() -> synthesize(buf.toString()))));
            }
            // 流结束：按顺序等剩余句子合成完成并推送
            while (!pending.isEmpty()) {
                PendingAudio head = pending.poll();
                pushAudio(emitter, head);
            }

            // 景点短链匹配 → links 事件（失败已内部降级，不会抛出）
            List<ShortLinkService.LinkItem> links =
                    shortLinkService.buildLinksFor(question, answer);
            if (!links.isEmpty()) {
                send(emitter, "links", Map.of("items", links));
            }

            String emotion = EmotionAnalyzer.analyze(question);
            send(emitter, "done", Map.of("answer", answer, "emotion", emotion));
            // 落库：与同步接口口径一致（快慢链路统一进大屏统计）
            interactionService.save(question, answer, emotion, links);
            emitter.complete();
        } catch (Exception e) {
            log.warn("流式问答异常: {}", e.getMessage(), e);
            send(emitter, "error", Map.of("message", "抱歉，我现在暂时无法回答，请稍后再试。"));
            emitter.complete();
        }
    }

    /**
     * 快问快答流水线：文本分块推送 + 缓存音频单段推送 + 短链 + 落库。
     * 与 LLM 流水线口径一致（links/emotion/interaction），保证大屏统计不分快慢链路。
     */
    private void runQuickPipeline(QuickQaDO qa, String question, SseEmitter emitter) {
        try {
            String answer = qa.getAnswer();

            // 打字机效果：按 6 字一块推送，间隔 25ms（100 字答案约 0.4s 走完，体感自然）
            for (int i = 0; i < answer.length(); i += 6) {
                String delta = answer.substring(i, Math.min(i + 6, answer.length()));
                send(emitter, "text", Map.of("delta", delta));
                Thread.sleep(25);
            }

            // 音频：进程内缓存（首次命中现场合成约 1s，之后直接命中）
            byte[] audio = quickQaService.audioOf(qa, this::synthesize);
            if (audio.length > 0) {
                send(emitter, "audio", Map.of("seq", 0, "url", "/api/audio/" + audioRegistry.put(audio)));
            }

            List<ShortLinkService.LinkItem> links = shortLinkService.buildLinksFor(question, answer);
            if (!links.isEmpty()) {
                send(emitter, "links", Map.of("items", links));
            }

            String emotion = EmotionAnalyzer.analyze(question);
            send(emitter, "done", Map.of("answer", answer, "emotion", emotion, "quick", true));
            interactionService.save(question, answer, emotion, links);
            emitter.complete();
        } catch (Exception e) {
            log.warn("快问快答流水线异常: {}", e.getMessage(), e);
            send(emitter, "error", Map.of("message", "抱歉，我现在暂时无法回答，请稍后再试。"));
            emitter.complete();
        }
    }

    /** 队首任务已完成时立即推送（保持顺序：只推队首，不越过未完成的句子） */
    private void drainReady(SseEmitter emitter, ArrayDeque<PendingAudio> pending) {
        while (!pending.isEmpty() && pending.peek().future().isDone()) {
            pushAudio(emitter, pending.poll());
        }
    }

    private void pushAudio(SseEmitter emitter, PendingAudio item) {
        try {
            byte[] audio = item.future().get();
            if (audio != null && audio.length > 0) {
                String id = audioRegistry.put(audio);
                send(emitter, "audio", Map.of("seq", item.seq(), "url", "/api/audio/" + id));
            }
        } catch (Exception e) {
            log.warn("音频推送失败 seq={}: {}", item.seq(), e.getMessage());
        }
    }

    /** 单句合成：失败返回 null（跳过音频，文本体验不受影响）——TTS 是增强而非依赖 */
    private byte[] synthesize(String sentence) {
        String cleaned = cleanForTts(sentence);
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return edgeTtsClient.synthesize(cleaned);
        } catch (Exception e) {
            log.warn("TTS 单句合成失败: {}", e.getMessage());
            return null;
        }
    }

    private String synthesizeOnce(String answer) {
        byte[] audio = synthesize(answer);
        return audio != null ? "/api/audio/" + audioRegistry.put(audio) : null;
    }

    private List<ChatMessage> buildMessages(String question, List<ChatMessage> history,
                                            String systemPrompt) {
        String knowledge = ragService.retrieve(question);
        String system = systemPrompt;
        if (knowledge != null && !knowledge.isBlank()) {
            system = system + "\n\n【参考知识库】\n" + knowledge;
        }
        List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(ChatMessage.system(system));
        messages.addAll(history);
        messages.add(ChatMessage.user(question));
        return messages;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开：中断流水线由上层 catch 收尾
            throw new IllegalStateException("SSE 推送失败", e);
        }
    }

    /** TTS 前清洗：去 emoji（合成器会朗读乱码） */
    private static String cleanForTts(String text) {
        return text.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]+", "").trim();
    }

    @PreDestroy
    public void shutdown() {
        pipelineExecutor.shutdownNow();
        ttsExecutor.shutdownNow();
    }
}
