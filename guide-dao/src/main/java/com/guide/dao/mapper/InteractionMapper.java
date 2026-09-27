package com.guide.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.dao.entity.InteractionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface InteractionMapper extends BaseMapper<InteractionDO> {

    @Select("SELECT COUNT(*) FROM interaction WHERE DATE(create_time) = CURDATE()")
    long countToday();

    @Select("SELECT COUNT(*) FROM interaction WHERE create_time >= #{since}")
    long countSince(@Param("since") LocalDateTime since);

    /** 近 7 日情感趋势：按天聚合正/负/中性提问数 */
    @Select("SELECT DATE(create_time) AS day, " +
            "SUM(CASE WHEN emotion = 'positive' THEN 1 ELSE 0 END) AS positive, " +
            "SUM(CASE WHEN emotion = 'negative' THEN 1 ELSE 0 END) AS negative, " +
            "SUM(CASE WHEN emotion = 'neutral' THEN 1 ELSE 0 END) AS neutral " +
            "FROM interaction WHERE create_time >= #{since} " +
            "GROUP BY DATE(create_time) ORDER BY day")
    List<Map<String, Object>> emotionTrend(@Param("since") LocalDateTime since);

    /** 热门问题 Top5 */
    @Select("SELECT user_question AS question, COUNT(*) AS cnt FROM interaction " +
            "GROUP BY user_question ORDER BY cnt DESC LIMIT 5")
    List<Map<String, Object>> hotQuestions();
}
