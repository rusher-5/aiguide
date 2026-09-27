package com.guide.service.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.dao.entity.AppConfigDO;
import com.guide.dao.entity.KnowledgeDO;
import com.guide.dao.mapper.AppConfigMapper;
import com.guide.dao.mapper.KnowledgeMapper;
import com.guide.service.rag.RagService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理：上传（txt ≤ 2MB）、列表、删除；上传/删除即重建分块与全文索引。
 * 另管应用配置 KV（数字人模型路径、缩放、位置、音量）。
 */
@Service
public class KnowledgeService {

    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private final KnowledgeMapper knowledgeMapper;
    private final AppConfigMapper appConfigMapper;
    private final RagService ragService;

    public KnowledgeService(KnowledgeMapper knowledgeMapper,
                            AppConfigMapper appConfigMapper,
                            RagService ragService) {
        this.knowledgeMapper = knowledgeMapper;
        this.appConfigMapper = appConfigMapper;
        this.ragService = ragService;
    }

    @Transactional
    public long upload(String fileName, String category, String content) {
        if (content == null || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        KnowledgeDO doc = new KnowledgeDO();
        doc.setTitle(fileName);
        doc.setCategory(category == null || category.isBlank() ? "未分类" : category);
        doc.setContent(content);
        doc.setFileName(fileName);
        knowledgeMapper.insert(doc);
        // 上传即建分块索引
        ragService.indexDocumentChunks(doc.getId(), content);
        return doc.getId();
    }

    public List<KnowledgeDO> list() {
        // 列表不带全文，减少传输
        return knowledgeMapper.selectList(new LambdaQueryWrapper<KnowledgeDO>()
                .select(KnowledgeDO::getId, KnowledgeDO::getTitle, KnowledgeDO::getCategory,
                        KnowledgeDO::getFileName, KnowledgeDO::getUploadTime)
                .orderByDesc(KnowledgeDO::getUploadTime));
    }

    @Transactional
    public void delete(long id) {
        KnowledgeDO doc = knowledgeMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_NOT_FOUND);
        }
        knowledgeMapper.deleteById(id);
        ragService.removeDocumentChunks(id);
    }

    // ============================================================
    // 应用配置 KV
    // ============================================================

    public Map<String, String> configMap(List<String> keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) {
            AppConfigDO doc = appConfigMapper.selectById(key);
            result.put(key, doc != null ? doc.getConfigValue() : null);
        }
        return result;
    }

    public void putConfig(String key, String value) {
        AppConfigDO doc = new AppConfigDO();
        doc.setConfigKey(key);
        doc.setConfigValue(value);
        // MyBatis-Plus 无原生 upsert：先查后插/更
        AppConfigDO exist = appConfigMapper.selectById(key);
        if (exist == null) {
            appConfigMapper.insert(doc);
        } else {
            appConfigMapper.updateById(doc);
        }
    }
}
