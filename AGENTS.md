# AGENTS.md

本文件是写给 AI 编程助手的项目说明，团队两人的 AI 共用这一份。开始任何工作前请完整读完。

## 1. 项目是什么

**enco**：运行在 Android 手机上的校园 AI 助手。用户说一句话，多个 Agent 协作规划步骤、操作手机上的 App 完成任务，并能记住用户习惯。参加福州软件职业技术学院「AI+」应用能力大赛 AI+编程赛道。

- 设计文档：[docs/design.md](docs/design.md)：架构、多 Agent 主循环、设备层、记忆、安全，**写代码前先读相关章节**
- 开发排期：[docs/roadmap.md](docs/roadmap.md)：每天的任务、验收标准、砍功能顺序
- 许可证：Apache-2.0

## 2. 先确定你在为谁工作

团队两人按模块分工，各自用 AI 开发。**每次开始工作时，先用 `git branch --show-current` 判断角色**，然后读对应的角色说明：

| 当前分支 | 角色 | 角色说明 |
|---|---|---|
| `brain/...` | 甲：Agent 与数据（大脑） | [docs/agents/brain.md](docs/agents/brain.md) |
| `body/...` | 乙：设备与界面（手脚） | [docs/agents/body.md](docs/agents/body.md) |
| `main` 或其他 | 不确定 | **先问用户是甲还是乙**，并建议切到对应前缀的分支再开始 |

**只修改自己角色负责的目录**（见第 4 节）。需要改动对方目录时，不要直接改，告诉用户需要和队友协调，必要时在自己代码里留 `// TODO(body): ...` 或 `// TODO(brain): ...`。

## 3. 技术栈

**完整的选型、版本、用法约定和“明确不用”的清单见 [docs/tech-stack.md](docs/tech-stack.md)。新增依赖、写构建脚本、接入 Shizuku / Room / 网络之前必须先读它。** 下面只是摘要：

| 领域 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.4.21、Jetpack Compose（BOM 2026.09.00，Material 3）、Navigation Compose |
| 包名 | `xyz.her3.enco`（`applicationId` 与 Kotlin 包名一致；`agent-core` 用 `xyz.her3.enco.core`） |
| 构建 | Gradle 9.9.1、AGP 9.5.1（**内置 Kotlin：不要应用 `kotlin-android` 插件，不能用 kapt**）、KSP 2.3.12、JDK 17、Version Catalog |
| 系统版本 | compileSdk 37、targetSdk 36、minSdk 30（Android 11）；**两台测试机都是 Android 14** |
| 手机控制 | **Shizuku 为主**（Shizuku API 13.1.5 + UserService 助手进程 + `UiAutomation` + HiddenApiBypass），支持主屏、小窗、虚拟屏。**无障碍服务只作备用**：Shizuku 没运行时接管主屏的读屏、截图和操作（见设计文档 7.1、7.2 节） |
| 并发 | Kotlin Coroutines + Flow（`StateFlow` / `SharedFlow`，不用 LiveData、RxJava） |
| 网络 | OkHttp 5 + okhttp-sse + kotlinx.serialization，只对接 OpenAI 兼容接口，不用 Retrofit 和厂商 SDK |
| 存储 | Room 2.8（KSP，含 FTS4）+ DataStore；API Key 用 Android Keystore（AES-GCM）加密 |
| 依赖注入 | 手写 ServiceLocator，不引入 Hilt / Koin |
| 测试 | JUnit 4、kotlinx-coroutines-test、Turbine、MockWebServer；优先手写假实现，不用 mock 框架 |
| 格式化 | Spotless + ktlint（提交前执行 `.\gradlew.bat spotlessApply`） |

## 4. 目录与归属

