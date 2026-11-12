# enco 技术选型

> 版本号核对日期：2026-11-12。来源：Google Maven、Maven Central、各项目 GitHub 发布页、Android 官方 AGP 发布说明。
> 工程搭好后，**版本以 `gradle/libs.versions.toml` 为准**。本文件说明“用什么、为什么、怎么用”，升级依赖时同步更新本文件。

## 1. 开发环境

| 工具 | 版本 | 用途 | 备注 |
|---|---|---|---|
| JDK | **17**（Eclipse Temurin 17） | Gradle 与 AGP 运行环境 | AGP 9.5 的最低要求就是 17；`JAVA_HOME` 指向它 |
| Android Studio | 最新稳定版（需支持 AGP 9.5） | 写代码、看布局预览、调试 | 只用它写代码也可以；构建以命令行 `gradlew.bat` 为准 |
| Android SDK Platform | **API 37** | `compileSdk` | SDK Manager 中安装 |
| Android SDK Build-Tools | **36.0.0** | AGP 9.5 默认版本 | SDK Manager 中安装 |
| Android SDK Platform-Tools | 最新 | `adb` | 需在 `PATH` 中能直接执行 `adb` |
| Git | 最新 | 版本管理 | |
| Python 3 | 可选 | 辅助脚本 | |
| **Shizuku App** | v13.6.0 或更新 | 测试机上提供 shell 权限 | 从 GitHub Releases 或应用商店安装 |
| **scrcpy** | v5.0.1 或更新 | 在电脑上投屏、操控、**录制演示视频** | Apache-2.0；`scrcpy --record demo.mp4` |
| AI 编程助手 | 任选 | 写代码 | 都会读取仓库根目录的 `AGENTS.md` |

`local.properties` 里写 `sdk.dir=...`（Android Studio 打开工程时会自动生成）。**这个文件不能提交。**

## 2. 构建配置

| 项 | 取值 | 说明 |
|---|---|---|
| Gradle | **9.9.1**（Wrapper） | AGP 9.5 最低要求 9.6.0。万一遇到不兼容，退回 9.6.0 |
| Android Gradle Plugin | **9.5.1** | 最高支持 API 37，要求 JDK 17、Build-Tools 36.0.0 |
| Kotlin | **2.4.21** | |
| KSP | **2.3.12** | **不要用 2.3.13**：官方说明这个版本有并发执行的回归问题（google/ksp#3286） |
| `compileSdk` | 37 | |
| `targetSdk` | 36 | 先不适配 API 37 的新行为限制，降低风险 |
| `minSdk` | 30（Android 11） | 无障碍截图需要 API 30；无线调试启动 Shizuku 需要 Android 11 |
| JVM 目标 | 17 | `compileOptions` 设为 17；`agent-core` 用 `kotlin { jvmToolchain(17) }` |
| 依赖声明 | Version Catalog（`gradle/libs.versions.toml`） | 构建脚本里不写死版本号 |

### 2.1 AGP 9 的“内置 Kotlin”（重要）

AGP 9 起默认开启内置 Kotlin，AI 很容易按旧教程写错，务必注意：

