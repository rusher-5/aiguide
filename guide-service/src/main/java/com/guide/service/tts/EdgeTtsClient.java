package com.guide.service.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Edge TTS Java 客户端：零依赖复刻 Python edge-tts 协议（JDK HttpClient WebSocket）。
 *
 * 协议要点：
 * 1. WSS 端点带 TrustedClientToken + Sec-MS-GEC 令牌（微软 DRM 校验）：
 *    令牌 = SHA256(五分钟对齐的 Windows FILETIME ticks + TrustedClientToken) 大写十六进制。
 *    主端点 403 时降级备用端点（与 edge-tts 官方库行为一致）。
 * 2. 建连后先发 speech.config（指定 mp3 输出格式），再发 SSML 合成请求。
 * 3. 服务端二进制帧格式：2 字节大端头长度 + 头文本 + 音频数据；文本帧 Path:turn.end 表示结束。
 *
 * 该类为每次合成建立独立连接（与 edge-tts 行为一致），线程安全。
 */
@Service
public class EdgeTtsClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeTtsClient.class);

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    /** 与官方 edge-tts 同步的 Chromium 版本号：微软会拒绝过旧的 GEC 版本（实测 403） */
    private static final String SEC_MS_GEC_VERSION = "1-143.0.3650.75";

    /** 主端点与备用端点：主端点 403/握手失败时依次降级 */
    private static final String PRIMARY_ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String FALLBACK_ENDPOINT =
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1";

    /** 握手头：复刻 edge-tts 官方库（Origin 必须是 Edge 扩展协议头，缺失会被 403 拒绝） */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final String WS_ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /** edge-tts 约定的时间戳格式 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .withZone(ZoneOffset.UTC);

    private static final long WINDOWS_EPOCH_DIFF = 11644473600L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String voice;
    private final String rate;

    public EdgeTtsClient(@Value("${guide.tts.voice:zh-CN-XiaoxiaoNeural}") String voice,
                         @Value("${guide.tts.rate:+0%}") String rate) {
        this.voice = voice;
        this.rate = rate;
    }

    /**
     * 合成文本为 mp3 字节；所有端点均失败时抛出异常（由调用方降级为纯文本体验）。
     */
    public byte[] synthesize(String text) {
        String gec = generateSecMsGec();
        Exception lastError = null;
        for (String endpoint : new String[]{PRIMARY_ENDPOINT, FALLBACK_ENDPOINT}) {
            try {
                return doSynthesize(endpoint, text, gec);
            } catch (Exception e) {
                lastError = e;
                log.warn("Edge TTS 端点合成失败（{}）: {}", endpoint, e.getMessage());
            }
        }
        throw new IllegalStateException("Edge TTS 所有端点均失败", lastError);
    }

    // ============================================================
    // Sec-MS-GEC 令牌：微软 2024 起强制的前置校验
    // ============================================================

    /**
     * ticks = floor(unix秒 + Windows纪元偏移, 300s) * 10^7（100ns 单位）
     * token = SHA256(ticks + TRUSTED_CLIENT_TOKEN) 大写十六进制
     */
    static String generateSecMsGec() {
        long unix = Instant.now().getEpochSecond() + WINDOWS_EPOCH_DIFF;
        long ticks = (unix / 300) * 300 * 10_000_000L;
        String strToHash = ticks + TRUSTED_CLIENT_TOKEN;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(strToHash.getBytes(StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toUpperCase(Locale.US);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ============================================================
    // WebSocket 合成流程
    // ============================================================

    private byte[] doSynthesize(String endpoint, String text, String gec) throws Exception {
        String url = endpoint
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + UUID.randomUUID().toString().replace("-", "")
                + "&Sec-MS-GEC=" + gec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        TtsListener listener = new TtsListener();
        CompletableFuture<WebSocket> wsFuture = http.newWebSocketBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Origin", WS_ORIGIN)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "") + ";")
                .buildAsync(URI.create(url), listener);

        // 握手失败（403 等）在此抛出，由外层降级下一个端点
        WebSocket ws = wsFuture.get(10, TimeUnit.SECONDS);
        try {
            listener.awaitOpen(5, TimeUnit.SECONDS);

            ws.sendText(buildSpeechConfig(), true).join();
            ws.sendText(buildSsml(text), true).join();

            // 单句合成超时上限：edge-tts 单连接地板延迟约 2s，留足余量
            byte[] audio = listener.awaitAudio(30, TimeUnit.SECONDS);
            if (audio.length == 0) {
                throw new IOException("未收到任何音频数据");
            }
            return audio;
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "ok");
        }
    }

    private String buildSpeechConfig() {
        String payload = "{\"context\":{\"synthesis\":{\"audio\":{"
                + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
        return "X-Timestamp:" + dateHeader()
                + "\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n"
                + payload;
    }

    private String buildSsml(String text) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                + "<voice name='" + voice + "'>"
                + "<prosody rate='" + rate + "' pitch='+0Hz'>"
                + escapeXml(text)
                + "</prosody></voice></speak>";
        return "X-RequestId:" + requestId
                + "\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" + dateHeader() + "Z"
                + "\r\nPath:ssml\r\n\r\n" + ssml;
    }

    private static String dateHeader() {
        return DATE_FORMAT.format(LocalDateTime.now(ZoneOffset.UTC).atZone(ZoneOffset.UTC));
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * WebSocket 监听器：收集二进制音频帧，收到 Path:turn.end 文本帧时完成。
     * 注意 JDK WebSocket 可能分片投递（last=false），必须按消息累积。
     */
    private static final class TtsListener implements WebSocket.Listener {

        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private final CompletableFuture<byte[]> audio = new CompletableFuture<>();

        private final StringBuilder textBuf = new StringBuilder();
        /** 当前二进制消息的分片累积缓冲 */
        private ByteArrayOutputStream messageBuf;
        /** 跨多个二进制消息累积的纯音频数据 */
        private final ByteArrayOutputStream audioBuf = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.complete(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String message = textBuf.toString();
                textBuf.setLength(0);
                if (message.contains("Path:turn.end")) {
                    audio.complete(audioBuf.toByteArray());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (messageBuf == null) {
                messageBuf = new ByteArrayOutputStream();
            }
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            messageBuf.writeBytes(chunk);
            if (last) {
                // 每个二进制消息格式：2 字节大端「头长度」+ 头文本 + 音频数据。
                // 音频跨多个消息到达，必须按消息剥离头部后仅累积纯音频。
                byte[] message = messageBuf.toByteArray();
                messageBuf = null;
                if (message.length > 2) {
                    int headerLen = ((message[0] & 0xFF) << 8) | (message[1] & 0xFF);
                    int audioStart = Math.min(2 + headerLen, message.length);
                    audioBuf.write(message, audioStart, message.length - audioStart);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!audio.isDone()) {
                audio.completeExceptionally(new IOException("连接提前关闭: " + statusCode + " " + reason));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            opened.completeExceptionally(error);
            audio.completeExceptionally(error);
        }

        void awaitOpen(long timeout, TimeUnit unit) throws Exception {
            opened.get(timeout, unit);
        }

        byte[] awaitAudio(long timeout, TimeUnit unit) throws Exception {
            return audio.get(timeout, unit);
        }
    }
}
