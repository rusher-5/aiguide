package com.guide.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 应用配置 KV 表：数字人模型路径、缩放、位置、音量等运行时配置
 */
@TableName("app_config")
public class AppConfigDO {

    @TableId
    private String configKey;

    private String configValue;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }
}
