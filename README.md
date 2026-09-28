# Mindhaven · 心屿

一个可本地运行的前后端心理健康科普与自我记录应用。Vue 3 + TypeScript 前端，Java 21 + Spring Boot 3.5.7 + Spring AI 1.1.2 后端。默认使用 SQLite，无需 Redis、MongoDB、RocketMQ、Docker 或模型密钥即可体验完整的本地演示流程。

这是**机构多租户本地 MVP**，提供机构管理员和成员登录，数据在服务端按租户及用户隔离。默认监听 127.0.0.1，尚未完成公网部署所需的防滥用、账号恢复和运维治理。量表、课程、知识库均为自编演示材料，不具备临床验证；不存在诊断、风险分级或自动干预能力。

## 快速启动

需要 JDK 21 或更高版本、Maven 3.9+、Node.js 20.19+。在项目根目录执行：

```bash
./start.sh
```

前端 http://127.0.0.1:5173，后端 http://127.0.0.1:8080/api/health。Ctrl+C 停止本次启动的进程。

macOS 可以直接双击项目根目录的 `start.command`。脚本会寻找本机已有的 JDK 21+，包括 Homebrew JDK；未找到时提示安装，不修改系统 Java 配置。

```bash
./start.sh --check   # 只检查依赖、配置和端口
./start.sh           # 演示回复 + 本地 BM25 检索
./start.sh --ai      # 真实模型 + 本地 BM25 检索，无需 Docker/Ollama
./start.sh --vector  # BM25 + Qdrant 混合检索 + Ollama Embedding，回复仍为演示
./start.sh --live    # 混合检索 + 真实模型，密钥可在终端隐藏输入
```

脚本会执行 `npm ci`、构建后端、按顺序启动前后端并检查就绪。日志写入 `.runtime/run-日期-进程号/`。Ctrl+C 清理本次启动的应用进程；不结束其他程序。端口 8080 或 5173 被占用时明确报错，不自动改端口。

使用真实模型但暂不启用向量检索时，运行 `./start.sh --ai`。兼容网关通过 `DEEPSEEK_BASE_URL`、`DEEPSEEK_CHAT_PATH`、`DEEPSEEK_MODEL` 配置，最终地址是 base URL 与 path 拼接。密钥可通过环境变量或 `.env` 提供；交互启动未配置密钥时会隐藏输入，仅在本次进程中使用。

脚本默认强制使用演示模式；`.env` 中的 `AI_MODE`、`VECTOR_MODE` 不覆盖命令行模式。`.env` 是可信的 Shell 配置文件，会被 source，请勿粘贴来源不明的命令。若需复用特定 Maven 缓存，可设置 `MAVEN_REPO_LOCAL`。npm 默认使用项目 `.runtime/npm-cache`，也可通过 `npm_config_cache` 指定已有缓存目录。

也可以分开启动：

```bash
# 终端 1
cd backend
mvn clean package
java -jar target/mindhaven-0.1.0.jar

# 终端 2
cd frontend
npm ci
npm run dev
```

Maven 使用本机默认配置（`~/.m2/settings.xml`）；IDEA 中将 `backend/pom.xml` 导入为 Maven 项目，选择 JDK 21。前端依赖由 package-lock.json 固定。构建中的 JAR 不应同时作为运行中的 JAR 被覆盖；开发脚本会创建独立运行副本。

## 首次使用与问卷录入

1. 打开前端，选择「我是管理员，创建新机构」。填写机构代码、名称、管理员用户名和密码；没有预设生产账号。
2. 进入「机构管理 → 问卷管理 → 录入问卷」，填写名称和说明。
3. 添加单选、多选或文本题；可调整顺序、设置必填、添加选项及 0–100 分的选项分值。每份问卷 1–50 题，选择题 2–12 个选项。
4. 「保存草稿」保留编辑内容；「保存并发布新版本」让机构成员可见。未填完的标题、题目和选项也可保存；发布时再检查必填内容与题目数量。
5. 「成员管理」添加成员。成员使用同一机构代码及独立账号登录，在「心理自评」选择已发布问卷填写。回答自动保存为个人草稿，重新打开同一版本可继续填写。
6. 成员在「成长记录」查看自己的答卷；管理员在问卷的「查看答卷」中查看机构提交记录。停止收集后不再接受提交，历史答卷保留。

已发布问卷继续编辑时，旧发布版本仍供成员填写；再次发布生成新版本。答卷保存当时的题目、选项文字、分值和版本，不根据最新问卷重新计算。允许已打开的旧发布版本提交；停止收集后所有版本均拒绝提交。

## 微课堂配置

「机构管理 → 微课堂管理 → 新增课程」可录入标题、分类、阅读时长（1–240 分钟）、简介（最多 500 字）和正文（最多 20000 字，支持换行）。保存草稿后，成员仍看到上次发布的正文；发布后才更新微课堂。停用课程会从成员列表和首页移除，可从管理列表编辑并重新发布。已有机构的内置课程也可以直接编辑，无需迁移数据。

