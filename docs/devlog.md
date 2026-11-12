# enco 开发日志

## 2026-11-12

### 工程骨架

- 完成：搭建 `app` 和纯 Kotlin/JVM 的 `agent-core`，接入带分发校验的 Gradle Wrapper、Version Catalog 和 Spotless。添加 Compose 启动页、Apache-2.0 许可证和第三方声明。
- 问题：ktlint 默认命名规则拒绝以大写开头的 Compose 函数。Gradle 默认堆内存在合并 DEX 时不足。
- 解决：将 ktlint 的函数命名例外限定为带 `@Composable` 的函数，并将 Gradle 堆内存上限设为 2 GB。
- 验证：`spotlessApply`、`spotlessCheck` 和 `:app:assembleDebug` 成功。已校验 Debug APK 的签名和启动入口。两个模块的单测任务均为 `NO-SOURCE`。ADB 未检测到设备，真机启动待验证。
- AI 工具：Codex 依据排期搭建工程，排查格式化和打包问题，并校验 Debug APK。

### 提交与变更日志

- 完成：在 `AGENTS.md` 中约定每项工作完成后先写提交说明，再同步到根目录的 `CHANGELOG.md`，随后一并提交。建立变更日志并写入本次记录。
- AI 工具：Codex 更新协作规范和日志。
