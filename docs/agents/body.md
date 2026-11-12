# 模块指南：设备与界面（手脚）

“手脚”和“脸”是 enco 中通过 Shizuku（主）和无障碍服务（备用）控制手机、以及用户看到的全部界面的部分。改动下表里的模块前，先读完根目录的 [AGENTS.md](../../AGENTS.md)，再读本文件。

## 1. 模块范围

| 模块 | 目录 | 设计文档章节 |
|---|---|---|
| 分层接口（与大脑共用） | `agent-core/src/main/kotlin/xyz/her3/enco/core/api/` | 7.1 |
| Shizuku 接入与授权 | `app/src/main/java/xyz/her3/enco/device/shizuku/` | 7.2 |
| shell 助手进程 | `app/src/main/java/xyz/her3/enco/device/shell/`、`app/src/main/aidl/xyz/her3/enco/device/shell/` | 7.2 |
| 无障碍备用后端 | `app/src/main/java/xyz/her3/enco/device/a11y/` | 7.1、7.2 |
| `DeviceController` 实现与舞台 | `app/src/main/java/xyz/her3/enco/device/stage/` | 7.1、7.3 |
| 预览小窗、悬浮球 | `app/src/main/java/xyz/her3/enco/device/overlay/` | 7.3、7.4 |
| 语音 | `app/src/main/java/xyz/her3/enco/device/voice/` | 7.5 |
| 通知监听、前台服务 | `app/src/main/java/xyz/her3/enco/device/service/` | 10 |
| 界面 | `app/src/main/java/xyz/her3/enco/ui/`（`settings/model/` 和 `confirm/` 见 [brain.md](brain.md)） | 4.1 |
| 字符串 | `app/src/main/res/values/strings.xml` | – |

Agent、模型、记忆、校园逻辑见 [brain.md](brain.md)。

**技术选型**：先读 [tech-stack.md](../tech-stack.md) 的第 2 节（构建）、3.2 节（界面）、3.3 节（设备层）、3.5 节（测试）、第 4 节（系统 API）、6.4–6.5 节（Shizuku、助手进程、并发的约定）。

## 2. 工作方式

### 2.1 Shizuku 与助手进程

- 用 Shizuku 的 **UserService** 启动助手进程，代码打包在 enco 的 APK 里，以 shell 身份（uid 2000）运行，通过 AIDL 和 App 通信。
- Shizuku 运行时，读界面、截图、注入输入都在助手进程里通过 `UiAutomation` 完成（无障碍服务只待命）：
  - 连接时带 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，不会挤掉用户已开启的无障碍服务，也不会挤掉 enco 自己的无障碍服务。
  - 需要读取多个窗口时开启“获取交互窗口”的标志，用 `getWindowsOnAllDisplays()` 按屏幕编号取窗口。
  - 注入的输入事件要设置目标屏幕编号，保证点到正确的舞台上。
- `uiautomator dump`、`screencap`、`input` 这些命令只作为兜底，不要作为主要路径（每次要 0.5–2 秒）。
- **隐藏 API 只能在 `device/shell/` 里使用**，App 进程不调用。
- AIDL 接口只开放固定能力（读界面、截图、注入输入、启动 App、管理舞台、给 enco 自己授权），**不要加执行任意命令的方法**。
- 有了 shell 权限后自动授予：悬浮窗（`appops`）、通知读取（`cmd notification allow_listener`）、运行时权限（`pm grant`）、电池优化白名单（`dumpsys deviceidle whitelist`），并自动开启 enco 的无障碍服务（在 `enabled_accessibility_services` 原有列表后追加，不动别的服务）。

### 2.2 无障碍备用后端