课程完成记录按课程 ID 保留，重新发布不会强制成员重学；目前没有按发布版本记录学习快照。课程支持纯文本与视频，至少配置一项；暂不提供富文本编辑或通用附件上传。

## 微课堂视频上传与部署配置

编辑课程时，在「课程视频」选择 MP4 / WebM 文件。页面显示上传进度，支持取消、预览、替换及移除。上传成功后还需「保存课程草稿」或「保存并发布课程」将文件关联到课程；只上传不等于发布。视频课程可不填写正文，但仍需标题、分类、时长和简介。推荐 MP4（H.264 + AAC），能否播放取决于文件编码及浏览器支持，服务端不转码。

`.env` 配置（`./start.sh` 自动加载）：

```bash
MEDIA_DIR=./media
VIDEO_MAX_SIZE=512MB
VIDEO_REQUEST_MAX_SIZE=520MB
```

- 默认文件落在 `backend/media/<tenantId>/<videoId>`，文件名由服务器生成；数据库保存视频元数据及课程关联，SQLite 不保存视频二进制。可将 MEDIA_DIR 设为绝对路径或持久化挂载目录；需同时备份数据库与该目录。
- 单文件上限默认 512 MB；请求上限要稍大于文件上限。前端从服务端读取大小限制。限制在 Spring multipart 层及业务层检查，扩展名与文件头做基础校验，不等同于完整媒体解码或病毒扫描。
- 视频通过带登录校验的 `/api/videos/{id}` 返回，支持 HTTP Range / 206 拖动播放。管理员只能预览本机构文件；成员仅能访问本机构当前已发布课程引用的视频。停用课程或发布移除/替换后，旧文件不再供成员新请求访问（若其他已发布课程仍引用则继续可读）。已缓冲的媒体内容无法撤回。
- 保存替换草稿不会影响已发布课程。文件是不可变对象，替换产生新 ID。为避免删除仍被引用的视频，取消关联或丢弃草稿不会物理删除文件；当前未实现孤立文件自动清理、存储配额，管理员需监控磁盘或 Bucket 用量。
- 如另行配置 Nginx，需设置例如 `client_max_body_size 520m;`、合适的上传超时，并完整转发 Cookie、Range、If-Range。不要把 media 目录配置为公开静态目录，这会绕过租户权限。

## 租户与权限边界

| 资源 | 可见范围 | 管理权限 |
|---|---|---|
| 问卷、知识库、课程 | 同一机构成员 | 问卷、知识和课程写入仅机构管理员 |
| 问卷答卷 | 填写者及其机构管理员 | 成员不能查看管理接口 |
| 聊天、摘要、指标、私人树洞、课程进度、报告解读 | 当前用户本人 | 机构管理员也不能通过个人接口读取其他成员记录 |
| 成员目录 | 机构管理员 | 只能创建本机构普通成员，不能自行指定角色 |

租户身份来自数据库中的登录会话，不接受 `X-Tenant-Id` 等请求头切换身份。会话 Cookie 为 HttpOnly、SameSite=Strict，8 小时有效；数据库仅保存令牌 SHA-256。密码使用独立盐和 PBKDF2 哈希。生产 HTTPS 可设 `MINDHAVEN_SECURE_COOKIE=true`，且需另行配置可信域名、反向代理、限流、密码恢复、备份和审计等。

数据库由 Flyway 管理迁移，首次升级会保留已有业务记录。SQLite 包含账号认证表、`chat_session`、`chat_message`、`knowledge_chunk` 和其余模块使用的 `tenant_records`。知识按 tenant_id 隔离，会话和消息额外按 owner_id 隔离；通用表中的租户共享数据使用空 owner_id，个人数据使用实际 user_id。旧单用户 `records` 表保留原样，**不会自动导入新机构**；目前不提供旧数据归属迁移工具。

Qdrant 索引 ID 包含租户命名空间，payload 包含 tenantId，检索强制 tenantId + 知识版本过滤；返回结果再次核对 tenantId。老索引没有 tenantId，不会被新查询命中，管理员需要重新同步本机构索引。

## 已实现

