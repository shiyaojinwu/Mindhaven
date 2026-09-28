# 参与贡献

欢迎通过 Issue 报告问题、讨论功能，或提交 Pull Request。较大改动请先说明使用场景和方案。

## 本地开发

需要 JDK 21、Maven 3.9+、Node.js 20.19+。运行 `./start.sh` 可使用无需模型密钥的演示模式，完整配置见 README。

提交前运行：

```bash
mvn -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
python3 scripts/test_launcher.py
git diff --check
```

后端遵循 Controller → Service → Manager → Mapper；外部模型、向量与存储适配放在 integration。前端按 features 组织。涉及权限、数据迁移、流式恢复或模型预算时，请补充对应回归测试。

PR 请说明解决的问题、行为变化、验证结果和兼容性影响。不要提交密钥、真实对话、个人答卷、数据库、上传文件或运行日志。新增演示资料应确认允许公开使用。

真实模型和向量服务评测与离线测试分开；未运行的检查请明确注明。许可证尚未确定，贡献前请先与维护者确认代码授权范围。
