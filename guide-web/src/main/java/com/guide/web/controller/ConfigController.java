package com.guide.web.controller;

import com.guide.common.result.Result;
import com.guide.service.knowledge.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游客端公开配置：数字人模型路径、缩放、位置、音量（只读）
 */
@RestController
public class ConfigController {

    private static final Map<String, String> DEFAULTS = Map.of(
            "model_path", "/static/models/hiyori/hiyori_pro_t11.model3.json",
            "scale", "1.0",
            "position_x", "0",
            "position_y", "0",
            "volume", "0.5");

    private final KnowledgeService knowledgeService;

    public ConfigController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping("/api/config")
    public Result<Map<String, Object>> config() {
        Map<String, String> stored = knowledgeService.configMap(List.copyOf(DEFAULTS.keySet()));
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, String> def : DEFAULTS.entrySet()) {
            String value = stored.get(def.getKey());
            if (value == null || value.isBlank()) {
                value = def.getValue();
            }
            if ("scale".equals(def.getKey()) || "volume".equals(def.getKey())
                    || "position_x".equals(def.getKey()) || "position_y".equals(def.getKey())) {
                result.put(def.getKey(), Double.parseDouble(value));
            } else {
                result.put(def.getKey(), value);
            }
        }
        return Result.success(result);
    }
}