- 首页：心情入口、功能导航、课程推荐。
- AI 倾听室：持久化任务、请求防重、SSE 事件回放、停止与断线恢复、会话新建/切换、完整消息持久化、失败轮次隔离、知识原文快照回查。
- RAG：主题/版本过滤、历史指代重写、来源 ID 引用；支持本地 BM25，以及 BM25 + Qdrant 向量召回的应用层 RRF 融合。
- 上下文：预留输出预算，按完整 user/assistant 轮次保留最近历史，增量摘要和覆盖消息序号，已覆盖消息不重复注入。
- 心理自评：动态问卷录入、草稿/发布/停用、版本快照、三类题型、服务端校验与计分、管理员查看答卷；内置 5 题示例。
- AI 运行：独立记录回答、改写、摘要、报告与向量化用量；提示词文件化并记录内容哈希，任务有总时限与保守 Token 预算。
- 报告：规则解读；真实 AI 模式可生成额外解读并保存，演示模式明确显示固定演示解读。
- 微课堂：机构管理员配置标题、分类、预计阅读分钟、简介和纯文本正文；草稿、发布、停用及学习完成记录，内置 3 篇示例可继续编辑。
- 树洞：私人内容保存、每条一次自我拥抱、删除；不是公开交流社区。
- 知识管理：逐片段录入、独立版本、手动同步 Qdrant 索引。
- 评测：固定样例、两组查询重写对照；压缩由服务端配置，分别启动启用/禁用实例可做四组对照、Recall@4、首段耗时、上下文估算、最终回答调用的 Token usage、摘要及人工复核字段。

## 任务协议与运行边界

| 接口 | 行为 |
| --- | --- |
| `POST /api/sessions/{id}/runs` | 提交消息和 `requestId`；同一租户、用户、会话下重复请求返回原任务，标识相同但内容不同返回 409 |
| `GET /api/runs/{id}/events?after={seq}` | 按递增序号回放持久化事件，断线后从上次序号恢复 |
| `POST /api/runs/{id}/cancel` | 标记取消并尝试中断执行，保留已写入的部分回复；仅断开网络不会取消任务 |
| `GET /api/usage?runId=...` | 查询本人任务各阶段的模型、耗时、状态及实际或估算用量 |

完整消息、指标、`done` 事件和任务完成状态在同一事务提交；失败轮次不进入后续上下文。每个账号最多一个活动聊天任务，线程池和队列均有界。`AI_RUN_DEADLINE_SECONDS` 默认 180 秒，包含排队；`AI_RUN_TOKEN_BUDGET` 默认 24000，累计预留各阶段估算输入和最大输出，不代表账单用量。

**当前只支持一个应用进程对应一个数据库。** 启动恢复会将遗留活动任务标为 `INTERRUPTED`，保留记录，不自动重放模型调用。多副本运行需要补充任务租约与节点认领；取消外部调用是尽力而为，已产生的费用不会撤销。

报告分析为同步接口，同一用户对同一报告并发分析返回 409，其他报告最多并发四份；缓存根据报告事实、模型、模式和提示词哈希失效。尚未提供货币费用换算、每日租户额度、分布式限流、生产告警或 MCP 外部工具执行。

## 模型与检索模式

| 配置 | 默认 | 含义 |
|---|---|---|
| `AI_MODE` | `demo` | `demo` 为确定性回复；`live` 使用 Spring AI 调用 DeepSeek 兼容接口 |
| `VECTOR_MODE` | `local` | `local` 使用本地 BM25；`qdrant` 默认将向量召回与 BM25 做 RRF 融合 |
| `DEEPSEEK_API_KEY` | 空 | 仅真实 AI 模式需要，不要提交到 Git |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 需根据供应商实际可用模型确认 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 不包含 `/chat/completions` |
| `DEEPSEEK_CHAT_PATH` | `/chat/completions` | 模型接口路径 |
| `EMBEDDING_BASE_URL` | `http://localhost:11434` | OpenAI-compatible Embedding 服务，例如本地 Ollama |
| `EMBEDDING_PATH` | `/v1/embeddings` | 按供应商调整 |
| `EMBEDDING_MODEL` | `embeddinggemma` | 必须已在向量服务中提供 |
| `EMBEDDING_API_KEY` | `local` | 本地 Ollama 占位值，云服务请使用实际密钥 |
| `QDRANT_COLLECTION` | `mindhaven_embeddinggemma_v1` | 换 Embedding 模型或维度时使用新集合并重新建索引 |

Apple 芯片 Mac 可在项目内安装固定版本的 Qdrant 与 Ollama 官方运行包，无需 Docker、sudo 或修改系统 PATH：

```bash
./scripts/setup-vector-macos.sh
./start.sh --vector
```

安装包会校验 SHA-256；可执行文件放在 Git 忽略的 `.runtime/tools`，Ollama 首次运行会在 `~/.ollama` 创建本机身份文件。其他系统可安装并启动 Docker（含 Compose v2）和 Ollama；也可以自行安装原生 `qdrant` 并加入 PATH。Java/Node/Maven 依赖仍然需要。

```bash
# 只体验向量检索，不需要 DeepSeek 密钥
./start.sh --vector

# 真实问答
# 按需配置 .env 中的模型地址，已有 .env 请勿覆盖；密钥可在终端输入
./start.sh --live
```

