package com.guide.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.dao.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {

    /**
     * FULLTEXT ngram 自然语言模式检索：多关键词 OR 语义、按相关性排序取 Top-K。
     * ngram 将中文切成二元组，规避 InnoDB 默认分词器 min_token_size=3 对 1~2 字中文词的丢弃。
     */
    @Select("SELECT id, knowledge_id, chunk_idx, content FROM knowledge_chunk " +
            "WHERE MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) " +
            "LIMIT #{topK}")
    List<KnowledgeChunkDO> searchFulltext(@Param("query") String query, @Param("topK") int topK);
}
