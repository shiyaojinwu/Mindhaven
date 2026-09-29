# Mindhaven · 心屿

面向心理健康科普与自我记录的 AI 应用，支持多租户机构管理、知识问答、问卷评估和微课堂。由心屿 App 延伸为独立的前后端演示项目，用于探索 AI 应用与 Web 开发。

默认使用 SQLite，无需模型密钥即可运行演示模式；可接入真实模型、本地 Embedding 和 Qdrant。

## 功能

- **AI 对话**：流式回复、历史会话、停止生成与断线恢复；Agent 可查询知识、课程和问卷。
- **知识检索**：BM25 与向量混合检索、RRF 排序、主题和版本过滤、引用原文回查。
- **上下文管理**：完整消息持久化、自动摘要压缩、近期对话保留与模型用量记录。
- **问卷评估**：问卷录入、草稿与发布、版本快照、答卷保存和报告解读。
- **微课堂**：课程配置、视频上传、发布管理和学习记录，支持本地或 S3 兼容存储。
- **机构管理**：机构管理员与成员账号，按租户及用户隔离数据。
- **个人记录**：心情记录与私人树洞。
- **可观测性**：持久化运行事件、OpenTelemetry 链路追踪和可选 Jaeger 控制台。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Vue Router |
| 后端 | Java 21、Spring Boot、Spring AI、MyBatis-Plus |
| 数据存储 | SQLite、Flyway；可选 S3 兼容对象存储 |
| AI 与检索 | DeepSeek 兼容接口、Ollama、Qdrant、BM25 / RRF |
| 可观测性 | OpenTelemetry、Jaeger |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- Node.js 20.19+

### 启动项目

```bash
git clone https://github.com/shiyaojinwu/Mindhaven.git
cd Mindhaven
./start.sh
```

启动脚本会安装前端依赖、构建后端并启动服务。macOS 也可双击 `start.command`。

- 应用：http://127.0.0.1:5173
- 健康检查：http://127.0.0.1:8080/api/health

首次打开页面，选择「我是管理员，创建新机构」，创建机构和管理员账号，再添加成员、配置问卷及课程。没有预设登录账号。

按 `Ctrl+C` 停止本次启动的服务。重启不会清除数据库；日志位于 `.runtime/`。

### 启动模式

| 命令 | 模型回复 | 检索 |
| --- | --- | --- |
| `./start.sh` | 演示回复 | 本地 BM25 |
| `./start.sh --ai` | 真实模型 | 本地 BM25 |
| `./start.sh --vector` | 演示回复 | BM25 + Qdrant |
| `./start.sh --live` | 真实模型 | BM25 + Qdrant |

使用 `./start.sh --check` 检查依赖、配置和端口。

## 配置

按需将 [.env.example](.env.example) 复制为 `.env`，已有配置请勿覆盖。启动脚本自动加载该文件；不要提交密钥或 `.env`。

真实模型的主要配置：

```dotenv
DEEPSEEK_API_KEY=your-api-key
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_CHAT_PATH=/chat/completions
DEEPSEEK_MODEL=deepseek-chat
```

`MODEL_CONTEXT_WINDOW` 和 `MODEL_MAX_OUTPUT_TOKENS` 应根据实际模型及网关限制设置。真实模式可能产生模型调用费用。

向量模式需要 Qdrant 和 Ollama。Apple 芯片 Mac 可先执行：

```bash
./scripts/setup-vector-macos.sh
./start.sh --vector
```

启动后，在知识管理页面同步向量索引。业务原文保存在 SQLite，向量索引保存在 Qdrant；切换 Embedding 模型后需使用新集合并重新同步。

视频默认保存在 `backend/media/`；使用对象存储时设置 `MEDIA_STORAGE=s3` 及相应连接配置。备份时需同时保留数据库与媒体文件。

更多配置、问卷与课程操作、存储、链路追踪和运行边界见 [配置与使用指南](docs/guide.md)。

## 项目结构

```text
Mindhaven/
├── backend/             # Spring Boot 后端
│   └── src/main/
│       ├── java/com/mindhaven/
│       │   ├── controller/     # HTTP 接口
│       │   ├── service/        # 业务编排与 Agent 执行
│       │   ├── manager/        # 数据访问与租户条件
│       │   ├── mapper/         # MyBatis-Plus 与 SQL
│       │   ├── model/          # 实体、请求和响应模型
│       │   └── integration/    # 模型、向量和存储适配
│       └── resources/          # 配置、提示词和数据库迁移
├── frontend/src/
│   ├── views/           # 业务页面与专用逻辑
│   ├── components/      # 公共组件
│   ├── layouts/         # 页面布局
│   ├── api/             # 后端接口与 SSE
│   ├── router/          # 路由与权限守卫
│   └── types/           # TypeScript 类型
├── scripts/             # 启动辅助与评测脚本
├── config/              # 本地服务配置
├── eval/                # 固定评测用例
├── docs/                # 使用文档
└── start.sh             # 一键启动入口
```

后端采用 `Controller → Service → Manager → Mapper` 分层，外部服务通过 `integration` 接入。前端按页面与职责组织，页面专用状态使用组合式函数管理。

## 开发与测试

```bash
mvn -B -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
python3 scripts/test_launcher.py
```

GitHub Actions 执行后端测试、前端测试与构建、启动脚本检查。真实模型质量与工具选择需要单独评测，离线测试不能替代真实网关验证。

## 当前限制

- 项目以本地单实例运行和学习演示为主，暂不支持多副本任务调度或服务重启后自动续跑 Agent。
- 课程、问卷和知识包含演示材料，不用于临床诊断或替代专业服务。
- 引用检查校验来源标识，不代表已验证引用对结论的语义支持；资源真实性与摘要质量仍需完善评测。
- PostgreSQL 配置已提供，但尚未经真实实例集成验证；默认使用 SQLite。

## 参与贡献

欢迎通过 Issue 反馈问题或提交 Pull Request。开发约定见 [CONTRIBUTING.md](CONTRIBUTING.md)，安全问题反馈见 [SECURITY.md](SECURITY.md)。

## 许可证

许可证尚未确定。公开源码不代表已授予开源再分发许可，使用或分发前请与维护者确认。
