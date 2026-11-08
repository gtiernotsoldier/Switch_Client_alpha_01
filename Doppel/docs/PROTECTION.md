# 防护与反逆向路线

> 状态：**设计文档，未实施。** 记录 2026-10 的讨论结论，供后续按顺序执行。
> 所有"现状"条目都指向具体文件，便于核对；所有工作量都是单人估算。

---

## 0. 一句话

> 让逆向的**成本**高于**收益**，让泄露**可追溯**，让复刻**拿不到参数**——而不是追求"不可逆向"。

Java 客户端不存在"防逆向"，只存在"提高成本"。JVM 在执行前必须看到明文字节码，这一条决定了所有方案的上限。

---

## 1. 目标（可度量）

写成可验收的句子，而不是"我觉得很难"：

| 目标 | 验收方式 |
|---|---|
| 反编译产物不可维护 | 交给一个没看过代码的人/AI，30 分钟内说不出"这是什么客户端、有哪几类功能" |
| 仓库泄露不可用 | 别人拿到源码后 `./gradlew build` 失败，或跑起来没有核心功能 |
| 泄露可追溯 | 任何一份流出的构建能定位到构建号/买家 |
| 复刻不等于原版 | 行为可以观察，但参数不在客户端，复刻出来的阈值是错的 |

**不追求**：无法 dump、无法调试、无法观测行为。这三件做不到。

---

## 2. 威胁模型

| 编号 | 攻击者 | 手段 | 主要防线 |
|---|---|---|---|
| **T1** | 静态逆向 | Resource Hacker → Vineflower / AI | L1 提取 + L2 可读性 |
| **T2** | 动态分析 | 调试器、hook、内存 dump | L1 不落盘 + L5 检测 |
| **T3** | 行为复刻 | 抓包对照，不读代码 | L6 参数服务端化 |
| **T4** | 授权破解 | patch 校验、复制 license | L4 签名绑定 |
| **T5** | 泄露 | 仓库、凭据、协作者 | L0 泄露面 |

混淆只对 T1 有效；T3 完全无效；T4 靠签名；T5 靠纪律。

---

## 3. 现状（事实，不是猜测）

| # | 事实 | 位置 | 后果 |
|---|---|---|---|
| 1 | `agent.jar` 以**明文 RCDATA 101** 嵌入 exe | `injector/build.rs` | Resource Hacker 十秒导出完整 jar |
| 2 | 解出的 jar / dll / hud.exe **写在 `%TEMP%` 且不删除** | `injector/src/resource.rs` | 运行一次就能捡走 |
| 3 | 构建**没有混淆**，Kotlin `@Metadata` 完整保留 | `agent/build.gradle.kts` | 反编译接近源码 |
| 4 | WebUI 绑 `0.0.0.0`，且 token 写进 `%TEMP%\doppel-payload.log` | `WebUIServer.kt` | 同局域网可控制客户端 |
| 5 | 打包的 ASM 9.7 被 Forge 的 `asm-all-5.0.3` 遮蔽 | `Transformer.java` 注释 | 实际用 ASM5 API，功能受限 |
| 6 | `AgentBridge` 用 `Class.forName("$base.$pkg.$name")` 注册模块 | `AgentBridge.kt:126` | 混淆后模块全部注册失败 |
| 7 | `ModuleOptions` 用 `getDeclaredMethod("getX")` 读选项 | `ModuleOptions.kt:57` | 混淆后 WebUI 选项为空 |
| 8 | `Delegates.resolveModulePrefix` 反射 `getName` | `Delegates.kt:412` | 混淆后配置键变化，用户设置丢失 |
| 9 | `data class` 的 `toString` 带全部字段名 | 所有 model | 字符串加密的主要目标之一 |
| 10 | `require(...) { "SprintVelocityStrategy expects ..." }` | 多处 | 直接把类名写进常量池 |

第 1、2 条是**当前最严重的**：它们让后面所有工作都失去意义。

---

## 4. 分层

防护按"挡住哪一步"分层，互不替代。

### L0 泄露面（成本最低，收益最大）

