package com.guide.web.controller;

import com.guide.common.result.Result;
import com.guide.service.interaction.InteractionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台：交互记录与数据大屏（含短链点击漏斗）
 */
@RestController
@RequestMapping("/api/admin")
public class InteractionController {

    private final InteractionService interactionService;

    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @GetMapping("/interactions")
    public Result<Map<String, Object>> interactions(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size) {
        return Result.success(interactionService.page(page, size));
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        return Result.success(interactionService.dashboard());
    }
}
