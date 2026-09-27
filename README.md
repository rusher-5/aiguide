# AI 智能导游数字人平台（Java 版）

为景区打造的 AI 数字人导游：游客向 Live2D 数字人提问，系统基于景区知识库（RAG）流式生成回答并逐句转语音播报，回答中的景点入口自动经[短链平台](../urllink)（`D:\urllink`）生成短链下发，点击数据回流形成「提问→推荐→点击」转化漏斗。

Python 版（Flask + edge-tts + SQLite FTS5）的 Java 重写，工程风格对齐短链平台（Spring Boot 3.4 多模块 Maven）。

## 功能特性

- [x] RAG 知识检索：句界滑窗分块（300/60）+ jieba 分词 + MySQL FULLTEXT ngram 相关性排序 Top-K
- [x] 流式语音链路：LLM token 流 → 按句切分 → 2 worker 并行 TTS → SSE 按序推送（seq）
- [x] Edge TTS Java 客户端：零依赖复刻 edge-tts 协议（JDK WebSocket + Sec-MS-GEC 令牌 + 双端点降级）
- [x] 短链平台深集成：景点短链映射、短码持久化复用、创建失败降级长链、点击漏斗统计
- [x] 统一用户体系：与短链平台共享 JWT 密钥，其签发 token 可直接管理本平台
- [x] 管理后台：知识库上传/删除、数字人形象配置、交互记录、数据大屏（含短链漏斗）
- [x] 安全设计：历史净化防提示词注入、音频 UUID 注册表（无路径穿越面）、管理端 Basic/JWT 双通道鉴权
- [x] 音频内存注册表 + TTL 定时清扫（无磁盘临时文件、无慢性泄漏）

## 技术栈

| 分类 | 技术 |
|---|---|
| 语言 | Java 17（JDK 25 可编译运行） |
| 框架 | Spring Boot 3.4、Spring MVC（SseEmitter） |
| ORM | MyBatis-Plus 3.5 |
| 数据库 | MySQL 8（FULLTEXT ngram 中文全文索引） |
| LLM | DeepSeek（OpenAI 兼容协议，JDK HttpClient 流式 SSE） |
| TTS | Edge TTS 免费接口（Java WebSocket 客户端自实现） |
| 分词 | jieba-analysis 1.0.2（关键词提取自实现：分词+停用词+词频） |
| 认证 | JWT（与短链平台共享密钥）+ Basic Auth |
| 前端 | Live2D（pixi-live2d-display）、原生 JS |

## 系统架构

```text
                        +----------------+
                        |  游客端浏览器    |  管理后台浏览器
                        +---+--------+---+
                            |        |
                            v        v
              +-------------+--------+-------------+
              |      ai-guide (Spring Boot:5001)   |
              |  SSE流式问答 / RAG / TTS / 管理API   |
              +------+-----------------+------------+
                     |                 |
                     v                 v
              +------+------+   +------+------+
              | MySQL 8     |   | urllink平台  |
              | ai_guide 库 |   |  (:8080)     |
              | FULLTEXT    |   | 短链创建/统计 |
              +-------------+   +------+------+
                                       |
                                 (JWT 共享密钥)
```

## 快速开始

### 环境要求

- JDK 17+（仓库带 Maven Wrapper，无需全局 Maven）
- MySQL 8（本机 3306，账号 root/123456，与短链平台共库实例；库名 `ai_guide` 自动创建）
- 短链平台本地运行（`D:\urllink`，端口 8080）——短链功能需要，不可用时自动降级长链
- 环境变量 `DEEPSEEK_API_KEY`（LLM 必需）

### 启动

```powershell
# 1. 短链平台侧注册服务账号（一次性）
curl -X POST http://localhost:8080/api/v1/user/register -H "Content-Type: application/json" -d '{"username":"guide_bot","password":"guide_bot_123"}'

# 2. 构建
.\mvnw.cmd clean package -DskipTests

# 3. 启动（Windows PowerShell）
$env:DEEPSEEK_API_KEY="sk-xxxx"
java -jar guide-web\target\guide-web-1.0.0.jar
```

- 游客端：http://localhost:5001
- 管理后台：http://localhost:5001/admin（admin / admin123，或携带短链平台 JWT）
- 健康检查：http://localhost:5001/actuator/health

### 使用流程

1. 管理后台「知识库管理」上传景区文档（txt，≤2MB）→ 自动分块建索引
2. 「景点短链」配置景点名/关键词/长链接
3. 游客端提问 → 流式回答 + 语音播报 → 命中景点时回答下方附带短链卡片
4. 「数据大屏」查看问答统计 + 各景点短链 PV/UV/今日点击（来自短链平台）

## 项目结构

```text
ai-guide
├── guide-common           # 公共模块：Result/错误码/业务异常
├── guide-dao              # 数据访问：实体、Mapper、schema.sql
├── guide-service          # 业务逻辑：RAG/LLM/TTS/短链集成/鉴权
├── guide-web              # 接口层：Controller、SSE、拦截器、启动类（唯一可运行模块）
│   └── resources
│       ├── pages          # 游客端/管理后台页面（经 Controller 鉴权后返回）
│       ├── static         # Live2D 模型与运行库
│       └── sql            # schema.sql（启动幂等执行，自动建库建表）
```

## API 一览

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | /api/chat/stream | 流式问答（SSE：text/audio/links/done/error） | 否 |
| POST | /api/chat | 同步问答（A/B 基线/降级） | 否 |
| GET | /api/chat/status | LLM 配置探活 | 否 |
| GET | /api/audio/{id} | 音频拉取（UUID 注册表） | 否 |
| GET | /api/config | 数字人配置（游客端只读） | 否 |
| GET | / | 游客端页面 | 否 |
| GET | /admin | 管理后台页面 | Basic/JWT |
| POST | /api/admin/knowledge/upload | 上传知识文档 | Basic/JWT |
| GET | /api/admin/knowledge/list | 知识列表 | Basic/JWT |
| DELETE | /api/admin/knowledge/{id} | 删除知识文档 | Basic/JWT |
| POST/GET | /api/admin/scene | 景点短链映射 CRUD | Basic/JWT |
| GET | /api/admin/interactions | 交互记录（分页） | Basic/JWT |
| GET | /api/admin/dashboard | 数据大屏（含短链漏斗） | Basic/JWT |
| POST | /api/admin/config | 数字人配置更新 | Basic/JWT |

## 与 Python 版的差异（重写要点）

| 维度 | Python 版 | Java 版 |
|---|---|---|
| 框架 | Flask + generator SSE | Spring Boot + SseEmitter |
| 检索 | SQLite FTS5 + jieba BM25 | MySQL FULLTEXT ngram + jieba 关键词（相关性排序语义等价） |
| TTS | edge-tts 库 | 自实现 Edge TTS 协议客户端（JDK WebSocket 零依赖） |
| 音频分发 | 临时文件 + 路径穿越双保险 + 定时清扫 | 内存 UUID 注册表 + TTL 清扫（无路径穿越面） |
| LLM | openai SDK | JDK HttpClient 手写 SSE 解析 |
| 鉴权 | HTTP Basic Auth | Basic + 短链平台共享 JWT 双通道 |
| 短链 | 无 | 景点短链映射 + 转化漏斗 + 降级兜底 |

## License

MIT License
