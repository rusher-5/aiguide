package com.guide.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识分块表：一份文档按句界滑窗切成多个块，检索以块为单位。
 * content 列建 FULLTEXT ngram 索引，供 BM25 风格的相关性排序检索。
 */
@TableName("knowledge_chunk")
public class KnowledgeChunkDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeId;

    private Integer chunkIdx;

    private String content;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(Long knowledgeId) {
        this.knowledgeId = knowledgeId;
    }

    public Integer getChunkIdx() {
        return chunkIdx;
    }

    public void setChunkIdx(Integer chunkIdx) {
        this.chunkIdx = chunkIdx;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
