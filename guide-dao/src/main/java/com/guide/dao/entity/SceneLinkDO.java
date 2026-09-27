package com.guide.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 景点短链映射表（管理后台配置）：
 * 运营为景点配置关键词与长链接（详情页/购票页/路线图），
 * 问答中命中关键词时自动经短链平台生成短链随回答下发，点击量回流短链统计。
 */
@TableName("scene_link")
public class SceneLinkDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sceneName;

    private String keyword;

    private String longUrl;

    /** 经短链平台创建后回填的短码：只创建一次并复用，点击统计才能按景点持续聚合 */
    private String shortCode;

    /** 1 启用，0 禁用 */
    private Integer enabled;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSceneName() {
        return sceneName;
    }

    public void setSceneName(String sceneName) {
        this.sceneName = sceneName;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public void setLongUrl(String longUrl) {
        this.longUrl = longUrl;
    }

    public String getShortCode() {
        return shortCode;
    }

    public void setShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
