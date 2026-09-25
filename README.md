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
./start.sh           # 演示回复 + 本地关键词检索
./start.sh --vector  # 真实 Qdrant 向量检索 + Ollama Embedding，回复仍为演示
./start.sh --live    # 真实向量检索 + DeepSeek，需要 .env 中的密钥
```

脚本会执行 `npm ci`、构建后端、按顺序启动前后端并检查就绪。日志写入 `.runtime/run-日期-进程号/`。Ctrl+C 清理本次启动的应用进程；不结束其他程序。端口 8080 或 5173 被占用时明确报错，不自动改端口。原 `scripts/dev.sh` 保留为兼容入口，接受相同参数。

脚本默认强制使用演示模式；`.env` 中的 `AI_MODE`、`VECTOR_MODE` 不覆盖命令行模式。`.env` 是可信的 Shell 配置文件，会被 source，请勿粘贴来源不明的命令。若需复用特定 Maven 缓存，可设置 `MAVEN_REPO_LOCAL`。

也可以分开启动：

```bash
# 终端 1
cd backend
mvn -s maven-settings.xml test package
java -jar target/mindhaven-0.1.0.jar

# 终端 2
cd frontend
npm ci
npm run dev
```

`maven-settings.xml` 只配置公开 Maven Central，避免继承开发机器的公司镜像。前端依赖由 package-lock.json 固定。构建中的 JAR 不应同时作为运行中的 JAR 被覆盖；开发脚本会创建独立运行副本。

## 首次使用与问卷录入

1. 打开前端，选择「我是管理员，创建新机构」。填写机构代码、名称、管理员用户名和密码；没有预设生产账号。
2. 进入「机构管理 → 问卷管理 → 录入问卷」，填写名称和说明。
3. 添加单选、多选或文本题；可调整顺序、设置必填、添加选项及 0–100 分的选项分值。每份问卷 1–50 题，选择题 2–12 个选项。
4. 「保存草稿」保留编辑内容；「保存并发布新版本」让机构成员可见。保存要求题目结构完整，未填完的空题需补全后再保存。
5. 「成员管理」添加成员。成员使用同一机构代码及独立账号登录，在「心理自评」选择已发布问卷填写。
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

SQLite 新增 `tenants`、`tenant_users`、`auth_sessions`、`tenant_records`。租户共享数据使用空 owner_id，个人数据使用实际 user_id；每次查询、更新和删除都带 tenant_id/owner_id。旧单用户 `records` 表保留原样，**不会自动导入新机构**；目前不提供旧数据归属迁移工具。

Qdrant 索引 ID 包含租户命名空间，payload 包含 tenantId，检索强制 tenantId + 知识版本过滤；返回结果再次核对 tenantId。老索引没有 tenantId，不会被新查询命中，管理员需要重新同步本机构索引。

## 已实现

- 首页：心情入口、功能导航、课程推荐。
- AI 倾听室：SSE 流式回答、会话新建/切换、完整消息持久化、失败轮次隔离、知识原文快照回查。
- RAG：主题/版本过滤、历史指代重写、来源 ID 引用；支持本地关键词检索和 Spring AI Qdrant VectorStore 两种适配。
- 上下文：预留输出预算，按完整 user/assistant 轮次保留最近历史，增量摘要和覆盖消息序号，已覆盖消息不重复注入。
- 心理自评：动态问卷录入、草稿/发布/停用、版本快照、三类题型、服务端校验与计分、管理员查看答卷；内置 5 题示例。
- 报告：规则解读；真实 AI 模式可生成额外解读并保存，演示模式明确显示固定演示解读。
- 微课堂：机构管理员配置标题、分类、预计阅读分钟、简介和纯文本正文；草稿、发布、停用及学习完成记录，内置 3 篇示例可继续编辑。
- 树洞：私人内容保存、每条一次自我拥抱、删除；不是公开交流社区。
- 知识管理：逐片段录入、独立版本、手动同步 Qdrant 索引。
- 评测：固定样例、4 组重写/压缩开关组合、Recall@4、首段耗时、上下文估算、最终回答调用的 Token usage、摘要及人工复核字段。

## 模型与检索模式

| 配置 | 默认 | 含义 |
|---|---|---|
| `AI_MODE` | `demo` | `demo` 为确定性回复；`live` 使用 Spring AI 调用 DeepSeek 兼容接口 |
| `VECTOR_MODE` | `local` | `local` 为字符二元组关键词匹配，不是向量检索；`qdrant` 使用真实向量库 |
| `DEEPSEEK_API_KEY` | 空 | 仅真实 AI 模式需要，不要提交到 Git |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 需根据供应商实际可用模型确认 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | 不包含 `/chat/completions` |
| `DEEPSEEK_CHAT_PATH` | `/chat/completions` | 模型接口路径 |
| `EMBEDDING_BASE_URL` | `http://localhost:11434` | OpenAI-compatible Embedding 服务，例如本地 Ollama |
| `EMBEDDING_PATH` | `/v1/embeddings` | 按供应商调整 |
| `EMBEDDING_MODEL` | `embeddinggemma` | 必须已在向量服务中提供 |
| `EMBEDDING_API_KEY` | `local` | 本地 Ollama 占位值，云服务请使用实际密钥 |
| `QDRANT_COLLECTION` | `mindhaven_embeddinggemma_v1` | 换 Embedding 模型或维度时使用新集合并重新建索引 |