- 私有仓库、最小权限 token、不进 CI 日志
- 构建产物校验：jar 里出现 `.java` / `.kt` / `.git/` / `.env` / `*.pem` 直接失败
- `%TEMP%` 日志里不放 token

**真实世界的"源码被开源"，多数止于这一层。**

### L1 提取（断掉"十秒拿源码"）

三档，按强度递增：

| 档 | 做法 | 攻击者需要 |
|---|---|---|
| 1-A | 资源 AES/XChaCha 加密，密钥拆分 | 逆向 DLL 找密钥 |
| 1-B | 解密后写 `FILE_FLAG_DELETE_ON_CLOSE` 临时文件，`loadAgent` 返回即关句柄 | 在注入的 ~200ms 窗口内 hook API |
| 1-C | 不落盘：`JNIEnv::DefineClass` 从内存定义类 | 内存 dump |

### L2 可读性（断掉"拿到就能读"）

顺序不能乱：

```
0. 剥元数据     @Metadata / SourceFile / LineNumberTable / 参数名
1. 改名 + repackage + 类合并
2. 字符串加密   按调用点派生 key
3. 数字混淆     常量 → 算术
4. 语义打散     方法拆分 + 状态外置（自定义 pass，市面没有现成工具）
5. 控制流平坦化 基本块 + 哈希状态 + 不透明谓词
6. 诱饵         假模块，共享部分真路径
```

### L3 结构（胶水化）

目标：**jar 里只剩 JVM 要求的东西、薄桩转发。**

见 §9 的分层审计。核心是把 `core/`（7.1k 行，零反射）搬到 **Rust 独立进程**，`adapter/` 保持 Kotlin，游戏侧只留不含业务的编组薄桩（设计见 9.5）。

### L4 授权

- 客户端只放**公钥**，不放任何"能通过验证的东西"
- 离线激活：机器指纹 → 用户发短码 → 你用私钥签 license → 客户端验签 + 用 `KDF(指纹)` 解载荷
- 复制 license 到另一台机器解不开（指纹不同）
- 更新包也要签名；签不过就不更新

### L5 可追溯

- 每个构建嵌唯一水印（常量 / 资源 / 默认值）
- 可选：注入器检测调试器/分析工具，评分上报，只做"记录 + 拒绝注入"，不对分析者机器做任何事

### L6 参数服务端化

- 客户端只有算法框架，各反作弊的阈值组合由服务端下发
- 这是唯一"泄露了也不亏"的结构，也是对抗 T3 的唯一手段

---

## 5. 关键方案

### 5.1 资源加密 + 阅后即焚

```rust
// 伪代码
let key = derive(machine_guid, build_salt);
let plain = xchacha20poly1305_decrypt(RCDATA_101, key)?;
let path = temp_path("agent.jar");
let h = CreateFileW(path, GENERIC_WRITE, 0, null,
                    CREATE_ALWAYS,
                    FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, null);
write(h, &plain);
// 句柄保持打开，loadAgent 读完立刻 CloseHandle → 文件消失
load_agent(&path);
CloseHandle(h);
```

要点：**不要 `fs::write` 完就走**——那正是现在的做法（`resource.rs`）。

### 5.2 不落盘：`DefineClass`

```cpp
jclass c = env->DefineClass("io/doppel/agent/Agent",
                            gameClassLoader, bytes, len);
```

- 类字节从 DLL 里的加密 blob 解密
- **父类必须先于子类定义**；否则需要自定义 ClassLoader，在 `loadClass` 时回调 DLL 解密
- 资源（`mappings/*.json`）也要一并处理，否则它会变成"唯一还需要落盘的东西"

### 5.3 JVMTI 与 ASM 的分工（常见误解）

| | 作用 |
|---|---|
| JVMTI | 提供"类加载 / 重定义时叫到你"的**钩子机制** |
| ASM | 提供"把 `byte[]` 改成另一个 `byte[]`"的**编辑器** |

**JVMTI 不能替代 ASM**。它给的是原始字节，你仍然需要一个编辑器。
JVMTI 能替代的是 `agentmain` + `Instrumentation` + AttachListener。

### 5.4 ASM 重定位（1 小时，别重写）