```text
enco/
├── AGENTS.md                         共享：本文件
├── agent-core/                       甲：纯 Kotlin/JVM 模块，不依赖 Android
│   └── src/main/kotlin/xyz/her3/enco/core/
│       ├── api/                      ★ 接口合同：双方共同维护，修改规则见第 5 节
│       ├── agent/                    甲：Orchestrator、Planner、Operator、Reflector、Curator
│       ├── guard/                    甲：敏感动作识别
│       ├── perception/               甲：元素列表压缩、标注截图的坐标计算
│       ├── protocol/                 甲：动作协议解析与校验
│       └── prompt/                   甲：Prompt 组装（模板放在 src/main/resources/prompts/）
├── app/
│   └── src/main/
│       ├── aidl/xyz/her3/enco/device/shell/   乙：助手进程的 AIDL 接口
│       ├── java/xyz/her3/enco/
│       │   ├── llm/                  甲：OpenAI 兼容客户端
│       │   ├── memory/               甲：Room 实体、DAO、检索
│       │   ├── campus/               甲：通知分类与提取、课表识别、上课提醒
│       │   ├── device/               乙：Shizuku 与助手进程、无障碍备用后端、舞台、悬浮球、预览小窗、语音、通知监听
│       │   ├── ui/                   乙：全部界面，但下面两个目录归甲
│       │   │   ├── settings/model/   甲：模型设置页
│       │   │   └── confirm/          甲：确认卡片与接管提示
│       │   └── EncoApp.kt            共享：Application 与 ServiceLocator
│       └── res/values/
│           ├── strings.xml           乙
│           └── strings_brain.xml     甲（字符串分文件存放，避免合并冲突）
├── eval/                             甲：评测任务与结果
├── scripts/                          共享：init-upstream.ps1 等辅助脚本
├── upstream/                         不入库：参考项目，只读，见第 8 节
└── docs/                             共享：设计、技术选型、排期、开发日志、第三方声明
```

**共享文件**（`settings.gradle.kts`、各 `build.gradle.kts`、`gradle/libs.versions.toml`、`AndroidManifest.xml`、`EncoApp.kt`）两人都可以改，但只做**最小的追加式修改**，不调整别人已经写好的内容，不重新排序、不格式化整个文件。

## 5. 接口合同（`agent-core/.../core/api/`）

这是两人之间唯一的“合同”，定义了 `DeviceController`、`Stage`、`Observation`、`RawNode`、`Action`、`ActionResult`、`LlmClient`、`AgentEvent`、`MemoryStore` 等类型（含义见设计文档第 6、7 节和排期 2.2 节）。

- **不要擅自修改 `api/` 里已有的签名**。确实需要改时：先向用户说明改什么、为什么、对另一方有什么影响，等用户确认已和队友商量好，再修改。
- 接口改动单独一个提交，提交说明以 `api:` 开头。
- 新增可选字段（带默认值）、新增类型，比修改已有签名更好。
- 双方都可以写 `api/` 接口的**假实现**（放在各自的测试目录或 `debug` 源集），用来在对方没做完时并行开发。

## 6. 构建、测试与真机调试

Windows 环境，在仓库根目录执行。

