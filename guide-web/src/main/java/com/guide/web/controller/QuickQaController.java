package com.guide.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.common.exception.BizException;
import com.guide.common.result.ErrorCode;
import com.guide.common.result.Result;
import com.guide.dao.entity.QuickQaDO;
import com.guide.dao.mapper.QuickQaMapper;
import com.guide.service.quickqa.QuickQaService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台：快问快答（猜你想问）CRUD。
 * 变更（改/删/启停）后逐出 TTS 音频缓存，下次命中重新合成，避免旧语音残留。
 */
@RestController
@RequestMapping("/api/admin/quick-qa")
public class QuickQaController {

    private final QuickQaMapper quickQaMapper;
    private final QuickQaService quickQaService;

    public QuickQaController(QuickQaMapper quickQaMapper, QuickQaService quickQaService) {
        this.quickQaMapper = quickQaMapper;
        this.quickQaService = quickQaService;
    }

    @GetMapping("/list")
    public Result<List<QuickQaDO>> list() {
        return Result.success(quickQaMapper.selectList(new LambdaQueryWrapper<QuickQaDO>()
                .orderByAsc(QuickQaDO::getSortOrder)
                .orderByAsc(QuickQaDO::getId)));
    }

    @PostMapping
    public Result<Void> create(@RequestBody QuickQaDO qa) {
        validate(qa);
        qa.setId(null);
        qa.setEnabled(1);
        qa.setCreateTime(LocalDateTime.now());
        quickQaMapper.insert(qa);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") long id, @RequestBody QuickQaDO qa) {
        QuickQaDO exist = quickQaMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "快问快答不存在");
        }
        validate(qa);
        exist.setQuestion(qa.getQuestion());
        exist.setAnswer(qa.getAnswer());
        exist.setKeywords(qa.getKeywords());
        if (qa.getSortOrder() != null) {
            exist.setSortOrder(qa.getSortOrder());
        }
        quickQaMapper.updateById(exist);
        quickQaService.evictAudio(id);
        return Result.success();
    }

    @PutMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable("id") long id) {
        QuickQaDO exist = quickQaMapper.selectById(id);
        if (exist == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "快问快答不存在");
        }
        exist.setEnabled(exist.getEnabled() != null && exist.getEnabled() == 1 ? 0 : 1);
        quickQaMapper.updateById(exist);
        quickQaService.evictAudio(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id) {
        quickQaMapper.deleteById(id);
        quickQaService.evictAudio(id);
        return Result.success();
    }

    private void validate(QuickQaDO qa) {
        if (qa.getQuestion() == null || qa.getQuestion().isBlank()
                || qa.getAnswer() == null || qa.getAnswer().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "问题与答案均不能为空");
        }
    }
}
