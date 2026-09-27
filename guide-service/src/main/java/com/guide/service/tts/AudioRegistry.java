package com.guide.service.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 内存音频注册表：TTS 产出的 mp3 以 UUID 为键在内存中登记，前端经 /api/audio/{id} 拉取。
 *
 * 相比 Python 版「临时文件 + 定时清扫」方案的改进：
 * - 键为服务端生成的 UUID，从根上消除路径穿越面（无需 secure_filename/commonpath 双保险）
 * - 无磁盘 IO 与磁盘慢性泄漏风险，仅以 TTL 控制内存占用（单句约 18KB，上限可控）
 */
@Component
public class AudioRegistry {

    private static final Logger log = LoggerFactory.getLogger(AudioRegistry.class);

    /** 合法音频 id：36 位 UUID（小写十六进制 + 连字符），服务端生成、白名单校验 */
    private static final Pattern VALID_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private record Entry(byte[] data, long createdAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Value("${guide.tts.audio-ttl-seconds:1800}")
    private long ttlSeconds;

    /** 登记音频，返回访问 id */
    public String put(byte[] audio) {
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(audio, System.currentTimeMillis()));
        return id;
    }

    /** 取音频；id 非法或不存在返回 null */
    public byte[] get(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            return null;
        }
        Entry entry = store.get(id);
        return entry != null ? entry.data() : null;
    }

    /** 每 5 分钟清扫过期条目，杜绝内存慢性泄漏 */
    @Scheduled(fixedRate = 300_000)
    public void sweep() {
        long deadline = System.currentTimeMillis() - ttlSeconds * 1000;
        int before = store.size();
        store.values().removeIf(e -> e.createdAt() < deadline);
        int removed = before - store.size();
        if (removed > 0) {
            log.info("音频注册表清扫：过期移除 {} 条，剩余 {} 条", removed, store.size());
        }
    }

    public int size() {
        return store.size();
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }
}