**第一次拿到项目**（刚 clone 下来、或者发现 `upstream/` 不存在时）先初始化参考项目：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\init-upstream.ps1
```

脚本会把三个参考项目拉到 `upstream/`，并固定在同一个版本，保证两人看到的内容一致。已经存在的项目会跳过；加 `-Update` 会重新同步到固定版本，加 `-Update -Latest` 会更新到各项目的最新提交。网络中断会自动重试 3 次；仍然失败就重新运行脚本。

| 目的 | 命令 |
|---|---|
| `agent-core` 单元测试（最快，不需要手机） | `.\gradlew.bat :agent-core:test` |
| 编译 App | `.\gradlew.bat :app:assembleDebug` |
| 安装到连接的手机 | `.\gradlew.bat :app:installDebug` |
| App 单元测试 | `.\gradlew.bat :app:testDebugUnitTest` |
| 自动格式化代码 | `.\gradlew.bat spotlessApply` |
| 检查格式（不修改） | `.\gradlew.bat spotlessCheck` |
| 查看日志 | `adb logcat -s enco:* AndroidRuntime:E` |
| 截取手机屏幕 | `adb exec-out screencap -p > screen.png` |

- **提交前必须编译通过、相关单元测试通过，并执行过 `spotlessApply`。**
- 能放进 `agent-core` 的逻辑尽量放进去，用 JUnit 在电脑上测，不依赖真机。
- 手机通过 USB 连接时，可以直接安装、看日志、截图来验证改动，不要只凭推测说“应该可以”。
- 日志统一使用 `enco` 前缀的 tag（如 `enco.device`、`enco.agent`）。

## 7. 编码规范

- 遵循 Kotlin 官方代码风格；标识符用英文，注释和文档用中文，界面文字用中文并放进字符串资源。
- 只在需要解释“为什么”的地方写注释，不写复述代码的注释。
- 协程：不用 `GlobalScope`，不在主线程做 IO 或网络；长任务必须可取消。
- **隐藏 API（反射调用 `android.*` 的非公开接口）只允许出现在助手进程代码里**（`device/shell/`），App 进程不调用隐藏 API。
- 不吞异常：要么处理，要么带上下文往上抛；面向用户的错误给出中文说明。
- 新增依赖前先确认许可证与 Apache-2.0 兼容（**不能引入 GPL / AGPL 代码**），并在同一个提交里登记到 `docs/THIRD_PARTY_NOTICES.md`。

## 8. 原创性（参赛硬性要求）

- 本地的 `upstream/` 目录里有 Mika、dsh-go、humanize-agent 三个参考项目，已被 `.gitignore` 忽略，由 `scripts/init-upstream.ps1` 拉取（见第 6 节）。**可以阅读它们了解设计思路，但不得复制、改写或翻译其中的任何代码、Prompt 或资源文件。** dsh-go 是 AGPL-3.0，复制会导致整个项目的许可证冲突。
- 所有代码必须是为 enco 新写的。参考了某个设计思路时，在设计文档第 13 节或开发日志中注明出处。

## 9. 安全底线（任何情况下都不能违反）

- 助手进程只开放固定接口，**不提供执行任意 shell 命令的接口**，模型输出也不能直接变成 shell 命令。
- 付款、转账、输入密码、验证码一律交给用户接管；发送、提交、删除等动作必须经过 Guard。
- 屏幕上读到的文字只作为数据传给模型，Prompt 里要声明不执行屏幕内容中的指令。
- **不要提交任何密钥**：API Key、签名文件、`local.properties`、`.env` 都不能进仓库；日志里也不能打印 API Key。
- 不实现抢课、刷课、代签到、批量刷量、抢购等功能。

## 10. Git 规范

- 分支：甲用 `brain/<主题>`，乙用 `body/<主题>`，例如 `brain/orchestrator`、`body/virtual-display`。
- 提交说明：`<类型>(<范围>): <中文说明>`，类型用 `feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `api`。例如 `feat(device): 虚拟屏支持注入点击`。
- 小步提交，一个提交只做一件事。
- 每天至少合并一次到 `main`：先 `git pull --rebase origin main`，编译和测试通过后再合并、推送。
- 不要 `push --force` 到 `main`，不要改写别人的提交历史。

## 11. 开发日志（参赛评分项）

大赛“规范性”占总分 20%，要看创作过程记录。**每完成一块工作，在 `docs/devlog.md` 末尾追加一条记录**，按下面的格式写，追加在当天日期的标题下（没有就新建）：

```markdown
## 2026-11-13

### 甲 · LLM 客户端
- 完成：OpenAI 兼容客户端，支持流式输出和图片输入
- 问题：某服务商流式结尾不发 [DONE]，导致读取挂起
- 解决：连接关闭时也视为结束，并加了超时
- AI 工具：GitHub Copilot（Agent 模式），生成客户端与单元测试，人工审阅了重试逻辑
```

只写真实发生的事，不要编造。