这两个模式会自动选择已安装的原生 Qdrant，否则使用 Compose；可通过 `QDRANT_RUNTIME=native|docker` 显式选择。它们复用本机已运行的 Ollama 或启动一个本地服务，本地模型存在时直接复用，否则拉取 `EMBEDDING_MODEL`（默认 `embeddinggemma`，约 622 MB）。启动脚本固定使用本机 Qdrant 6333/6334 和 Ollama 11434，仅监听回环地址；外部向量/Embedding 服务请按配置自行分开启动后端。

退出时会停止本次脚本启动的 Ollama 和原生 Qdrant，复用的已有进程不受影响。原生 Qdrant 数据保存在 `.data/qdrant`，模型保存在 `.data/ollama/models`；可通过 `OLLAMA_MODELS` 复用其他模型目录。Qdrant 容器单独保留，可运行 `docker compose stop qdrant` 停止，数据卷不会删除。更换原生/容器运行方式不会自动迁移索引，需重新同步知识；两种方式的持久化位置不同。

然后在聊天页面打开「知识与检索设置」，点击「同步向量索引」。该操作仅为新增或变化的片段调用 Embedding 服务；重复同步跳过已成功且指纹一致的片段。页面显示已同步、待同步和失败数量，失败后重试会保留已完成的进度。原文存在 SQLite，Qdrant 保存片段内容、向量和过滤元数据，引用快照随每条回答保存。

默认 `EMBEDDING_MODEL=embeddinggemma`。未指定 `QDRANT_COLLECTION` 时，脚本为默认模型选择 `mindhaven_embeddinggemma_v1`，为旧 `bge-m3` 选择原集合名，其他模型按名称哈希生成集合名。手动启动后端时默认也是 EmbeddingGemma 集合；切换模型需手动指定新集合。已有 `.env` 中的显式模型和集合配置优先，不会被脚本改写。相同模型名的权重版本升级也需指定新集合、重新同步索引。

