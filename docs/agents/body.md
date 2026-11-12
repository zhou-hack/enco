# 角色说明：乙 · 设备与界面（手脚）

你正在为**乙**工作。乙负责 enco 的“手脚”和“脸”：通过 Shizuku 控制手机，以及用户看到的全部界面。先读完根目录的 [AGENTS.md](../../AGENTS.md)，再读本文件。

## 1. 负责范围

| 模块 | 目录 | 设计文档章节 |
|---|---|---|
| 接口合同（与甲共同维护） | `agent-core/src/main/kotlin/xyz/her3/enco/core/api/` | 7.1 |
| Shizuku 接入与授权 | `app/src/main/java/xyz/her3/enco/device/shizuku/` | 7.2 |
| shell 助手进程 | `app/src/main/java/xyz/her3/enco/device/shell/`、`app/src/main/aidl/xyz/her3/enco/device/shell/` | 7.2 |
| `DeviceController` 实现与舞台 | `app/src/main/java/xyz/her3/enco/device/stage/` | 7.1、7.3 |
| 预览小窗、悬浮球 | `app/src/main/java/xyz/her3/enco/device/overlay/` | 7.3、7.4 |
| 语音 | `app/src/main/java/xyz/her3/enco/device/voice/` | 7.5 |
| 通知监听、前台服务 | `app/src/main/java/xyz/her3/enco/device/service/` | 10 |
| 界面 | `app/src/main/java/xyz/her3/enco/ui/`（`settings/model/` 和 `confirm/` 除外） | 4.1 |
| 字符串 | `app/src/main/res/values/strings.xml` | – |

**不要修改**：`agent-core/` 中 `api/` 以外的目录、`app/.../llm/`、`app/.../memory/`、`app/.../campus/`、`ui/settings/model/`、`ui/confirm/`、`strings_brain.xml`、`eval/`。这些归甲。

## 2. 工作方式

### 2.1 Shizuku 与助手进程

- 用 Shizuku 的 **UserService** 启动助手进程，代码打包在 enco 的 APK 里，以 shell 身份（uid 2000）运行，通过 AIDL 和 App 通信。
- **不使用无障碍服务**。读界面、截图、注入输入都在助手进程里通过 `UiAutomation` 完成：
  - 连接时带 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，不影响用户已经开启的无障碍服务。
  - 需要读取多个窗口时开启“获取交互窗口”的标志，用 `getWindowsOnAllDisplays()` 按屏幕编号取窗口。
  - 注入的输入事件要设置目标屏幕编号，保证点到正确的舞台上。
- `uiautomator dump`、`screencap`、`input` 这些命令只作为兜底，不要作为主要路径（每次要 0.5–2 秒）。
- **隐藏 API 只能在 `device/shell/` 里使用**，App 进程不调用。
- AIDL 接口只开放固定能力（读界面、截图、注入输入、启动 App、管理舞台、给 enco 自己授权），**不要加执行任意命令的方法**。
- 有了 shell 权限后自动授予：悬浮窗（`appops`）、通知读取（`cmd notification allow_listener`）、运行时权限（`pm grant`）、电池优化白名单（`dumpsys deviceidle whitelist`）。

### 2.2 三种舞台（两台测试机都是 Android 14）

| 舞台 | 要点 |
|---|---|
| 主屏 | 第一个实现，后两种都以它为降级目标 |
| 系统小窗 | 以自由窗口模式启动目标 App；读界面只取该窗口，截图按窗口区域裁剪，坐标按窗口换算。原生 Android 14 可能要先开启开发者选项里的自由窗口支持，各家系统的小窗实现不同，**先在两台测试机上确认能不能用** |
| 虚拟屏 | 助手进程创建，尺寸与主屏一致，画面输出到 `ImageReader`；Android 14 上带**独立焦点**标志，不抢主屏输入；同时带受信任、内容随屏销毁等标志 |

- 舞台对甲透明：`Observation` 里的坐标统一按舞台归一化到 0–1000，截图和节点只包含舞台内的内容。
- 选中的舞台失败时（App 拒绝在虚拟屏启动、小窗没生效），通过 `AgentEvent` 通知用户，并自动降级到主屏。
- 输入文字优先用 `ACTION_SET_TEXT`，不依赖输入法，这样虚拟屏里也能输入。

### 2.3 界面

- Jetpack Compose + Material 3，界面文字全部放进 `strings.xml`。
- 界面数据只来自 `AgentEvent` 事件流和 `MemoryStore`，不直接调用甲的内部实现。
- 甲的 Agent 没做好时，用 `debug` 源集里的 `FakeAgentEvents` 按脚本发出假事件，先把时间线、步骤卡片、确认流程做出来。
- 悬浮球和预览小窗用 `TYPE_APPLICATION_OVERLAY`；截图前先隐藏悬浮球，避免截进图里。
- Android 14 的前台服务必须声明类型（执行任务用 `specialUse`，语音用 `microphone`），并在清单里声明对应权限。

### 2.4 和甲的对接点

| 你提供 | 甲怎么用 |
|---|---|
| `DeviceController`（`ShizukuDevice`） | Agent 通过它观察屏幕、执行动作 |
| `Observation`（原始节点 + 截图） | 甲压缩成元素列表 |
| 通知监听采集到的原始通知 | 交给甲的 `campus/` 提取函数 |

| 甲提供 | 你怎么用 |
|---|---|
| `Action` 定义 | 你负责执行 |
| `AgentEvent` 事件流 | 时间线、步骤卡片、悬浮球状态、确认请求 |
| `MemoryStore` | 记忆与技能管理页 |

## 3. 真机调试常用命令

| 目的 | 命令 |
|---|---|
| 用 USB 启动 Shizuku | `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh` |
| 安装 | `.\gradlew.bat :app:installDebug` |
| 日志 | `adb logcat -s enco:* AndroidRuntime:E` |
| 截屏 | `adb exec-out screencap -p > screen.png` |
| 查看所有屏幕（含虚拟屏） | `adb shell dumpsys display` |
| 查看各屏上的任务 | `adb shell dumpsys activity activities` |
| 对照系统导出的控件树 | `adb shell uiautomator dump /sdcard/ui.xml` 后 `adb pull /sdcard/ui.xml` |

改动设备层之后，**必须在真机上实际跑一遍**，用日志和截图确认结果，不要只凭推测说“应该可以”。

## 4. 当前任务

按 [roadmap.md](../roadmap.md) 第 3 节“乙”这一列，找到今天的日期对应的任务。开始前先确认上一个阶段的验收标准是否达成，没达成的优先补上。

## 5. 完成一块工作后

1. `.\gradlew.bat :app:assembleDebug` 通过；改了 `api/` 还要 `.\gradlew.bat :agent-core:test` 通过。
2. 安装到真机验证，主屏、小窗、虚拟屏三种舞台中受影响的都要试。
3. 在 `docs/devlog.md` 追加一条“乙”的记录。
4. 按 AGENTS.md 第 10 节的格式提交。
