-- ============================================================
-- AI 智能导游平台 数据库脚本（MySQL 8）
-- 库：ai_guide（连接串带 createDatabaseIfNotExist=true，无需手工建库）
-- ============================================================

CREATE TABLE IF NOT EXISTS `knowledge` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(255) DEFAULT NULL COMMENT '标题',
    `category`    VARCHAR(64)  DEFAULT '未分类' COMMENT '分类',
    `content`     MEDIUMTEXT   COMMENT '文档全文',
    `file_name`   VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    `upload_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识文档主表';

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `knowledge_id` BIGINT       NOT NULL COMMENT '所属文档',
    `chunk_idx`    INT          NOT NULL DEFAULT 0 COMMENT '块序号',
    `content`      VARCHAR(1024) NOT NULL COMMENT '块内容',
    PRIMARY KEY (`id`),
    KEY `idx_chunk_kid` (`knowledge_id`),
    -- 中文全文索引：ngram 二元组分词，检索侧配合 jieba 关键词提取
    FULLTEXT KEY `ft_chunk_content` (`content`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '知识分块表';

CREATE TABLE IF NOT EXISTS `app_config` (
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(512) DEFAULT NULL COMMENT '配置值',
    PRIMARY KEY (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '应用配置 KV 表';

CREATE TABLE IF NOT EXISTS `interaction` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_question` VARCHAR(2000) NOT NULL COMMENT '游客提问',
    `ai_answer`    MEDIUMTEXT  COMMENT 'AI 回答',
    `emotion`      VARCHAR(16) DEFAULT 'neutral' COMMENT '情感：positive/negative/neutral',
    `links_json`   TEXT        COMMENT '本次回答下发的短链 JSON 数组',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '时间',
    PRIMARY KEY (`id`),
    KEY `idx_interaction_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '交互记录表';

CREATE TABLE IF NOT EXISTS `scene_link` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `scene_name`  VARCHAR(64)  NOT NULL COMMENT '景点名',
    `keyword`     VARCHAR(64)  NOT NULL COMMENT '命中关键词，逗号分隔可配多个',
    `long_url`    VARCHAR(2048) NOT NULL COMMENT '目标长链接（详情页/购票页/路线图）',
    `short_code`  VARCHAR(16)  DEFAULT NULL COMMENT '经短链平台创建后回填的短码（复用，保证统计按景点聚合）',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '景点短链映射表';

CREATE TABLE IF NOT EXISTS `quick_qa` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`    VARCHAR(200) NOT NULL COMMENT '展示用标准问题',
    `answer`      TEXT         NOT NULL COMMENT '预制答案（命中后直接返回，不走 LLM）',
    `keywords`    VARCHAR(200) DEFAULT NULL COMMENT '命中关键词，逗号分隔；为空则按问题全文匹配',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用，0 禁用',
    `sort_order`  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '快问快答（猜你想问）表';

-- 种子数据：INSERT IGNORE 幂等，重启不会重复插入（按主键冲突跳过）
INSERT IGNORE INTO `quick_qa` (`id`, `question`, `answer`, `keywords`, `sort_order`) VALUES
(1, '门票多少钱？', '灵山胜境成人票210元，6-18周岁未成年人及60-69周岁老人可购半价票105元，70周岁以上老人、1.4米以下儿童免票（需成人陪同）。建议提前在官方渠道购票，入园更快捷。', '门票,票价,多少钱,价格,购票', 1),
(2, '景区几点开门？', '灵山胜境开放时间为每日7:30-17:30（17:00停止入园）。《吉祥颂》演出每日10:35、11:30、14:00、16:00四场，建议提前15分钟到场占座。', '开放,几点,时间,关门,营业', 2),
(3, '怎么去灵山胜境？', '自驾：上海方向经沪蓉高速转锡宜高速，无锡西出口下，约2.5小时车程。公共交通：无锡火车站乘88路公交直达景区，或地铁1号线至长广溪站转乘。', '怎么去,交通,自驾,公交,路线,地铁', 3),
(4, '灵山大佛有多高？', '灵山大佛通高88米，其中佛体79米、莲花瓣9米，比自由女神像还高出42米，是中国第二高的巨型佛像。抱佛脚平台可俯瞰太湖全景，值得一去！', '大佛,多高,高度,多高', 4),
(5, '推荐一条游览路线？', '经典半日游：大照壁→九龙灌浴（9:35/11:30/14:45/16:45演出）→阿育王柱→千年古银杏→祥符禅寺→灵山大佛（抱佛脚）→万佛殿。全程约4小时，午餐推荐景区内灵山精舍素面。', '推荐,路线,攻略,怎么玩,游玩', 5);