查看向量数据：打开 [Qdrant 控制台](http://127.0.0.1:6333/dashboard)，进入 **Collections → mindhaven_embeddinggemma_v1**。Points 显示片段及租户、主题、版本等元数据，Info 显示维度和距离算法。Apple 芯片 Mac 安装脚本会同时安装官方 Web UI；已有原生安装需重新运行该脚本，再重启服务以加载控制台。

Qdrant 就绪后会持续运行以处理检索请求，终端保持运行是正常状态。当前采用前台启动脚本管理本次启动的进程，按 Ctrl+C 停止；电脑重启后需重新运行 `./start.sh --live`。日志位于本次启动打印的目录，其中 `qdrant.log` 是原生向量库日志。

真实模式启动与问答会访问配置的外部服务，并可能产生费用；本地演示不访问这些服务。

## 数据库可插拔边界

普通业务数据通过 MyBatis-Plus 访问数据库。采用 `Controller → Service → Manager → Mapper`：Service 负责业务编排，具体 Manager 封装租户条件与存储操作，Mapper 承担 MyBatis-Plus CRUD 和自定义 SQL。顶层 `manager`、`mapper` 与 `model/entity` 分别归属数据访问、SQL 和表映射，不再为数据库访问额外定义 Repository 接口。`integration` 按 AI、向量检索和媒体存储组织接口与实现。

会话、消息、知识使用独立业务表和对应 SessionManager、MessageManager、KnowledgeManager；消息引用和引用检查结果作为 JSON 快照保存。V3 Java 迁移将 tenant_records 中的这些记录复制到新表，保留原始记录作为升级备份，后续读写只走新表。知识 ID 不变，无需因本次表迁移重建 Qdrant 索引。课程、问卷等其余模块仍使用通用 JSON 表，后续可逐步迁移；账号、认证会话、AI 任务、事件和用量已使用独立表。复合主键记录使用完整键条件和原子 upsert，任务事件使用事务内 `UPDATE ... RETURNING` 分配序号。租户与用户条件由 Manager 显式限定，未启用自动租户插件；身份查询和启动恢复有各自的访问边界。Flyway 继续管理迁移，Qdrant 不经过 MyBatis-Plus。

- SQLite：默认 `backend/mindhaven.db`（按后端工作目录）；单连接池避免本地竞争。
- PostgreSQL：提供 `application-postgres.yml` 和驱动，使用同一 MyBatis-Plus 持久化层及兼容 DDL。**PostgreSQL 适配尚未经实例集成测试验证。**
- MySQL：尚未提供已验证适配，需要驱动、建表方言与集成测试；不能仅换 URL 就宣称兼容。

```bash
SPRING_PROFILES_ACTIVE=postgres \
DB_URL=jdbc:postgresql://localhost:5432/mindhaven \
DB_USER=mindhaven DB_PASSWORD='your-local-password' \
java -jar backend/target/mindhaven-0.1.0.jar
```

Flyway V1 接管原表，V2 增加任务、事件和用量表，已有数据库以版本 0 建立基线；V3 复制会话、消息和知识记录，遇到无效数据会失败并回滚。升级前请备份数据库和媒体目录，迁移后不能直接用旧版本继续写入旧表。

切换数据库不会自动迁移旧数据。当前 JSON 存储适合本地小规模数据；需要复杂统计、大规模并发写入时，应增加规范化表及更完整的事务/并发契约测试。会话锁和问卷编辑锁只在当前 JVM 内有效。问卷 revision 检查与事务用于单实例冲突保护；多实例部署还需数据库级 CAS/行锁及任务租约。

## 混合检索与引用

`VECTOR_MODE=qdrant` 时默认 `RAG_RETRIEVAL_MODE=hybrid`：同一租户、主题及版本范围内，Qdrant 做稠密向量召回，本地 BM25 做词项召回，然后在应用层进行 RRF 排名融合。`dense` 可切回纯向量检索作对照；未启用 Qdrant 时使用本地 BM25。

| 配置 | 默认值 | 作用 |
|---|---|---|
| `RAG_RETRIEVAL_MODE` | `hybrid` | `hybrid` 或 `dense` |
| `RAG_CANDIDATE_LIMIT` | `20` | 混合模式每路最多候选数，范围 4–100 |
| `RAG_RRF_K` | `60` | 融合公式中的平滑常数，范围 1–1000，不是返回结果数 |
| `RAG_VECTOR_THRESHOLD` | `0.35` | 向量通道的相似度阈值，不用于 BM25 或融合分数 |

每篇文档按 `Σ 1/(k + 该通道名次)` 得分，保留前 4 篇，再由上下文预算决定实际注入的片段。融合分数不是相似度或可信度。BM25 使用中文字符二元组及英文词项，查询时读取本机构的小型知识库；没有接入 Qdrant 稀疏向量索引或中文分词器。大规模语料需替换该全文扫描实现，并用固定评测集验证召回。向量服务故障会明确报错，不静默切换检索方式。

引用原文从业务数据库读取，Qdrant payload 不作为权威原文；返回片段再次核对租户、主题及版本。新增知识尚未同步时可能仅由 BM25 命中。数据库与 Qdrant 索引同步不是分布式事务，失败后需重试。

回答完成后保存引用检查结果。有效的 `[片段ID]` 显示为可点击的来源编号；“参考来源”可展开核对原文。聊天区仅展示实际引用的来源；未标注来源记录在诊断面板，不能仅凭这一点认定漏引。引用未知 ID 仍提示核查；旧记录不补造校验结果。检查只能确认编号及是否缺失，不能证明每条结论有原文支持，也不会自动补造引用或额外调用模型修复。

“本轮检索与上下文”展示向量/BM25 排名、RRF 得分、实际注入范围及配置快照，方便回查。

## 上下文与评测口径

提示词保存在 `backend/src/main/resources/prompts`，分别用于回答、改写、摘要和报告；调用记录保存内容的 SHA-256。`ConversationContextService` 管理自动压缩，`ContextPlanner` 控制上下文预算，`ContextRenderer` 格式化消息，Spring AI 负责 JSON 序列化：

```text
system 规则 → 可选摘要参考消息 → 摘要未覆盖的 user/assistant 完整轮次 → 当前 user 消息
```

有检索资料时，当前 user 消息包含 `reference_documents`（片段 ID、版本、标题、来源和正文）及 `current_question`；无资料时直接使用用户原话。摘要和资料不作为系统规则，标签及属性内容经过转义，预算按最终渲染文本估算。分区标签有助于区分内容，但不能保证防住提示词注入。

- 完整消息记录和模型上下文分开。发送失败的 user 消息保留 `failed` 状态，不注入后续上下文。
- 每个会话使用单调 seq；摘要保存 `version` 和 `coveredThroughSeq`。按完整候选上下文估算触发自动压缩，阈值为 min(模型配置窗口 × 80%，窗口 − 输出预留 − 200)。触发后压缩早期完整轮次，保留最近三轮及当前问题；不会为满足预算静默截断这些轮次。摘要未覆盖的消息从数据库增量查询，消息序号独立取数据库最大值。压缩失败不更新摘要，检索资料可按优先级缩减，仍超限则明确提示。
- 输入预算采用 **UTF-8 字节数 + 消息余量** 的保守估算，不是 DeepSeek tokenizer 的精确 Token 数，不能作为计费依据。
- 摘要输入空间由上下文预算动态分配；完整轮次仍超限时明确报错，保留原文及摘要覆盖范围。真实模型摘要的事实保留率需单独人工或模型裁判核验。
- `firstTokenMs`：从本轮服务处理开始到第一段非空回答的耗时，包含重写、摘要和检索，不包含客户端到服务端网络耗时。
- `promptTokens` / `completionTokens`：仅最终回答调用中供应商返回的 usage；未返回时为 null。`/api/usage` 另外记录改写、摘要、报告与 Embedding 等阶段；供应商实测值与估算值分别保存，尚未换算货币费用。
- Recall@4：每题前 4 个原始检索结果命中的标注相关片段数 / 该题相关片段总数，然后按有标注相关片段的题目做宏平均。知识缺失/错误版本样例不计入该分母，单独检查空检索。
- 引用检查区分无资料、未引用、无效 ID 和有效 ID，只允许引用实际纳入本轮上下文的材料；**不等同于引用在语义上支持结论**。
- 内置急迫风险关键词分支仅为演示保护，不是可靠的危机识别系统，可能误报/漏报。

```bash
# 启动后端后运行；默认创建随机评测机构及专用会话，建议使用独立 DB_URL
python3 scripts/evaluate.py --base http://127.0.0.1:8080 --output eval/latest.json
```

如需复用预先创建的评测机构，可通过环境变量 `EVAL_TENANT`、`EVAL_USERNAME`、`EVAL_PASSWORD` 提供账号；脚本不会将凭证写入结果文件。Qdrant 模式下，新建的评测机构会先同步自己的向量索引；复用已有机构时须显式加 `--index`，避免意外修改已有索引。

固定集包含口语表达、多轮指代、长对话、知识缺失、主题和版本过滤。`factRetentionReview` 和 `citationSupportReview` 默认 null，留给实际人工复核，不能把未评测记为通过。评测结果由脚本生成到本地，不纳入版本控制；演示模式结果不代表真实模型质量或性能。

## 验证与目录

```bash
cd backend && mvn test
cd ../frontend && npm ci && npm test && npm run build
```

- `backend/src/main/java/com/mindhaven/`：业务、接口和模型适配。
- `backend/src/test/java/com/mindhaven/`：SQLite 集成、预算/摘要/断线回归、Spring AI 本地 SSE 模拟服务测试。
- `frontend/src/`：Vue 页面、SSE 客户端、响应式样式。
- `eval/cases.json`：固定评测用例。
- `scripts/`：本地依赖安装、Jaeger 启动与评测脚本。
- `config/jaeger-local.yml`：本地追踪控制台配置。

业务范围参考 `powertoredstar/Mindhaven` 后端快照 `078c6c8fd08631a84fb3f5a9b0e7f912d8f6c602` 的业务范围，重新组织为独立前后端。没有复制旧仓库的部署配置或凭证。

## 可配置对象存储

视频默认保存本地；也可通过 `.env` 切换 S3 兼容存储（AWS S3、MinIO 或提供兼容接口的服务）。这是部署级配置，租户使用独立对象前缀，不是让租户在页面录入密钥。

```dotenv
MEDIA_STORAGE=s3
S3_ENDPOINT=http://127.0.0.1:9000
S3_REGION=us-east-1
S3_BUCKET=mindhaven-media
S3_ACCESS_KEY=替换为服务账号的AccessKey
S3_SECRET_KEY=替换为服务账号的SecretKey
S3_PATH_STYLE=true
```

先创建私有 Bucket，再填写 `.env` 并重启 `./start.sh`。服务账号需拥有该 Bucket 对象的 PutObject/GetObject/DeleteObject 权限（删除用于保存失败补偿）。AWS S3 可留空 `S3_ENDPOINT`，填写实际区域并设置 `S3_PATH_STYLE=false`。同时留空两个密钥可使用 AWS SDK 默认凭证链，包括运行环境中的临时凭证或角色。密钥仅在服务端使用，不写入课程或视频元数据，不返回前端。远程部署使用 HTTPS endpoint。

后端代理视频上传和播放，每次读取先校验登录、租户和发布状态，再向对象存储发起 Range 读取；无需开放 Bucket 公共访问或浏览器跨域权限。当前支持单段 Range（含后缀范围），多段 Range 返回 416。没有预签名公开下载链接。使用 AWS SDK 的 [endpoint 与路径寻址配置](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)。

每个新视频保存 local/s3、Bucket、对象 Key 和连接位置标识；历史无存储标识的视频按 local 处理。切换 `MEDIA_STORAGE` 只影响新上传，不迁移旧文件。切回 local 时，若还要播放旧 S3 视频，保留原 S3 连接配置；更换 endpoint/region 后旧视频会提示恢复原连接，不会静默访问新存储。当前只有一个 S3 连接，不支持同时连接多家对象存储。Bucket 可以更换，新视频写新 Bucket，旧视频仍读记录的原 Bucket，凭证需有相应权限。修改 `MEDIA_DIR` 前需自行迁移原本地文件。

上传采用流式单对象 PUT（默认最大 512MB），不把完整视频加载进 JVM 内存；不提供断点续传、分片上传或自动转码。启动验证必填配置，Bucket 存在性和实际权限由首次上传验证。已用本地 S3 HTTP 测试服务验证 SDK 请求，尚未使用真实云账号或 MinIO 实例验收。

## 数据持久化与备份

| 数据 | 持久化位置 | 边界 |
| --- | --- | --- |
| 机构、账号、登录会话 | SQLite `backend/mindhaven.db` | 登录会话有有效期，密码存哈希 |
| 问卷草稿、发布版本、答卷及结果 | 同一 SQLite | 保存成功后保留，未提交表单不保存 |
| 课程草稿、发布配置、学习完成记录 | 同一 SQLite | 完成状态按课程 ID，非播放进度秒数 |
| 聊天完整消息、摘要、引用快照、指标、报告、树洞内容 | 同一 SQLite | 按租户及个人范围隔离 |
| 视频元数据与课程引用 | 同一 SQLite | 视频二进制不写数据库 |
| 视频文件 | 本地 `backend/media` 或配置的 S3 Bucket | 独立于数据库，必须保留对应存储 |
| 知识片段 | SQLite | 本地模式采用 BM25 检索 |
| 向量索引（开启 Qdrant 时） | 原生 `.data/qdrant` 或 Docker 命名卷 | 需备份或从知识原文重建 |

默认一键启动重启不会清除这些数据。问卷填写进度自动保存；聊天尚未发送的输入、上传进度、视频当前播放秒数等不持久化。SQLite/JDBC 数据源由 `DB_URL` 配置；PostgreSQL profile 已提供但未实库验收，不代表任意数据库无需适配。

本地备份建议停止服务后复制数据库文件和完整 `MEDIA_DIR`；在线备份 SQLite 应使用其备份机制，不能仅复制正在写入的主文件。使用 S3 时仍需备份数据库，并为 Bucket 配置相应备份/版本保留策略；数据库不会替你备份视频。Qdrant 使用卷备份或快照。源码 zip 排除了数据库、上传文件、`.env` 和运行日志，因此源码包不是数据备份。

## 后端分层

```text
com.mindhaven/
├── Application.java             # Spring Boot 入口
├── controller/       # HTTP 接口、SSE 订阅与回放
├── service/          # auth/chat/knowledge/course/questionnaire/report 等业务服务
├── manager/          # 租户过滤、复合主键和原子存储操作
├── mapper/           # MyBatis-Plus Mapper 与 SQL
├── model/
│   ├── entity/       # 数据库表映射
│   ├── dto/          # 输入与业务传输对象
│   ├── vo/           # 专用响应对象
│   └── ...           # chat/course/questionnaire 等业务记录与快照
├── integration/      # ai/vector/storage 接口及适配实现
├── observability/    # Trace 与 HTTP 追踪
├── config/           # 配置与组件装配
├── security/         # 认证、租户上下文
└── common/           # 通用异常与工具
```

Controller 负责请求校验、协议与响应，课程发布事务、报告生成、视频权限等由 Service 处理；数据库访问通过 Manager 和 Mapper，外部服务通过 integration 接口接入。SSE 生命周期和 HTTP Range 流式响应留在 Web 层。当前是实用分层，不是完全无框架依赖的领域模型：聊天上下文仍使用 Spring AI 消息类型，文件上传接口仍使用 MultipartFile；知识检索通过 `KnowledgeVectorIndex` 接口隔离 Qdrant 适配。

前端使用 Vue Router 管理页面、登录与权限跳转，目录按职责划分：

```text
frontend/src/
├── main.ts / App.vue  # 应用入口
├── router/           # 路由、登录与权限守卫
├── layouts/          # 登录后的布局和跨页面状态
├── views/            # 业务页面及其专用组件、组合式函数
├── components/       # 公共组件
├── composables/      # 登录、未保存提醒等公共逻辑
├── api/              # HTTP、SSE、上传及各业务接口
├── types/            # 接口和业务类型
├── utils/            # 日期、SSE 解析等工具
└── styles/           # 全局样式与主题变量
```

页面通过 `api` 调用后端，业务状态由对应的 `use*.ts` 管理。评测脚本递归记录 Java 源码哈希。

### 对话意图与资料边界

普通聊天回复统一由配置的模型流式生成，没有按关键词返回固定话术。明确的寒暄及“吃饭”等独立宽泛主题跳过检索，模型根据上下文自然回应或澄清；回答调用正常计入用量。有历史时，这类短答保留上下文交给模型回应，但不追加检索材料。“那怎么办”等指代在有历史时仍按原流程改写、检索；“失眠”等简短症状不会因字数少被过滤。当前为有限规则分流，并非通用意图分类器。

提示词区分用户已确认的事实、助手猜测及知识资料，禁止将材料建议说成用户经历。引用检查仍只校验编号；语义支持需要评测，不能靠提示词保证。

### 自动上下文压缩配置

聊天 API 不再接受压缩开关。后端配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MODEL_CONTEXT_WINDOW` | `12000` | 模型/网关实际可用窗口，应随模型配置；不是自动发现值 |
| `CONTEXT_COMPRESSION_THRESHOLD` | `0.8` | 输入估算达到窗口比例时触发，同时预留输出和安全余量 |
| `CONTEXT_KEEP_RECENT_TURNS` | `3` | 压缩后保留的完整历史轮次，不含当前输入 |
| `CONTEXT_MAX_COMPRESSION_PASSES` | `4` | 单轮最多摘要批次，防止无界调用 |
| `CHAT_MAX_INPUT_CHARACTERS` | `1500` | 单条输入长度上限，可调低，API 硬上限为 1500 |
| `CONTEXT_COMPRESSION_ENABLED` | `true` | 服务端实验开关，普通聊天保持开启 |

Token 目前采用保守的 UTF-8 字节估算，不是模型 tokenizer 精确计数。评测脚本从 health 读取实际压缩配置，不通过请求切换压缩；对照实验需分别启动不同服务端配置并保存不同输出文件。禁用压缩时不注入旧摘要，超预算明确失败，不静默截断历史。

## Trace 与本地 Jaeger

后端使用 OpenTelemetry SDK，默认 `TRACE_SAMPLE_RATE=1.0`。完成的 Span 写入后端日志，包含 traceId、spanId、父 Span、操作名、耗时和状态；HTTP 响应头 `X-Trace-Id` 可用于检索 `backend.log`。AI 任务入队时捕获追踪上下文，工作线程恢复后创建 `ai.run`，回答、改写、摘要、Embedding、检索和上下文组装分别记录子 Span。

Apple 芯片 Mac 启动控制台：

```bash
./scripts/start-jaeger.sh
```

脚本下载并校验固定版本 Jaeger。在 `.env` 配置以下地址并重启应用，随后打开 [Jaeger](http://127.0.0.1:16686)，选择 `mindhaven-backend` 查询新请求：

```dotenv
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces
```

未配置该地址时不向外部导出 Trace。`config/jaeger-local.yml` 仅监听本机，使用内存存储，最多保留 10000 条 Trace，退出后清空。

Span 属性包含检索模式和结果数量、上下文预算、摘要版本、引用数量、模型与提示词哈希、用量来源及排队耗时；Events 记录阶段开始、完成、失败及模型首段返回。`ai.first_chunk_ms` 从单次模型调用开始计时，与整轮 `firstTokenMs` 不同；HTTP Span 只统计请求分派，生成总耗时查看 `ai.run`。SSE 重连是独立请求，通过 runId 关联。

仅记录操作元数据，不记录聊天正文、提示词全文、检索原文、凭证、完整 URL 或异常正文。目前没有逐条 SQL、Qdrant 内部或外部模型服务的跨服务 Span；runId 与 traceId 通过日志及导出 Span 关联，未写入任务表，旧任务不会补生成 Trace。

## 索引维护与贡献

索引状态由 Flyway V4 写入业务数据库，按租户、Embedding 服务/模型/修订版及 Qdrant 集合隔离。每个片段写入前记录待处理状态，远端成功后才标记已同步；中途失败会停止本次同步，重试跳过已成功的片段。相同标题、主题、版本、来源和正文的重复录入返回已有片段。

同一机构只能同时进行一次同步，不阻塞其他机构的新增知识；当前同步仍是同步 HTTP 请求，适合本地小型知识库，不是持久化后台任务。状态表示最近写入结果，不实时检查远端集合；若清空或恢复了 Qdrant 数据，请在「索引维护」中重新同步全部片段。模型权重改变时设置新的 `EMBEDDING_REVISION`（默认 `v1`）并使用新集合，避免不同向量空间混用。数据库与 Qdrant 之间没有分布式事务，发生不确定写入时依靠稳定 Point ID 重试。

贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，安全问题反馈见 [SECURITY.md](SECURITY.md)。GitHub Actions 配置检查 JDK 21 后端测试、前端 SSE 协议测试、类型与构建，以及启动脚本回归；真实供应商调用与质量评测单独执行。项目许可证尚未确定，公开源码不代表已授予开源再分发许可。

### 聊天与前端职责

`SessionService` 负责会话、历史、摘要及指标查询；`ChatService` 编排一轮对话，委托 `QueryRewriteService` 改写问题、`ConversationContextService` 准备上下文、`ChatCommitService` 提交结果。`ChatCommitService` 的事务同时包含完整消息、指标、标题及运行完成回调；回调失败会一起回滚。`ChatRunService` 保留排队、取消、超时及事件持久化职责。

内部通过 `ChatEvent.Delta`、`Sources`、`Done` 传递强类型事件，在运行层转换为原有 SSE 名称和 JSON，客户端协议不变。

前端 `layouts/WorkspaceLayout.vue` 负责导航、初始加载与公共提示，通过 `RouterView` 展示页面；聊天状态保留在布局层，切换页面不会重新创建。课程、报告、树洞和知识库分别通过各自 `use*.ts` 管理状态，列表与详情组件负责展示；`components/AppDialog.vue` 统一处理弹窗、键盘焦点与关闭行为。