- `app` 模块**不要**再应用 `org.jetbrains.kotlin.android`（`kotlin-android`）插件，否则构建直接报错。
- **不能用 kapt**。Room 等注解处理一律用 **KSP**。
- 不要用 `android { kotlinOptions { } }`，改用顶层的 `kotlin { compilerOptions { } }`；`jvmTarget` 不用写，默认跟随 `compileOptions.targetCompatibility`。
- 源码目录：`src/main/java` 和 `src/main/kotlin` 都可以，本项目统一用 `src/main/java`（见 AGENTS.md 第 4 节）。
- Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose` 和序列化插件 `org.jetbrains.kotlin.plugin.serialization` 照常应用，版本与 Kotlin 一致（2.4.21）。
- `agent-core` 不是 Android 模块，照常应用 `org.jetbrains.kotlin.jvm`。

### 2.2 插件清单

| 插件 ID | 版本 | 用在 |
|---|---|---|
| `com.android.application` | 9.5.1 | `app` |
| `org.jetbrains.kotlin.jvm` | 2.4.21 | `agent-core` |
| `org.jetbrains.kotlin.plugin.compose` | 2.4.21 | `app` |
| `org.jetbrains.kotlin.plugin.serialization` | 2.4.21 | `app`、`agent-core` |
| `com.google.devtools.ksp` | 2.3.12 | `app`（Room） |
| `androidx.room` | 2.8.5 | `app`（导出数据库结构） |
| `com.diffplug.spotless` | 8.11.0 | 根工程（代码格式化） |

### 2.3 构建开关

- `app` 开启：`buildFeatures { compose = true; aidl = true; buildConfig = true }`。
- Release 构建**不开启 R8 混淆**（`isMinifyEnabled = false`）。助手进程里的隐藏 API 反射和 AIDL 容易被混淆弄坏，比赛阶段不值得冒这个险。
- 签名：Debug 用默认调试签名。Release 签名文件和密码放在本地的 `local.properties` 或环境变量里，**不进仓库**；构建脚本读不到时，就退回用调试签名。

## 3. 依赖清单

许可证都已核对，与 Apache-2.0 兼容。新增依赖时按同样格式加一行，并登记到 `docs/THIRD_PARTY_NOTICES.md`。

### 3.1 `agent-core`（纯 JVM）

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | 协程、Flow（`AgentEvent` 事件流、可取消的任务） | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.13.0 | 动作协议 JSON 解析与校验、`RawNode` 序列化 | Apache-2.0 |

`agent-core` **只允许依赖这两个库**，不能依赖任何 Android 库，也不能依赖 OkHttp。网络、存储、设备都通过 `api/` 里的接口由 `app` 注入。

### 3.2 `app` 的界面与架构

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| `androidx.compose:compose-bom` | 2026.09.00 | 统一 Compose 各库版本，下面的 Compose 库不写版本 | Apache-2.0 |
| `androidx.compose.ui:ui`、`ui-graphics`、`ui-tooling-preview` | BOM | Compose 基础 | Apache-2.0 |
| `androidx.compose.material3:material3` | BOM | Material 3 组件 | Apache-2.0 |
| `androidx.compose.ui:ui-tooling` | BOM | 预览（只用 `debugImplementation`） | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.13.0 | `ComponentActivity.setContent` | Apache-2.0 |
| `androidx.core:core-ktx` | 1.19.1 | Android 扩展函数 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | `collectAsStateWithLifecycle` | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | 各页面的 ViewModel | Apache-2.0 |
| `androidx.lifecycle:lifecycle-service` | 2.11.0 | `LifecycleService`：悬浮球、预览小窗所在的前台服务里使用 Compose | Apache-2.0 |
| `androidx.navigation:navigation-compose` | 2.10.2 | 页面导航，用 `@Serializable` 的类型安全路由 | Apache-2.0 |
| `com.mikepenz:multiplatform-markdown-renderer-m3` | 0.46.0 | 对话消息里的 Markdown 渲染 | Apache-2.0 |
| `io.coil-kt.coil3:coil-compose` | 3.6.3 | 显示时间线和任务详情里存成文件的截图 | Apache-2.0 |

### 3.3 `app` 的设备层

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| `dev.rikka.shizuku:api` | 13.1.5 | 检测 Shizuku 状态、申请授权、绑定 UserService | MIT |
| `dev.rikka.shizuku:provider` | 13.1.5 | `ShizukuProvider`，在清单中注册，用于接收 Shizuku 的 Binder | MIT |
| `org.lsposed.hiddenapibypass:hiddenapibypass` | 6.2 | **只在助手进程里**解除隐藏 API 限制（`UiAutomation` 连接、虚拟屏创建等） | Apache-2.0 |

### 3.4 `app` 的数据与网络

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.5.1 | 调用模型的 HTTP 客户端 | Apache-2.0 |
| `com.squareup.okhttp3:okhttp-sse` | 5.5.1 | 流式输出（SSE） | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.13.0 | 请求与响应的 JSON | Apache-2.0 |
| `androidx.room:room-runtime`、`room-ktx` | 2.8.5 | 记忆、技能、会话、校园数据，含 FTS4 全文检索 | Apache-2.0 |
| `androidx.room:room-compiler` | 2.8.5 | 用 **`ksp(...)`** 声明，不是 kapt | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.3.0 | 设置项（模型配置、执行位置、悬浮球手势等） | Apache-2.0 |
| `androidx.work:work-runtime-ktx` | 2.12.0 | 上课提醒、定时任务 | Apache-2.0 |

### 3.5 测试与代码质量

| 依赖 | 版本 | 用途 | 许可证 |
|---|---|---|---|
| `junit:junit` | 4.13.2 | 单元测试框架，**两个模块统一用 JUnit 4** | EPL-1.0（仅测试，不打进 APK） |
| `org.jetbrains.kotlin:kotlin-test-junit` | 2.4.21 | `assertEquals` 等 Kotlin 断言 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | `runTest`、虚拟时间（测超时、重试） | Apache-2.0 |
| `app.cash.turbine:turbine` | 1.2.1 | 测试 Flow（`AgentEvent` 事件顺序） | Apache-2.0 |
| `com.squareup.okhttp3:mockwebserver3` | 5.5.1 | 模拟模型服务端，测试 LLM 客户端的流式输出和错误处理 | Apache-2.0 |
| Spotless + ktlint | 8.11.0 / 1.8.0 | 统一代码格式，不同 AI 工具写出来的风格一致 | Apache-2.0 / MIT |

## 4. 平台能力：直接用系统 API，不引入库

| 能力 | 用什么 | 说明 |
|---|---|---|
| API Key 加密 | Android Keystore + `javax.crypto`（AES-GCM） | 不用已被弃用的 `androidx.security:security-crypto` |
| 语音识别 | `android.speech.SpeechRecognizer` | 手机没有系统识别服务时，退回模型服务商的 `/audio/transcriptions` |
| 语音播报 | `android.speech.tts.TextToSpeech` | |
| 截图（无障碍） | `AccessibilityService.takeScreenshot` | 备用后端 |
| 截图（Shizuku） | `UiAutomation.takeScreenshot`；虚拟屏用 `ImageReader` | 主后端 |
| 标注截图 | `android.graphics.Canvas` | 坐标由 `agent-core` 算好，`app` 负责画框和编号 |
| 悬浮窗 | `WindowManager` + `ComposeView` | 需要给 `ComposeView` 设置 Lifecycle、SavedState、ViewModelStore 的 Owner，否则会崩溃 |
| 通知监听 | `NotificationListenerService` | |
| 前台服务 | `Service` / `LifecycleService` | Android 14 必须声明类型：执行任务用 `specialUse`，语音用 `microphone` |
| 日志 | `android.util.Log`，统一封装为 `EncoLog` | `agent-core` 通过注入的日志接口输出，不直接依赖 Android |

## 5. 明确不用

| 不用 | 原因 |
|---|---|
| Hilt / Koin / Dagger | 规模小，手写 `ServiceLocator` 更简单，也省掉注解处理的构建时间 |
| kapt | AGP 9 内置 Kotlin 不支持 |
| Retrofit | 只调一类接口，OkHttp 直接写更透明，流式处理也更好控制 |
| Gson / Moshi | 统一用 kotlinx.serialization |
| RxJava | 统一用协程 + Flow |
| 各厂商的大模型 SDK | 只对接 OpenAI 兼容接口，保持模型无关 |
| 本地向量模型 / 向量数据库 | 安装包太大、耗电；中文检索用 FTS4 + 二元分词 |
| `material-icons-extended` | 体积大且已停止更新；图标用 Material Symbols 导出的 vector drawable 放进 `res/drawable` |
| XML 布局 / Fragment | 界面全部用 Compose |
| Root 方案（Magisk 等） | 比赛评委和普通用户的手机都没有 Root |
| 任何 GPL / AGPL 代码 | 与 Apache-2.0 冲突（见 AGENTS.md 第 8 节） |

## 6. 关键用法约定

### 6.1 网络与模型

- 请求地址：`{baseUrl}/chat/completions`，`baseUrl` 由用户填写（通常以 `/v1` 结尾）。
- 流式输出：用 `okhttp-sse` 的 `EventSource` 读取，收到 `[DONE]` 或连接关闭都视为结束。
- 超时：连接 15 秒；首个 token 60 秒；整次请求可取消（跟随协程取消）。
- 图片：长边缩放到 1280 后用 JPEG（质量 80）编码，以 base64 `data:image/jpeg;base64,...` 发送。
- 用 `mockwebserver3` 为客户端写测试，覆盖：正常流式输出、中途断开、401 错误、429 限流后的重试。

### 6.2 序列化

- 所有跨进程、跨模块、存储和网络的数据类都标 `@Serializable`。
- `Json` 实例统一配置为 `ignoreUnknownKeys = true`、`explicitNulls = false`，放在 `agent-core` 里共用。

### 6.3 Room

- 实体**不要加 `@Parcelize`**。AGP 9 内置 Kotlin + KSP 下有已知问题（google/ksp#3053），需要跨界面传递时另建数据类。
- 打开 Room Gradle 插件的数据库结构导出（`room { schemaDirectory("$projectDir/schemas") }`），导出的 `schemas/` 要提交到仓库。
- FTS4 表与内容表分开；二元分词列在写入时由 Kotlin 代码生成。

### 6.4 Shizuku 与助手进程

- 在清单里注册 `rikka.shizuku.ShizukuProvider`；启动时检查 `Shizuku.pingBinder()`，再用 `Shizuku.checkSelfPermission()` 检查授权，没有就请求授权。
- 助手进程用 `Shizuku.bindUserService(...)` 启动，进程名设为 `:shell`，实现类放在 `device/shell/`，通过 AIDL 接口通信。
- 助手进程启动后**第一件事**就是调用 `HiddenApiBypass.addHiddenApiExemptions("")`，之后才能使用 `UiAutomation`、`DisplayManager` 的隐藏接口。
- 助手进程里不能用 Compose，也不能用依赖 `Application` 的库，只用纯 Java/Kotlin 与系统 API。

### 6.5 并发

- 每个任务运行在独立的 `CoroutineScope` 里，停止任务就取消这个 scope。
- 状态向界面暴露为 `StateFlow`，一次性事件用 `SharedFlow`；不要用 `LiveData`。
- `Dispatchers.IO` 用于网络、数据库、AIDL 调用；不在主线程等待助手进程。

## 7. 模型（测试与演示用）

代码不绑定任何厂商。测试与演示用的模型需要满足：

| 要求 | 原因 |
|---|---|
| 兼容 OpenAI Chat Completions 接口 | enco 只对接这一种接口 |
| **支持图片输入**（视觉模型） | Operator 要看截图 |
| 支持流式输出 | 对话体验 |
| 最好支持 `response_format: json_object` | 动作输出更稳定；不支持也能用，enco 会自行解析并重试 |

国内常见的选择有阿里云百炼（通义千问 VL 系列）、智谱（GLM-4V / AutoGLM 系列）、火山方舟（豆包视觉系列）等，具体模型名称、地址和价格以各服务商文档为准。**API Key 只填在手机的设置页里，不要写进代码、文档或提交记录。**

## 8. 升级与变更规则

- 比赛期间（截止 11/30）**不主动升级依赖**。只有遇到确定由依赖引起的 bug 时，才单独升级那一个，并在提交说明里写明原因。
- 改动 `gradle/libs.versions.toml` 属于共享文件改动，提交前在群里说一声。
- 升级或新增依赖后，同步更新本文件和 `docs/THIRD_PARTY_NOTICES.md`。
