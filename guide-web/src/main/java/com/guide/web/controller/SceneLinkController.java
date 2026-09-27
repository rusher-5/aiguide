package com.guide.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.common.result.Result;
import com.guide.dao.entity.SceneLinkDO;
import com.guide.dao.mapper.SceneLinkMapper;
import com.guide.service.shortlink.ShortLinkService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理后台：景点短链映射 CRUD。
 * 修改长链接时清空已持久化的短码——下次命中重建短链，避免旧短码指向过期地址。
 */
@RestController
@RequestMapping("/api/admin/scene")
public class SceneLinkController {

    private final SceneLinkMapper sceneLinkMapper;
    private final ShortLinkService shortLinkService;

    public SceneLinkController(SceneLinkMapper sceneLinkMapper, ShortLinkService shortLinkService) {
        this.sceneLinkMapper = sceneLinkMapper;
        this.shortLinkService = shortLinkService;
    }

    @GetMapping("/list")
    public Result<List<SceneLinkDO>> list() {
        return Result.success(sceneLinkMapper.selectList(
                new LambdaQueryWrapper<SceneLinkDO>().orderByAsc(SceneLinkDO::getId)));
    }

    @PostMapping
    public Result<Void> create(@RequestBody SceneLinkDO scene) {
        validate(scene);
        scene.setId(null);
        scene.setShortCode(null);
        scene.setEnabled(1);
        scene.setCreateTime(LocalDateTime.now());
        sceneLinkMapper.insert(scene);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") long id, @RequestBody SceneLinkDO scene) {
        SceneLinkDO exist = sceneLinkMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "景点映射不存在");
        }
        validate(scene);
        exist.setSceneName(scene.getSceneName());
        exist.setKeyword(scene.getKeyword());
        exist.setEnabled(scene.getEnabled() == null ? 1 : scene.getEnabled());
        // 长链接变更 → 复位短码（下次命中时经短链平台重建）
        if (scene.getLongUrl() != null && !scene.getLongUrl().equals(exist.getLongUrl())) {
            exist.setLongUrl(scene.getLongUrl());
            exist.setShortCode(null);
        }
        sceneLinkMapper.updateById(exist);
        return Result.success();
    }

    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable("id") long id) {
        SceneLinkDO exist = sceneLinkMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "景点映射不存在");
        }
        exist.setEnabled(exist.getEnabled() != null && exist.getEnabled() == 1 ? 0 : 1);
        sceneLinkMapper.updateById(exist);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id) {
        sceneLinkMapper.deleteById(id);
        return Result.success();
    }

    private void validate(SceneLinkDO scene) {
        if (scene.getSceneName() == null || scene.getSceneName().isBlank()
                || scene.getKeyword() == null || scene.getKeyword().isBlank()
                || scene.getLongUrl() == null || scene.getLongUrl().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "景点名、关键词、长链接均不能为空");
        }
        try {
            URI.create(scene.getLongUrl()).toURL();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "长链接不合法");
        }
    }
}
