package com.guide.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.dao.entity.KnowledgeDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeMapper extends BaseMapper<KnowledgeDO> {
}