问题：Forge 1.8.9 的 `asm-all-5.0.3` 在 classpath 上排在 agent.jar 之前，遮蔽了打包的 9.7。

解法：

```
org.objectweb.asm.**  →  io.doppel.shaded.asm.**
```

- Gradle shadow 的 `relocate`，或自己那个 ASM pass 顺手做
- 之后可用完整 ASM9 API，jar 只多 ~200KB
- **比把 697 行 `Transformer` 重写成 native 划算得多**

### 5.5 离线授权（无运行时服务器）

```
① 客户端算机器指纹 F = hash(MachineGuid + 主板UUID + 系统盘序列号)
② 显示短码（F 的可读形式）
③ 用户发给你 → 你用私钥签 {enc(build_key, KDF(F)), signature}
④ 客户端：验签 + 用 KDF(F) 解出 build_key
⑤ 指纹不匹配 → 解出来是乱码 → 拒绝注入
```

每份构建可以带不同 `buyer_id`（发卡时生成），泄露即可定位。
**不要**写 `if (license.valid) enable()`——那是改一条跳转的事。

### 5.6 分析环境检测（可选，低优先级）

只做三件事，其余不做：

1. `IsDebuggerPresent` / `CheckRemoteDebuggerPresent` / PEB
2. `GetThreadContext` 查 Dr0–Dr3 硬件断点
3. 进程/窗口标题匹配（x64dbg / IDA / Ghidra / Process Hacker）

**必须打分，不能单条触发**——用户开着 Cheat Engine 玩别的游戏是正常情况。
建议：`<40` 只记录，`40–70` 加延迟，`>70` 拒绝注入。

**只做"记录 + 拒绝"，不对分析者机器做任何事。**

---

## 6. 执行顺序

| 阶段 | 内容 | 工作量 | 前置 |
|---|---|---|---|
| **P0** | 清理 `%TEMP%` 残留、token 不入日志、WebUI 默认 loopback、jar 内容校验 | 1 天 | 无 |
| **P1** | 资源加密 + 阅后即焚 | 1–2 天 | P0 |
| **P2** | 剥 `@Metadata` + 关参数断言 + `-g:none` | 1 天 | 无 |
| **P3** | 改掉三处自反射（`Class.forName` / `getDeclaredMethod` / `getName`），换成生成式注册表 | 1 天 | 无 |
| **P4** | R8 激进配置（repackage + merge + overload），跑通全部模块与 WebUI | 2 天 | P3 |
| **P5** | 字符串 + 数字加密（自定义 ASM pass） | 3–5 天 | P4 |
| **P6** | `core/strategy` + `core/algorithm` 搬 Rust **独立进程**（IPC 边界，设计见 9.5） | 4–6 周 | 无 |
| **P7** | 水印 + 每份构建独立密钥 | 半天 | P4 |
| **P8** | 离线授权（私钥签名 + 指纹绑定） | 3–5 天 | P7 |
| **P9** | 参数服务端化 | 1–2 周 | P8 |

**P0–P1 不做，后面全部没有意义。** P6 是"结构缺失"的分水岭。

---

## 7. 验收

### 7.1 30 分钟锚点测试

> 拿 release 包 → 反编译 → 交给一个没看过代码的人或 AI → 计时 30 分钟。
> 说不出"这是 Minecraft 客户端、大概有哪几类功能" → 通过。

每次改混淆配置都重跑，这是唯一诚实的指标。

### 7.2 自动化检查

- `./gradlew :obfuscator:obfuscateAgent` 后，`strings` 搜不到模块名/日志文案
- 反编译产物里 `@Metadata` 数量为 0
- jar 内容校验：无源码、无凭据
- 混淆前后跑 346 个单测，全绿

---

## 8. 明确不做的

| 不做 | 为什么 |
|---|---|
| native 壳 / VMProtect | Rust 部分不值钱；加了只会让发版天天崩 |
| 反调试军备竞赛 | 同进程的检查一定能被 patch |
| 全 native 重写客户端 | 3–6 个月，前三个月一直崩；收益只有"没有 jar" |
| 客户端内 prompt injection | 不可靠，且暴露"作者在耍花招" |
| 对分析者机器做任何事 | 那是攻击，不是防护 |

