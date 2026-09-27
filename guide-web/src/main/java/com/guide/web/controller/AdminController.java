package com.guide.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.common.result.Result;
import com.guide.service.knowledge.KnowledgeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 管理后台：知识库管理 + 应用配置。
 * 均在 /api/admin/** 下，经 AdminAuthInterceptor 鉴权（Basic / 共享 JWT 双通道）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final List<String> CONFIG_KEYS =
            List.of("model_path", "scale", "position_x", "position_y", "volume");

    private final KnowledgeService knowledgeService;

    public AdminController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    // ============================================================
    // 知识库
    // ============================================================

    @PostMapping("/knowledge/upload")
    public Result<Map<String, Object>> uploadKnowledge(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "category", required = false) String category) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "未选择文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".txt")) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID);
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
        long id = knowledgeService.upload(name, category, content);
        return Result.success(Map.of("id", id));
    }

    @GetMapping("/knowledge/list")
    public Result<List<?>> listKnowledge() {
        return Result.success(knowledgeService.list());
    }

    @DeleteMapping("/knowledge/{id}")
    public Result<Void> deleteKnowledge(@PathVariable("id") long id) {
        knowledgeService.delete(id);
        return Result.success();
    }

    // ============================================================
    // 应用配置（数字人模型路径、缩放、位置、音量）
    // ============================================================

    @PostMapping("/config")
    public Result<Void> updateConfig(@RequestBody JsonNode body) {
        body.fields().forEachRemaining(entry -> {
            if (CONFIG_KEYS.contains(entry.getKey())) {
                knowledgeService.putConfig(entry.getKey(), entry.getValue().asText());
            }
        });
        return Result.success();
    }
}