真实向量模式的前置条件是安装并启动 Docker（含 Compose v2），安装 Ollama，以及以上 Java/Node/Maven 依赖。脚本不会自动安装这些系统软件。

```bash
# 只体验向量检索，不需要 DeepSeek 密钥
./start.sh --vector

# 真实问答
cp .env.example .env
# 编辑 .env 填写 DEEPSEEK_API_KEY
./start.sh --live
```

这两个模式会自动启动 Compose 中的 Qdrant，复用本机已运行的 Ollama 或启动一个本地 Ollama 服务，本地模型存在时直接复用，否则拉取 `EMBEDDING_MODEL`（默认 `embeddinggemma`）。首次下载模型可能较大，请预留网络、磁盘空间和等待时间。启动脚本固定使用本机 Qdrant 6333/6334 和 Ollama 11434；外部向量/Embedding 服务请按配置自行分开启动后端。

退出时会停止本次脚本启动的 Ollama，已存在的 Ollama 不受影响。Qdrant 容器单独保留，可运行 `docker compose stop qdrant` 停止，数据卷不会删除。

然后在聊天页面打开「知识与检索设置」，点击「同步向量索引」。该操作会调用 Embedding 服务。原文存在 SQLite，Qdrant 保存片段内容、向量和过滤元数据，引用快照随每条回答保存。

真实模式启动与问答会访问配置的外部服务，并可能产生费用；本地演示不访问这些服务。不使用或保存聊天中曾提供的 GitHub 令牌。

## 数据库可插拔边界

`RecordStore` 是业务存储接口，`JdbcRecordStore` 是 JDBC 实现，业务服务不依赖 SQLite API。业务数据使用带 tenant_id/owner_id/bucket/id 复合主键的 JSON 表，身份和会话使用独立关系表。认证服务通过 JDBC 访问关系表；切换数据库需要同时验证身份表与业务存储。

- SQLite：默认 `backend/mindhaven.db`（按后端工作目录）；单连接池避免本地竞争。
- PostgreSQL：提供 `application-postgres.yml` 和驱动，使用相同 JDBC 实现及兼容 DDL。**本次没有运行 PostgreSQL 实例验证。**
- MySQL：尚未提供已验证适配，需要驱动、建表方言与集成测试；不能仅换 URL 就宣称兼容。

```bash
SPRING_PROFILES_ACTIVE=postgres \
DB_URL=jdbc:postgresql://localhost:5432/mindhaven \
DB_USER=mindhaven DB_PASSWORD='your-local-password' \
java -jar backend/target/mindhaven-0.1.0.jar
```

切换数据库不会自动迁移旧数据。当前 JSON 存储适合本地小规模数据；需要复杂统计、大规模并发写入时，应增加规范化表、版本化迁移和事务/并发契约测试。会话锁和问卷编辑锁只在当前 JVM 内有效。问卷 revision 检查与事务用于单实例冲突保护；多实例部署需数据库级 CAS/行锁及迁移管理。

## 上下文与评测口径

- 完整消息记录和模型上下文分开。发送失败的 user 消息保留 `failed` 状态，不注入后续上下文。
- 每个会话使用单调 seq；摘要保存 `version` 和 `coveredThroughSeq`。摘要只处理新增的完整轮次，近期至少保留两轮作为压缩时的保护范围；最终输入仍受总预算约束。
- 输入预算采用 **UTF-8 字节数 + 消息余量** 的保守估算，不是 DeepSeek tokenizer 的精确 Token 数，不能作为计费依据。
- 超大轮次无法进入摘要批次时，预算器可能截去旧轮次；这是一项现有限制。真实模型摘要的事实保留率需单独人工或模型裁判核验。
- `firstTokenMs`：从本轮服务处理开始到第一段非空回答的耗时，包含重写、摘要和检索，不包含客户端到服务端网络耗时。
- `promptTokens` / `completionTokens`：仅最终回答调用中供应商返回的 usage；未返回时为 null。当前没有汇总重写、摘要、报告和 Embedding 的 Token 成本。
- Recall@4：每题前 4 个原始检索结果命中的标注相关片段数 / 该题相关片段总数，然后按有标注相关片段的题目做宏平均。知识缺失/错误版本样例不计入该分母，单独检查空检索。
- 引用 ID 检查只验证引用是否来自检索材料，**不等同于引用在语义上支持结论**。
- 内置急迫风险关键词分支仅为演示保护，不是可靠的危机识别系统，可能误报/漏报。

```bash
# 启动后端后运行；默认创建随机评测机构及专用会话，建议使用独立 DB_URL
python3 scripts/evaluate.py --base http://127.0.0.1:8080 --output eval/latest.json
```