---

## 9. 附录：语言与分层审计

### 9.1 代码分布

| 层 | 行数 | 反射 | 结论 |
|---|---|---|---|
| `core/` | 7,105 | **0** | 全部可搬 Rust |
| `adapter/common` | 17,832 | 有 | 大部分留 Kotlin（胶水/管道） |
| `agent` | 1,818 | 有 | 入口削薄，Transformer 保留但用重定位 ASM |

`core/` 内部：

| 包 | 行数 | 处理 |
|---|---|---|
| `strategy` | 2,936 | 搬 Rust |
| `algorithm` | 1,021 | 搬 Rust |
| `option` / `condition` / `safety` | 735 | 搬 Rust |
| `model` / `util` | 469 | 搬 Rust |
| `logging` | 478 | 留 Kotlin |

### 9.2 什么必须留在 JVM

只有三样：

1. `agentmain(String, Instrumentation)` —— JVM 按名字调，签名固定
2. `ClassFileTransformer.transform(...)` —— 接口是 Java 的
3. `CoreStub` —— IPC 薄桩：快照编组、决策解组、core 进程监督。只有数据，没有业务

### 9.3 语言能力矩阵

| 层 | Java/Kotlin | C++/Rust | 说明 |
|---|---|---|---|
| 注入 | Java agent | DLL + `LoadLibrary` | 都可 |
| 拿 `Instrumentation` | `agentmain` | JVMTI `Agent_OnAttach` | 后者更底层 |
| 读游戏状态 | 反射（留在 JVM 内） | —— | 独立进程方案里 core **永不**碰游戏内存，映射表不用补描述符列 |
| 改字节码 | ASM | 自己写编辑器 | ASM 仍是最优 |
| 渲染 | LWJGL 直调 | hook GL / 独立 overlay | native 很痛 |
| 业务逻辑 | 任意 | 任意 | IPC 边界成本，实测往返 ~10µs（见 9.5） |

### 9.4 目标形态

```
游戏 JVM（agent.jar）
├─ Agent.java            入口、Attach、ASM 变换（重定位 ASM）
├─ Transformer.java      transform() → 改名 / 字符串加密（P2–P5）
├─ adapter（瘦身）        读游戏状态、应用动作——只有管道，没有策略
└─ CoreStub.kt           快照编组 → IPC → 决策解组 → 监督 core 进程

core 进程（doppel-core.exe，Rust；游戏内存与注入器里都没有它）
├─ strategy / algorithm / option / condition / safety / model
├─ 授权验签（P8）
├─ 会话状态：策略 FSM、噪声 RNG、调度放行决策
├─ WebUI 服务：配置面从游戏进程整体迁出（今天它住在 adapter 里）
└─ --replay 模式：金测回放器

注入器                    只负责注入 agent、拉起 core 与 overlay，不携带 IP
外部 UI / HUD             ClickGUI 走外部 overlay，HUD 读 state 文件——均不携带 IP
```

逆向者 dump 游戏进程：策略和算法不在里面；打开 jar：只有编组和转发。要看懂这套客户端，只能去逆那个独立 exe。

---

### 9.5 P6 v2：core 独立进程（2026-11 决定）

