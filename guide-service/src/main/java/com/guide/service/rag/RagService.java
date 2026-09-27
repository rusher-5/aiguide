package com.guide.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.dao.entity.KnowledgeChunkDO;
import com.guide.dao.mapper.KnowledgeChunkMapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索：jieba 分词提取问句关键词 → MySQL FULLTEXT ngram 自然语言模式（相关性排序）→ Top-K 块。
 * 降级路径：FULLTEXT 不可用时多关键词 LIKE 内存评分，保证功能降级而非崩溃。
 *
 * 关键词提取说明：jieba-analysis 1.0.2（Maven Central 最新发布版）未收录 TF-IDF 分析器，
 * 故基于 SEARCH 模式分词 + 停用词过滤 + 词频排序自实现。
 * 语料规模仅数百块时，词频排序与 TF-IDF 权重的检索效果等价（IDF 项在小区间内近似常数）。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 单例分词器：内部词典预加载，避免首个请求承受初始化耗时 */
    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    /** 停用词：问句高频虚词，不具检索区分度 */
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "请问", "一下", "什么", "怎么", "怎么样", "哪里", "哪个", "多少", "有没有", "是不是",
            "可以", "应该", "需要", "告诉", "介绍", "我想", "我要", "我们", "你们",
            "的", "了", "是", "在", "有", "和", "与", "就", "都", "很", "也", "还", "又",
            "我", "你", "他", "她", "它", "吗", "呢", "吧", "啊", "呀", "嗯", "哦",
            "去", "到", "要", "会", "能", "被", "把", "给", "对", "从", "向", "上", "下", "中",
            "不", "没", "最", "比较", "这个", "那个", "这些", "那些", "还是");

    private final KnowledgeChunkMapper chunkMapper;

    @Value("${guide.rag.chunk-size:300}")
    private int chunkSize;

    @Value("${guide.rag.chunk-overlap:60}")
    private int chunkOverlap;

    @Value("${guide.rag.retrieve-top-k:3}")
    private int retrieveTopK;

    public RagService(KnowledgeChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /** 预热 jieba 词典（启动时调用，把分词器初始化耗时挪出首个请求） */
    public void warmUp() {
        SEGMENTER.process("景区门票价格交通路线历史", JiebaSegmenter.SegMode.SEARCH);
    }

    /**
     * 检索知识库，返回拼接后的参考文本；检索不到返回空串。
     */
    public String retrieve(String query) {
        List<String> keywords = extractKeywords(query, 8);
        if (keywords.isEmpty()) {
            return "";
        }
        List<String> contents;
        try {
            // 自然语言模式：多关键词 OR、按相关性降序返回，语义对齐 Python 版 FTS5 BM25 方案
            String ftsQuery = String.join(" ", keywords);
            List<KnowledgeChunkDO> hits = chunkMapper.searchFulltext(ftsQuery, retrieveTopK);
            contents = hits.stream().map(KnowledgeChunkDO::getContent).toList();
        } catch (Exception e) {
            log.warn("FULLTEXT 检索异常，走 LIKE 降级路径: {}", e.getMessage());
            contents = likeFallback(keywords);
        }
        return String.join("\n---\n", contents);
    }

    /**
     * 文档入库/删除时重建该文档的分块与全文索引（删旧插新，幂等）。
     */
    public void indexDocumentChunks(long knowledgeId, String content) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getKnowledgeId, knowledgeId));
        List<String> chunks = Chunker.chunk(content, chunkSize, chunkOverlap);
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkDO c = new KnowledgeChunkDO();
            c.setKnowledgeId(knowledgeId);
            c.setChunkIdx(i);
            c.setContent(chunks.get(i));
            chunkMapper.insert(c);
        }
        log.info("知识文档 {} 重建分块索引完成，共 {} 块", knowledgeId, chunks.size());
    }

    public void removeDocumentChunks(long knowledgeId) {
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getKnowledgeId, knowledgeId));
    }

    /** 查询侧关键词：jieba SEARCH 分词 → 停用词过滤 → 词频排序 Top-N */
    private List<String> extractKeywords(String query, int topN) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<SegToken> tokens = SEGMENTER.process(query, JiebaSegmenter.SegMode.SEARCH);
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (SegToken token : tokens) {
            String word = token.word.trim();
            if (word.length() < 2 || STOP_WORDS.contains(word)) {
                continue;
            }
            freq.merge(word, 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 降级：全量块加载后按关键词命中数排序（语料规模小，可接受） */
    private List<String> likeFallback(List<String> keywords) {
        List<KnowledgeChunkDO> all = chunkMapper.selectList(null);
        record Scored(int hits, String content) {
        }
        return all.stream()
                .map(c -> {
                    int hits = 0;
                    for (String kw : keywords) {
                        if (c.getContent().contains(kw)) {
                            hits++;
                        }
                    }
                    return new Scored(hits, c.getContent());
                })
                .filter(s -> s.hits() > 0)
                .sorted((a, b) -> Integer.compare(b.hits(), a.hits()))
                .limit(retrieveTopK)
                .map(Scored::content)
                .toList();
    }
}
