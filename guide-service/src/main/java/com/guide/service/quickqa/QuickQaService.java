package com.guide.service.quickqa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.dao.entity.QuickQaDO;
import com.guide.dao.mapper.QuickQaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快问快答（猜你想问）服务：
 * - 游客端：启用列表（chips 展示）+ 问题匹配（命中返回预制答案，跳过 LLM）
 * - 匹配策略：问题归一化后 ① 与标准问题精确相等 ② 命任一关键词。
 *   归一化 = 去空白 + 去标点 + 小写，保证「门票多少钱？」与「门票多少钱」等价。
 * - TTS 音频按 id 进程内缓存：同一条快问快答只合成一次，重复问答毫秒级返回。
 */
@Service
public class QuickQaService {

    private static final Logger log = LoggerFactory.getLogger(QuickQaService.class);

    private final QuickQaMapper quickQaMapper;

    /** TTS 音频缓存：qaId -> mp3 字节。管理端修改/删除时逐出。 */
    private final Map<Long, byte[]> audioCache = new ConcurrentHashMap<>();

    public QuickQaService(QuickQaMapper quickQaMapper) {
        this.quickQaMapper = quickQaMapper;
    }

    /** 游客端：启用中的快问快答，按 sortOrder 排序（不含答案全文，chips 只需问题） */
    public List<QuickQaDO> listEnabled() {
        return quickQaMapper.selectList(new LambdaQueryWrapper<QuickQaDO>()
                .eq(QuickQaDO::getEnabled, 1)
                .orderByAsc(QuickQaDO::getSortOrder)
                .orderByAsc(QuickQaDO::getId));
    }

    /** 管理端：全量列表 */
    public List<QuickQaDO> listAll() {
        return quickQaMapper.selectList(new LambdaQueryWrapper<QuickQaDO>()
                .orderByAsc(QuickQaDO::getSortOrder)
                .orderByAsc(QuickQaDO::getId));
    }

    /**
     * 匹配游客提问：命中返回预制条目，未命中返回 null（走 LLM 正常链路）。
     * 高频问题集中在几条，线性扫描足够；数据量大时可换 Trie 或全文索引。
     */
    public QuickQaDO match(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalized = normalize(question);
        if (normalized.isEmpty()) {
            return null;
        }
        for (QuickQaDO qa : listEnabled()) {
            if (normalized.equals(normalize(qa.getQuestion()))) {
                return qa;
            }
            if (hitKeywords(normalized, qa.getKeywords())) {
                return qa;
            }
        }
        return null;
    }

    /** 获取（必要时合成并缓存）该条目的整段音频 */
    public byte[] audioOf(QuickQaDO qa, java.util.function.Function<String, byte[]> synthesizer) {
        return audioCache.computeIfAbsent(qa.getId(), k -> {
            byte[] audio = synthesizer.apply(qa.getAnswer());
            if (audio == null) {
                // 合成失败不缓存 null：computeIfAbsent 不允许 null 返回值，这里手动移除
                audioCache.remove(k);
                return new byte[0];
            }
            log.debug("快问快答[{}]音频已缓存，{} 字节", qa.getId(), audio.length);
            return audio;
        });
    }

    /** 管理端变更后逐出音频缓存，下次命中重新合成 */
    public void evictAudio(Long id) {
        audioCache.remove(id);
    }

    /** 归一化：仅保留汉字与字母数字（等效去全部空白/标点/符号）、转小写，让口语化变体命中同一条目 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^\\p{IsHan}\\p{Alnum}]", "").toLowerCase();
    }

    private static boolean hitKeywords(String normalizedQuestion, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return false;
        }
        for (String kw : keywords.split("[,，]")) {
            String k = normalize(kw);
            if (!k.isEmpty() && normalizedQuestion.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