> **实施状态（2026-11-07 完成）**：垂直切片已落地并通过测试 —— wire v1 编解码、
> TCP loopback 传输、握手与种子协商、会话状态驻留 core 侧、velocity 三种模式 +
> KnockbackDelay、条件引擎全量移植、1 ms deadline 降级、panic 兜底、
> Kotlin↔Rust 2000 tick 位级对拍、500 tick 进程级端到端、真实链路延迟基准。
> 字节表与运行手册见 **[IPC-PROTOCOL.md](IPC-PROTOCOL.md)**。
>
> **全部模块已移植完毕**：aim / click / combat / movement / reach / autoblock / tap /
> velocity 均改走 `ipc/Strategies.kt`；2026-11-08 又补上最后 6 个把完整逻辑留在 jar 里的模块
> （Eagle / Backtrack / KnockbackDisplace / Reach / ClickAssist / HitSelect）。它们的窗口状态、
> 门顺序和随机数流全部迁到 `Module::Player`（id 8），adapter 侧只剩编组与平台副作用；
> 六组 Kotlin↔Rust golden 对拍共 2100 行。adapter 侧不再出现任何 `io.doppel.core` 引用，
> `agent.jar` 内 `io/doppel/core/` 类数为 **0**（CI 有守卫，回退会直接失败）。
>
> **HitSelect 是唯一跨线程的一个**：tick 侧喂状态、render 侧在点击时问结果，两个 op 共用
> 一份 core 状态。为避免每帧一次往返、以及让概率掷骰被帧率驱动，forge 层只在物理攻击
> 成立时才问 gate；core 没回答时一律**放行**点击（吞掉真实一击比放过去更糟），与 Reach
> 的「没回答就不扩展」方向相反。
>
> 代价是必然的：**没有 `doppel-core` 进程在跑时，这些模块一动不动**——不是降级，
> 是完全不动作。启动顺序因此变成「先 core，后游戏」，injector 负责拉起 core。
>
> **9.4 的「只有编组和转发」尚未完全兑现**：jar 里仍留着 `contract/util/Geometry` 与
> `PortableMath`（帧级纯函数，走 IPC 会破坏瞄准手感）、Eagle 的 autoPlace 放方块时序，
> 以及 9 个模块里共 **24 处** `kotlin.random` 时长/概率抖动（STap / WTap / ADTap /
> BlockHit / AutoBlock 等）。它们不是 `io.doppel.core` 的类，但反编译仍可读；是否继续
> 外移待定。

P6 从「进程内 JNI（DLL 随 agent 加载进游戏进程）」改为「**独立 core 进程 + 游戏侧薄桩**」。
决定的三条理由，按重要性排：

1. **崩溃隔离**：进程内 native 崩 = 游戏崩；独立进程崩 = 该 tick 模块降级 + 重启 core，游戏无感。
2. **延迟实测不敏感**：一次 IPC 往返 10µs 级，占 50ms tick 预算的 0.02%（见 9.5.1）——「core 放哪」不影响执行时机，真正决定执行延迟的是游戏 tick 本身。
3. **游戏内存干净**：dump 游戏进程看不到任何策略 / 算法 / 阈值（附带收益，不是主要动机）。

#### 9.5.1 通讯延迟实测

同主机两进程一次往返，256 B（≈一 tick 状态快照），2 万次采样
（复现脚本 `scripts/ipc_latency_bench.c`，Linux 2 核沙箱；Windows named pipe 同一量级）：

| 通道 | p50 | p99 | p999 |
|---|---|---|---|
| 共享内存 + 自旋 | 0.43 µs | 0.53 µs | 3.2 µs |
| 本机 socket | 9.5 µs | 14.9 µs | 21.2 µs |
| 匿名管道 | 7.9 µs | 12.8 µs | 18.4 µs |

对照 50ms tick 预算：

- RTT p50 = 0.019%，p999 = 0.042%
- 全链路（编组 5µs + RTT p999 + 单次决策按 100µs 算）≈ **0.13ms = 0.25%**
- 20 TPS 下每秒 IPC 花费 0.19ms ≈ 单核的 0.019%
- 240fps 的帧预算 4.17ms，往返占 0.23%——帧级也够，但帧级走 9.5.2 的轨迹计划，不需要每帧通讯

落地选择：**先 named pipe**（Windows 本机 socket 等价物），简单够用；真要抠延迟再换共享内存双缓冲，消息边界不变，协议不用改。

> **实施偏差（2026-11-07）**：实际先落了 **TCP loopback**，不是 named pipe。
> 原因在 JVM 侧：客户端跑 Java 8，既没有 `UnixDomainSocketAddress`，开 Win32 管道
> 又必须引入 JNA（约 1.5 MB + native 解包）。实测 loopback TCP p50 6.70 µs /
> p99 12.50 µs，与管道同量级，1 ms deadline 仍有余量 80 倍。协议与传输解耦，
> 将来换管道只动 `transport.rs` 一个文件，不用改协议版本。详见 IPC-PROTOCOL.md §1。