- 目的：**Shizuku 没运行时（例如手机刚重启），悬浮球截图和主屏任务仍然可用。**
- `A11yDevice` 同样实现 `DeviceController`，只支持主屏：`takeScreenshot` 截图（enco 自己拿到 `Bitmap`，不调用系统截图）、`getWindows()` 读界面、`dispatchGesture` 手势、`ACTION_SET_TEXT` 输入、`performGlobalAction` 返回和主页。
- 后端选择：Shizuku 可用就用 `ShizukuDevice`，否则用 `A11yDevice`，都不可用就只聊天。**任务进行中不切换后端**；Shizuku 恢复后，下一个任务再切回来。
- 只有无障碍可用时，执行位置选择器里的“小窗”和“虚拟屏”置灰，提示“需要 Shizuku”。
- 开启方式：Shizuku 运行时自动开启，并在服务被系统停掉时自动重开。从没启动过 Shizuku 时，引导页要教用户手动开启：Android 13+ 先在“应用信息”右上角菜单里点“允许受限制的设置”，再去无障碍设置里打开 enco。
- 服务配置里只申请需要的能力（读取窗口内容、截图、执行手势），描述文字写清用途，审核和用户都能看懂。

### 2.3 三种舞台（两台测试机都是 Android 14）

| 舞台 | 要点 |
|---|---|
| 主屏 | 第一个实现，后两种都以它为降级目标 |
| 系统小窗 | 以自由窗口模式启动目标 App；读界面只取该窗口，截图按窗口区域裁剪，坐标按窗口换算。原生 Android 14 可能要先开启开发者选项里的自由窗口支持，各家系统的小窗实现不同，**先在两台测试机上确认能不能用** |
| 虚拟屏 | 助手进程创建，尺寸与主屏一致，画面输出到 `ImageReader`；Android 14 上带**独立焦点**标志，不抢主屏输入；同时带受信任、内容随屏销毁等标志 |

- 舞台对大脑透明：`Observation` 里的坐标统一按舞台归一化到 0–1000，截图和节点只包含舞台内的内容。
- 选中的舞台失败时（App 拒绝在虚拟屏启动、小窗没生效），通过 `AgentEvent` 通知用户，并自动降级到主屏。
- 输入文字优先用 `ACTION_SET_TEXT`，不依赖输入法，这样虚拟屏里也能输入。

### 2.4 界面

- Jetpack Compose + Material 3，界面文字全部放进 `strings.xml`。
- 界面数据只来自 `AgentEvent` 事件流和 `MemoryStore`，不直接调用 Agent 的内部实现。
- Agent 还没做好时，用 `debug` 源集里的 `FakeAgentEvents` 按脚本发出假事件，先把时间线、步骤卡片、确认流程做出来。
- 悬浮球：无障碍服务开启时用 `TYPE_ACCESSIBILITY_OVERLAY`，否则用 `TYPE_APPLICATION_OVERLAY`；预览小窗只在 Shizuku 下出现，用 `TYPE_APPLICATION_OVERLAY`。截图前先隐藏悬浮球，避免截进图里。
- Android 14 的前台服务必须声明类型（执行任务用 `specialUse`，语音用 `microphone`），并在清单里声明对应权限。

### 2.5 和大脑的对接点

| 手脚提供 | 大脑怎么用 |
|---|---|
| `DeviceController`（`ShizukuDevice`） | Agent 通过它观察屏幕、执行动作 |
| `Observation`（原始节点 + 截图） | 压缩成元素列表 |
| 通知监听采集到的原始通知 | 交给 `campus/` 的提取函数 |

| 大脑提供 | 手脚怎么用 |
|---|---|
| `Action` 定义 | 负责执行 |
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

按 [roadmap.md](../roadmap.md) 第 3 节找到今天的日期对应的任务。开始前先确认上一个阶段的验收标准是否达成，没达成的优先补上。

## 5. 完成一块工作后

1. `.\gradlew.bat :app:assembleDebug` 通过；改了 `api/` 还要 `.\gradlew.bat :agent-core:test` 通过。
2. 安装到真机验证，主屏、小窗、虚拟屏三种舞台中受影响的都要试；改动涉及截图或主屏操作时，还要停掉 Shizuku 试一次无障碍后端。
3. 在 `docs/devlog.md` 追加一条记录。
4. 按 AGENTS.md 第 10 节的格式提交。
