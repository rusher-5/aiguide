package com.guide.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 交互记录表：一次问答一条。links_json 记录本次回答中下发的短链，
 * 与短链平台的 access_log 打通后可还原「提问→推荐→点击转化」漏斗。
 */
@TableName("interaction")
public class InteractionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userQuestion;

    private String aiAnswer;

    /** positive / negative / neutral —— 口径为用户问题（游客满意度），非 AI 回答文风 */
    private String emotion;

    /** 本次回答附带的结构化短链 JSON：[{"scene":"...","shortUrl":"...","longUrl":"..."}] */
    private String linksJson;

    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserQuestion() {
        return userQuestion;
    }

    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
    }

    public String getAiAnswer() {
        return aiAnswer;
    }

    public void setAiAnswer(String aiAnswer) {
        this.aiAnswer = aiAnswer;
    }

    public String getEmotion() {
        return emotion;
    }

    public void setEmotion(String emotion) {
        this.emotion = emotion;
    }

    public String getLinksJson() {
        return linksJson;
    }

    public void setLinksJson(String linksJson) {
        this.linksJson = linksJson;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