如需复用预先创建的评测机构，可通过环境变量 `EVAL_TENANT`、`EVAL_USERNAME`、`EVAL_PASSWORD` 提供账号；脚本不会将凭证写入结果文件。

固定集包含口语表达、多轮指代、长对话、知识缺失、主题和版本过滤。`factRetentionReview` 和 `citationSupportReview` 默认 null，留给实际人工复核，不能把未评测记为通过。`eval/demo-results.json` 是本地演示模式的链路验证记录，不是模型质量或性能结论。

## 验证与目录

```bash
cd backend && mvn -s maven-settings.xml test
cd ../frontend && npm ci && npm run build
```

- `backend/src/main/java/com/mindhaven/`：业务、接口和模型适配。
- `backend/src/test/java/com/mindhaven/`：SQLite 集成、预算/摘要/断线回归、Spring AI 本地 SSE 模拟服务测试。
- `frontend/src/`：Vue 页面、SSE 客户端、响应式样式。
- `eval/`：固定样例和演示结果。
- `VALIDATION.md`：实际完成的验证及未验证边界。

本实现参考用户指定的 `powertoredstar/Mindhaven` 后端快照 `078c6c8fd08631a84fb3f5a9b0e7f912d8f6c602` 的业务范围，重新组织为独立前后端。没有复制旧仓库的部署配置或凭证，没有向远端提交。

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
| 知识片段 | SQLite | 本地模式采用关键词检索 |
| 向量索引（开启 Qdrant 时） | Qdrant 的 Docker 命名卷 | 需保留或重建；真实环境尚未验收 |

默认一键启动重启不会清除这些数据。页面临时输入、上传进度、视频当前播放秒数等不持久化。SQLite/JDBC 数据源由 `DB_URL` 配置；PostgreSQL profile 已提供但未实库验收，不代表任意数据库无需适配。

本地备份建议停止服务后复制数据库文件和完整 `MEDIA_DIR`；在线备份 SQLite 应使用其备份机制，不能仅复制正在写入的主文件。使用 S3 时仍需备份数据库，并为 Bucket 配置相应备份/版本保留策略；数据库不会替你备份视频。Qdrant 使用卷备份或快照。源码 zip 排除了数据库、上传文件、`.env` 和运行日志，因此源码包不是数据备份。

## 后端分层

```text
com.mindhaven/
├── Application.java             # Spring Boot 入口
├── web/controller/              # 按聊天、知识、课程、视频、问卷、报告、树洞划分 HTTP 接口
├── web/error/                   # 统一 HTTP 异常响应
├── application/                 # 用例与业务流程
│   ├── auth/                    # 账号、会话、机构初始化
│   ├── chat/                    # 对话、重写、摘要、上下文预算
│   ├── knowledge/               # 知识录入、检索与索引编排
│   ├── course/                  # 草稿发布、学习记录、视频校验与访问控制
│   ├── questionnaire/           # 问卷发布、计分与答卷快照
│   ├── report/                  # 报告解读与结果复用
│   ├── community/               # 个人树洞记录
│   └── dto/                     # 用例输入与输出
├── domain/model/                # 业务数据记录
├── domain/port/                 # RecordStore、AuthRepository、AiGateway、MediaStorage 接口
├── infrastructure/persistence/  # JDBC 记录与账号仓储
├── infrastructure/storage/      # 本地文件与 S3 实现
├── security/                    # 登录过滤、来源校验、租户上下文
├── config/                      # 配置绑定及 Spring AI / Qdrant Bean 装配
└── common/error/                # 共用异常
```

Controller 负责请求校验、协议与响应，课程发布事务、报告生成、视频权限等由 Service 处理；业务服务通过仓储/存储接口访问基础设施。SSE 生命周期和 HTTP Range 流式响应留在 Web 层。当前是实用分层，不是完全无框架依赖的领域模型：检索编排仍使用 Spring AI 类型，文件上传接口仍使用 MultipartFile。

分包不改变接口 URL、数据库表或 JSON 字段。评测脚本递归记录全部 Java 源码哈希，避免分包后漏记来源。

### 轻量本地 Embedding 启动

`./start.sh --vector` 使用本地 EmbeddingGemma + Qdrant，回复仍为演示模式；配置 DeepSeek Key 后 `./start.sh --live` 才使用真实回答。脚本需要已安装 Docker 与 Ollama，并按需下载模型，不自动安装系统软件。

默认 `EMBEDDING_MODEL=embeddinggemma`。未指定 `QDRANT_COLLECTION` 时，脚本为默认模型选择 `mindhaven_embeddinggemma_v1`，为旧 `bge-m3` 选择原集合名，其他模型按名称哈希生成集合名。手动启动后端时默认也是 EmbeddingGemma 集合；切换模型需手动指定新集合。已有 `.env` 中的显式模型和集合配置优先，不会被脚本改写。相同模型名的权重版本升级也需指定新集合、重新同步索引。
