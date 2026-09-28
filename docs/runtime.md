# 运行与分层

Mindhaven 延续原版心屿的粉紫色、浅底与圆角卡片。Web 版使用桌面侧栏和手机底栏，机构管理有独立入口。

## 代码职责

- `web/controller` 校验 HTTP 输入并调用应用服务；`web/stream` 只订阅已存在的任务。
- `application/chat` 管理完整会话、查询重写、检索上下文与增量摘要；`application/ai` 管理执行、取消、预算及调用记录。
- `domain/port` 定义任务、用量、提示词与业务存储接口；`infrastructure` 通过 MyBatis-Plus 实现关系数据持久化，并实现资源文件读取。
- 前端 `features/home`、`chat`、`surveys`、`courses`、`admin` 按功能组织。聊天协议位于 `features/chat/api.ts`，状态位于 `useChat.ts`，组件负责展示。`shared` 管理编辑离开提醒，`styles/tokens.css` 管理主题。

## 任务协议

1. `POST /api/sessions/{id}/runs` 接收消息及 `requestId`。数据库约束 `(tenant, owner, session, requestId)`；同一标识重试返回同一任务，不会再次调用模型。相同标识携带不同内容返回 409。
2. `GET /api/runs/{id}/events?after={seq}` 读取持久化事件。事件 ID 单调递增，客户端从上次序号恢复；重新打开会话则读取历史和最近任务。
3. `POST /api/runs/{id}/cancel` 标记取消并尝试中断执行，保留已写入的部分回复。网络中断只结束订阅，不取消服务端任务。
4. 完整消息、指标、`done` 事件与任务完成状态在同一事务提交。失败轮次不注入下一轮上下文。

每个账号最多一个活动聊天任务，工作线程和队列均有界。`AI_RUN_DEADLINE_SECONDS` 默认 180 秒，包含排队时间。`AI_RUN_TOKEN_BUDGET` 默认 24000，按各模型阶段的输入 UTF-8 字节估算及最大输出累加预留，是保守准入上限，不是账单 Token。

**当前部署边界为一个应用进程对应一个数据库。** 启动时将遗留活动任务标记为 `INTERRUPTED`，保留记录并禁止自动重放供应商调用。部署多个副本前需要数据库租约与工作节点认领。停止供应商调用是尽力而为，已经产生的供应商费用不能撤销。

## 数据与迁移

Flyway V1 接管原表，V2 增加 `ai_runs`、`ai_run_events`、`ai_usage`。已有数据库以版本 0 建立基线，迁移不清空或重建业务数据。旧 `records` 的归属规则保持不变。升级前备份 SQLite 文件及媒体目录；PostgreSQL 使用同一套迁移 SQL。

普通业务数据仍采用现有带租户和所有者的 `tenant_records`。问卷填写草稿按用户、问卷和发布版本保存，提交成功后删除对应草稿；发布版本快照不变。管理员可以保存不完整的问卷草稿，发布时进行完整性校验。

## 模型上下文与提示词

`resources/prompts` 保存回答、改写、摘要与报告提示词。调用记录保存对应文件内容的 SHA-256。检索材料和历史摘要放在明确标记的参考消息中，不拼接为系统规则；这降低指令混淆，不能单独保证模型免受提示词注入。

完整聊天与模型上下文分开。先预留输出和固定规则空间，再分配检索与历史预算，按完整轮次保留近期对话。摘要保存版本及覆盖消息序号；超长或失败摘要不推进覆盖范围。紧急关键词命中时直接使用本地支持性回复，不经过改写、压缩或模型调用；关键词规则不能覆盖所有风险表达。

报告仍为同步接口。相同用户、相同报告重复分析返回 409，其他报告最多并发四份；缓存按报告事实、模型、模式和提示词哈希失效，避免全局串行锁。

## 用量与边界

`GET /api/usage?runId=...` 可读取本人各阶段用量：回答、改写、摘要、报告、查询向量化、知识索引向量化。记录模型、提示词哈希、耗时、成功/失败/取消和用量来源。服务商未返回 Token 时实际字段为空，同时保留保守估算；演示调用标记 `demo`，计费 Token 为 0。向量库适配层未返回供应商 usage，目前只记估算。

记录首段耗时和引用检查（无资料／未引用／无效 ID／有效 ID）；只检查实际纳入本轮上下文的引用。ID 正确不等于引用支持回答语义。固定评测集和人工核查仍然需要运行。尚未加入货币价格表、每日租户额度、分布式限流、生产监控告警，也没有接入 MCP 或自动执行外部工具。

## 验证

