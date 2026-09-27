package com.guide.service.interaction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.dao.entity.InteractionDO;
import com.guide.dao.mapper.InteractionMapper;
import com.guide.service.shortlink.ShortLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互记录与数据大屏：
 * - 保存问答（含本次下发的短链 JSON，供漏斗分析「推荐了什么」）
 * - 分页查询、总览统计、情感趋势、热门问题
 * - 漏斗：短链点击统计来自短链平台（推荐→点击环节的转化数据）
 */
@Service
public class InteractionService {

    private static final Logger log = LoggerFactory.getLogger(InteractionService.class);

    private final InteractionMapper interactionMapper;
    private final ShortLinkService shortLinkService;
    private final ObjectMapper mapper;

    public InteractionService(InteractionMapper interactionMapper,
                              ShortLinkService shortLinkService,
                              ObjectMapper mapper) {
        this.interactionMapper = interactionMapper;
        this.shortLinkService = shortLinkService;
        this.mapper = mapper;
    }

    public void save(String question, String answer, String emotion, List<ShortLinkService.LinkItem> links) {
        InteractionDO row = new InteractionDO();
        row.setUserQuestion(question);
        row.setAiAnswer(answer);
        row.setEmotion(emotion);
        row.setLinksJson(toJson(links));
        row.setCreateTime(LocalDateTime.now());
        interactionMapper.insert(row);
    }

    public Map<String, Object> page(long current, long size) {
        Page<InteractionDO> page = interactionMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<InteractionDO>().orderByDesc(InteractionDO::getCreateTime));
        Map<String, Object> result = new HashMap<>();
        result.put("items", page.getRecords());
        result.put("total", page.getTotal());
        result.put("page", current);
        result.put("per_page", size);
        return result;
    }

    /** 数据大屏：问答量 + 情感趋势 + 热门问题 + 短链点击漏斗 */
    public Map<String, Object> dashboard() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        Map<String, Object> result = new HashMap<>();
        result.put("total", interactionMapper.selectCount(null));
        result.put("today", interactionMapper.countToday());
        result.put("week", interactionMapper.countSince(weekAgo));
        result.put("trend", interactionMapper.emotionTrend(weekAgo));
        result.put("hot", interactionMapper.hotQuestions());
        // 漏斗「推荐→点击」环节：短链平台各景点 PV/UV/今日点击
        result.put("sceneStats", shortLinkService.sceneStats());
        return result;
    }

    private String toJson(List<ShortLinkService.LinkItem> links) {
        if (links == null || links.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(links);
        } catch (JsonProcessingException e) {
            log.warn("短链列表序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