#### 9.5.2 tick 时序（执行延迟 = 0 个额外 tick）

```
tick 边界（50ms 一次）
① 薄桩拍快照：实体 / 自身 / 旋转 / 包事件 → 定长结构（几百字节）
② 发送 → core 策略 FSM + 算法（µs 级）→ 决策返回    ← 同一个 tick 内完成
③ 薄桩解组，经现有 adapter 路径应用
```

决策在**当 tick 内**返回并应用——生效时机与进程内方案完全相同。
三种通讯类型，不要求每次都同步等返回：

| 类型 | 例子 | 往返 |
|---|---|---|
| 同步决策 | 瞄准方向、velocity 修改/取消 | 当 tick 往返一次 |
| 异步通知 | PacketScheduler 放行决策 | 无往返 |
| 帧级计划 | 每 tick 返回整条旋转插值曲线 | 每 tick 一次，帧间零通讯 |

帧级平滑走**轨迹计划**：`RotationCalculator.interpolate` 是纯函数，薄桩拿一条曲线本地逐帧执行，帧与帧之间不通讯。

#### 9.5.3 deadline 降级纪律

协议按**延迟**设计，不按吞吐：

- 每个同步决策带 deadline（1ms ≈ p99 的 67 倍）
- 超时没回 → **本拍沿用上一拍命令**（对修改类动作即跳过本拍），游戏永远不等 core
- core 进程死了 → 薄桩重启（exe 冷启动 ~10ms 量级），期间全部降级；tick 快照本身就是心跳
- 崩溃纪律不变：每个入口 `catch_unwind` → 错误码 → 模块降级；进程模型多一层兜底

最坏情况只是丢一拍，不存在「卡住等待」这个状态。

#### 9.5.4 边界与状态

JNI 三分类在进程模型下更简单——**所有决策状态都长驻 core 进程**：

| 分类 | 处理 |
|---|---|
| 纯函数（rotateInterp / withinFov3D / VectorOperations） | 搬 core，结果进输出曲线 |
| 有状态噪声（GaussianNoise / NoiseProvider 的 RNG） | 状态整体住 core 进程，不过边界 |
| 永不过界（游戏对象 / 字符串 / Map / FSM 胶水） | 留薄桩侧，或变成 wire 数据 |

唯一非纯点不变：`core/network` PacketScheduler 队列存 `Any`（JVM 对象跨 tick）→ 队列留 Kotlin，只有放行决策过边界。连带一条：`PlatformCommand.CancelPacket(handle: Any)` 携带 JVM 对象引用，跨进程要改成**抽象 packet id**（版本化动词表），由薄桩侧调度器解析回真实包。

旧 P6 的「mappings 补描述符列」**取消**：core 永不读游戏内存，读状态是薄桩（JVM 内、按现有 mappings）；边界是自定义定长 wire 格式，不是 JNI 描述符。

#### 9.5.5 切换标准（不变）

金测回放：Kotlin 录（输入序列 + 种子 → 决策序列），core `--replay` 放同一份输入，diff 为零才切换。
决策只依赖（快照，种子，内部状态），与传输无关——IPC 不引入非确定性，f32 同序同结果。

#### 9.5.6 工作量

| 步骤 | 估时 | 状态 |
|---|---|---|
| wire 协议 + 薄桩编组/解组 + 进程监督 | 2–3 天 | **已完成** |
| core 进程骨架（生命周期 / 握手 / --replay） | 2–3 天 | 骨架与握手已完成；`--replay` 待做 |
| 策略 + 算法移植（主体） | 3–4 周 | velocity 已完成，其余模块待做 |
| 金测回放基建 + 对拍清零 | 1 周 | 夹具与对拍已完成（合成数据，真实录像待接） |
| **合计** | **4–6 周**（与 v1 相当，省掉 JNI 描述符基建） | 约完成 1/4，模式已确立 |

## 10. 一句话总结

> **搬算法进独立进程，不搬管道；留 ASM，只把它改名；先断提取，再谈混淆；参数永远不在客户端。**