`mvn -s maven-settings.xml test` 覆盖任务重试、取消、事件回放、用户隔离、启动恢复、摘要覆盖边界、草稿以及旧库迁移。`npm run build` 检查前端类型与构建。自动化测试默认使用演示模型，不代表 DeepSeek 或 Qdrant 的线上效果评测。

## 检索分层

`KnowledgeService` 负责租户、主题和版本范围、用量计量及检索编排；`LexicalRetriever` 在范围内计算 BM25；`ReciprocalRankFusion` 只根据各通道名次融合。领域端口 `KnowledgeVectorIndex` 由 `QdrantKnowledgeIndex` 实现，Spring AI 文档及过滤表达式不再进入知识检索应用服务。

Qdrant 查询加过滤条件，返回时再次检查 payload；应用层只接收本机构原文库中仍符合版本/主题的片段。引用原文从业务数据库取，向量 payload 不作为原文权威来源。新增知识后需同步向量索引；同步前混合模式可能仅由 BM25 命中。同步不是 SQLite 和 Qdrant 之间的分布式事务，失败需重试。

`Metrics` 追加检索配置、各通道排名与得分、实际上下文 ID 及引用检查；`Message` 追加引用检查。已有 JSON 记录缺少新字段时保持 null，不重写历史或假定旧回答已通过新校验。消息、指标及 SSE 完成事件仍在同一事务写入。

## 回答消息结构

`ContextPlanner` 负责选择历史和资料并控制预算，`ContextRenderer` 负责格式化，Spring AI 负责 JSON 序列化。消息顺序为：system 规则 → 可选的 conversation_summary 参考消息 → 近期原始 user/assistant 完整轮次 → 当前 user 消息。

有资料时，最后一条消息包含 reference_documents（document 的 id、version 及 title/source/content）和 current_question。没有资料时直接使用用户原话，不生成空资料消息；没有摘要时省略摘要消息。正文和属性中的 &、<、>、引号都转义，防止文本破坏分区结构；标签不是指令隔离或安全机制。预算按最终渲染后的文本估算，包含元数据、标签和转义增长。用户原话和来源快照的数据库保存方式不变。

## Trace 追踪

使用 OpenTelemetry SDK。默认 `TRACE_SAMPLE_RATE=1.0`，完成的 Span 输出到后端日志；日志包含 traceId、spanId、parentSpanId、操作名、耗时和状态，`ai.run` Span 额外携带 runId。HTTP 响应返回 `X-Trace-Id`，可在浏览器 Network 的响应头查找，再搜索本次启动目录中的 `backend.log`。

HTTP 请求创建服务端根 Span；AI 任务在入队时捕获父上下文，工作线程恢复上下文并创建 `ai.run`。回答、改写、摘要、Embedding、知识检索和上下文组装生成子 Span。线程退出后恢复上下文和 MDC，避免线程池串链。SSE 重连是独立 HTTP 请求，任务身份仍由 runId 关联。

可选设置 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces`，向已部署的 OTLP HTTP 接收端异步导出；空值不发送遥测到外部。此配置不自动安装追踪控制台。当前没有逐条 SQL Span、Qdrant 内部 Span 或跨外部模型服务的 traceparent 传播。HTTP Span 统计请求分派耗时，不代表 SSE 整条流的生命周期；生成耗时看 ai.run。

仅采集操作元数据，不采集聊天正文、提示词、检索原文、认证信息、完整 URL 或异常正文。runId 与 traceId 通过日志/导出 Span 关联，尚未写入任务表，旧任务不会补生成 Trace。

### 本地 Jaeger 控制台

Apple 芯片 Mac 可运行 `./scripts/start-jaeger.sh`，启动固定版本 2.21.0（自动下载并验证二进制 SHA-256）。浏览器访问 http://127.0.0.1:16686 ，Service 选择 `mindhaven-backend` 后点击 Find Traces。配置位于 `config/jaeger-local.yml`，仅监听本机地址；采用内存存储，最多保留 10000 条 Trace，退出后清空。

在 `.env` 中配置 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces` 后重启应用即可接入。当前部署验收通过独立的 demo 后端（18081，内存 SQLite）生成了一条完整任务，已从 Jaeger API 读回 HTTP、ai.run、检索、上下文和回答五个 Span，并验证父子关系。原 8080 live 服务未重启，示例耗时不代表真实模型延迟。

Span 详情现包含：检索模式与结果/候选上限；上下文预算、摘要版本、消息与引用数量、未纳入引用数量；模型名、提示词哈希、输入输出估算及实际用量来源、首段耗时；任务排队耗时。Events 包括 stage.started/completed/failed、model.first_chunk 和任务开始/结束。知识原文、对话、异常正文不写入属性或事件。`ai.first_chunk_ms` 从单次模型调用开始计时，与整轮首段耗时口径不同。
