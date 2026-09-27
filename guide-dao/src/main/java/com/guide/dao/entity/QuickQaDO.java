package com.guide.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 快问快答（猜你想问）表：运营预制高频问题与标准答案，
 * 游客提问命中后直接返回，跳过 LLM 调用——零成本、毫秒级响应。
 */
@TableName("quick_qa")
public class QuickQaDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 展示用标准问题（前端猜你想问 chips 文案） */
    private String question;

    /** 预制答案，命中后直接返回 */
    private String answer;

    /** 命中关键词，逗号分隔；为空则按问题全文归一化匹配 */
    private String keywords;

    /** 1 启用，0 禁用 */
    private Integer enabled;

    /** 展示顺序，小的在前 */
    private Integer sortOrder;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
