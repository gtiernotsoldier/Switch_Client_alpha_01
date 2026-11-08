# 防护与反逆向路线

> 状态：**部分已实施。** 2026-10 立项；P6（core 独立进程 + IPC）于 2026-11 落地，其余阶段状态见 §6 与 §9.5。
> **目标形态以 §9.4 为准（2026-11-08 定稿）**；§3 的"现状"是 2026-10 快照，仅供追溯，不代表当前实现。
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

目标：**jar 里的 Kotlin 只做逻辑判断、编组与平台副作用；计算、算法、策略与跨 tick 状态全部在外部进程。**

见 §9 的分层审计与 §9.4 定稿形状。`core/`（7.1k 行，零反射）搬到 **Rust 独立进程**，由 injector 拉起；`adapter/` 保持 Kotlin，游戏侧只留逻辑判断与编组。"只有编组和转发"是早期口号，已弃用——它把必须留在 JVM 的逻辑判断也一起否掉了。

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

> **进度（2026-11-08）**：P6 的算法迁移与 core 启动权归 injector **均已完成**，形状见 9.4；P0–P5 / P7–P9 仍按上表顺序执行。
> P6 尚余一项：外部 overlay 的 offline 显示（判定依据已在 injector 侧，显示层不进 Kotlin）。

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

### 9.1 代码分布（2026-10 快照，仅供追溯）

| 层 | 行数 | 反射 | 当时的结论 |
|---|---|---|---|
| `core/` | 7,105 | **0** | 全部可搬 Rust |
| `adapter/common` | 17,832 | 有 | 大部分留 Kotlin（胶水/管道） |
| `agent` | 1,818 | 有 | 入口削薄，Transformer 保留但用重定位 ASM |

`core/` 内部：

| 包 | 行数 | 当时的处理 |
|---|---|---|
| `strategy` | 2,936 | 搬 Rust |
| `algorithm` | 1,021 | 搬 Rust |
| `option` / `condition` / `safety` | 735 | 搬 Rust |
| `model` / `util` | 469 | 搬 Rust |
| `logging` | 478 | 留 Kotlin |

> **实施后（2026-11-08）**：策略与算法已迁到 `core-rs/src/strategy/`；JVM 侧 `core/` 仍有
> 65 个 kt 文件，但用途变了——它们是 `ParityTest` 的 **Kotlin oracle**（对拍基准）与
> `PacketScheduler.kt`（保留作回滚用），**不再被 adapter 引用**，也不进 `agent.jar`。
> `agent.jar` 内 `io/doppel/core/` 类数为 **0**（CI 守卫）。此表是迁移前的统计，别按行数判断达标。

### 9.2 什么必须留在 JVM

早期写「只有三样」，那是进程内 JNI 设想的残留，独立进程模型下不成立。按 9.4 定稿，留在 JVM 的是**平台相关的一层**，且全部不含算法：

1. `agentmain(String, Instrumentation)` / `ClassFileTransformer.transform(...)` —— JVM 按名字调，签名固定
2. `CoreStub` / `ipc/Strategies` —— wire 编组、决策解组、会话连接（逻辑判断 + 编组）
3. `Module` / `ModuleRegistry` / `ConfigManager` / WebUI / `EventBridge` —— 读游戏状态、选项、事件注册
4. 游戏内 `OverlayRenderer` 与 `HudStateWriter` —— 画兼容层 HUD、写 state 文件（平台副作用）
5. `Geometry` / `PortableMath` —— 帧级纯函数，**正式豁免**（见 9.4）

**不在 JVM**：计算、算法、策略、随机数、时间窗口、跨 tick 状态。CI 守卫只校验「`io/doppel/core/` 类数为 0」，即按包名而非按语义——换包名能绕过，白名单之外残留的 `kotlin.random` 守卫也抓不到。

### 9.3 语言能力矩阵

| 层 | Java/Kotlin | C++/Rust | 说明 |
|---|---|---|---|
| 注入 | Java agent | DLL + `LoadLibrary` | 都可 |
| 拿 `Instrumentation` | `agentmain` | JVMTI `Agent_OnAttach` | 后者更底层 |
| 读游戏状态 | 反射（留在 JVM 内） | —— | 独立进程方案里 core **永不**碰游戏内存，映射表不用补描述符列 |
| 改字节码 | ASM | 自己写编辑器 | ASM 仍是最优 |
| 渲染 | LWJGL 直调（游戏内兼容层） | 外部 overlay 进程 | 正式 HUD 是外部 Rust overlay |
| **逻辑判断 / 编组 / 平台副作用** | **只在 Kotlin** | —— | 平台 API 只在 JVM 侧可得（9.4） |
| **策略 / 算法 / 有状态噪声 / 跨 tick 状态** | 不留 | **只在 Rust core** | 决策状态整体住 core 进程，不过边界 |

> 旧表写「业务逻辑：任意 / 任意」，是进程内时代「放哪都行」的说法，与 9.4「策略全进 core」矛盾，此处更正。

### 9.4 目标形态（2026-11-08 定稿）

一句话：**Kotlin 里只有逻辑判断；计算、算法、策略、随机数、时间窗口与跨 tick 状态全部在外部进程。**

```
injector（编排者）
 ├─ 拉起 core（算法进程）  ← 启动权在这里，不在游戏 JVM
 └─ 拉起 overlay（外部 HUD）
        │
        │  进程状态与会话状态在外部进程侧
        │
游戏 JVM / agent.jar ─── wire（双向，Kotlin ↔ core）─── core（doppel-core）
  Kotlin 只做三件事：                                    ├─ 策略 / 算法 / 计算
  ① 逻辑判断  enabled / 条件 / 分支                     ├─ 跨 tick 状态、时间窗口
  ② 编组      收状态→打包→发；收决策→拆包→落地           ├─ 每模块独立 RNG stream
  ③ 平台副作用 按键、攻击脉冲、事件注册、游戏内渲染        └─ 配置副本（由 Kotlin 下发）
  不在 Kotlin：计算、算法、策略、随机数、时间窗口、跨 tick 状态
```

#### 状态归谁

| 状态 | 归属 | 说明 |
|---|---|---|
| 进程生存状态（谁活着、endpoint、pid） | injector | 只有拉起它的人才在外部看得见 |
| 会话状态（seed、generation、连接存根） | core | Kotlin 不生成 seed、不持有进程生死 |
| 决策状态（策略 FSM、窗口、RNG、配置副本） | core | 每模块独立 RNG stream，见 IPC-PROTOCOL §9 |
| 模块开关 / 选项值 | Kotlin 读、打包下发 | 配置是**输入**；存在哪、UI 在哪是另一个议题，不属本形状 |
| 显示状态 | 外部 overlay 进程 | **外部 HUD 才是正式的**；游戏内那套是迁就模块的兼容层，不删 |

#### offline 是什么

offline **不是 HUD 功能，也不进 Kotlin**。它是外部进程侧对「这条 wire 有没有建立」的观察：没建立 → 显示，建立了 → 不显示。不进 `HUD.kt`、不改 state 文件契约。

把 core 启动权交给 injector 的理由正是这个：现在 JVM 自己拉起 core、自己连自己，外部无从观察沟通是否成立；由 injector 拉起后，沟通链路的起点在外部，外部进程能直接看到它。

#### 三条明确边界

- **几何正式豁免**：`contract/util/Geometry` 与 `contract/math/PortableMath` 留在 JVM。理由不是"走 IPC 会破坏手感"（9.5.1 实测帧预算够用），而是不为每帧通讯付架构成本；它们是对已有数字的算术，不含策略。白名单外的纯函数一律在 core。
- **逻辑判断的判据**：留在 Kotlin 的 `if` 只能是确定性布尔分支 + 编组 + 平台副作用 + 降级方向；带 `Random`、时间窗口、跨 tick 可变字段的判断不合规（残留见 9.5）。
- **明确不做**：配置面迁出、seed 归 injector、删除游戏内 HUD——这三项都不在本形状内。配置值作为输入被打包属于编组；`core-rs/src/main.rs` 已有 `seed.unwrap_or_else(now_nanos)`，`hello_ack` 把 seed 回传给 Kotlin，协议一个字节不用改。

#### 落地状态

| 件 | 状态 |
|---|---|
| 计算 / 算法 / 策略 / RNG / 窗口搬 core | **已落地**（2026-11-08，六模块收尾 + Eagle autoPlace 时序，CI 守卫 `io/doppel/core/` 类数为 0） |
| Kotlin 只留判断与编组 | **六模块 + Eagle autoPlace 达成**：新增的 `PLAYER_EAGLE_PLACE` 把 aim 状态机、等待计数、冷却与随机抽样搬进 core，模块只剩门判断、几何容差判断、编组、`when(step)` 落地与平台副作用。其余 10 处 `kotlin.random` 残留见 9.5（显式白名单，本轮不处理） |
| core 启动权归 injector | **已落地**（2026-11-08）：injector 用 `--port-file` 拉起 core，endpoint 经 `doppel-config.properties` → `Agent.coreInit` → `CoreSupervisor.attach()`；JVM 侧删掉 `locate()` 的隐式扫描与 `ProcessBuilder` 生产路径，`doppel.core.path` 仅作开发/测试回退 |
| 外部算法进程 ↔ Kotlin 的 wire 沟通 | **已验证**：`CoreSupervisorTest` 模拟 injector 起 core、发布 endpoint、断言 JVM 不 spawn 且一次真实决策往返成功 |
| offline 作为外部观察点 | **判定依据已就位、显示未做**：injector 是唯一知道「core 有没有被拉起」的实体，`--port-file` 即就绪信号；外部 overlay 据此判断显示与否。不进 Kotlin、不进 `HUD.kt`、不改 state 文件契约 |
| WebUI / 授权验签 / `--replay` 进 core 进程 | **设计中，未实施**（旧图把它们画成现状，此处更正） |

逆向者 dump 游戏进程：策略和算法不在里面；打开 jar：只有逻辑判断与编组。§0 的立场不变——不追求无法 dump，只提高成本；jar 里的 `CoreStub` / `Strategies` / wire 表仍会暴露「存在外部算法进程」这一事实，本形状不以此为收益。

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
> 门顺序和随机数流全部迁到 `Module::Player`（id 8），adapter 侧只剩**逻辑判断、编组与平台副作用**；
> 六组 Kotlin↔Rust golden 对拍共 2100 行。2026-11-08 又收尾了 Eagle 的 AutoPlace 时序
> （aim 状态机 / 等待计数 / 冷却与随机抽样 → `strategy::eagle_place`，第七组 320 tick 对拍，
> 真实 socket 有 E2E 覆盖），Eagle 遂成为「模块里只有逻辑判断」的样板。adapter 侧不再出现任何
> `io.doppel.core` 引用，`agent.jar` 内 `io/doppel/core/` 类数为 **0**（CI 有守卫，回退会直接失败）。
>
> **HitSelect 是唯一跨线程的一个**：tick 侧喂状态、render 侧在点击时问结果，两个 op 共用
> 一份 core 状态。为避免每帧一次往返、以及让概率掷骰被帧率驱动，forge 层只在物理攻击
> 成立时才问 gate；core 没回答时一律**放行**点击（吞掉真实一击比放过去更糟），与 Reach
> 的「没回答就不扩展」方向相反。
>
> **可见性说明（2026-11-08 定稿）**：没有 `doppel-core` 进程时，这些模块一动不动——
> 这是 `Strategies` 里 `OfflineAim` / `OfflineClick` 被写成 no-op 的**主动选择**，不是物理定律。
> 行为不降级，但可见性要降级：offline 由**外部进程**看 wire 是否建立来判断，没建立才显示，
> 建立了就不显示；它不进 Kotlin、不进 `HUD.kt`、不改 state 文件契约（见 9.4）。
>
> **与 9.4 定稿形状的差距（2026-11-08 核对）**：
>
> | 件 | 2026-11-08 之后 | 目标（9.4） |
> |---|---|---|
> | core 启动权 | **injector 拉起**（`resource::launch_core()` + `--port-file`），endpoint 经 `doppel-config.properties` 交给 `CoreSupervisor.attach()`；JVM 不再 `ProcessBuilder`，`doppel.core.path` 仅开发/测试回退 | 达成 |
> | 状态沟通 | 编排权与进程状态归 injector；injector→JVM 的 config 通道承载 endpoint，JVM↔core 双向 wire 承载决策 | 达成最小闭环；overlay 侧的 offline 显示仍待做 |
> | 几何 | `contract/util/Geometry` + `PortableMath` 留在 jar | **正式豁免**，留在 jar（白名单，见 9.4） |
> | 残留抖动 | 10 个文件里仍有 `kotlin.random`（ADTap / WTap / STap / BlockHit / AutoBlock / SuperKnockback / JumpReset / SprintReset / ForgeEventBridge / ConfigManager） | 按 9.4 判据不合规，但各自带 RNG 流、搬动会碰 `--replay` 对拍，**显式白名单 + 到期复评**，本轮不处理 |
> | Eagle autoPlace 时序 | **已迁移（2026-11-08）**：aim-in-flight / 等待计数 / 冷却与随机抽样全部进 `strategy::eagle_place`（`PLAYER_EAGLE_PLACE` = 7，独立 `rng_eagle_place` 流）；模块只剩门判断 `shouldAutoPlace`、几何容差判断 `pitchInTolerance`、编组、`when(step)` 落地与平台副作用 | 达成；`EaglePlaceParityTest` 320 tick 对拍，真实 socket 往返有 E2E 覆盖 |
>
> 注：`agent.jar` 内的 `CoreStub` / `Strategies` / wire 表本来就会暴露「存在外部算法进程」，
> 所以把 offline 从 jar 里挪出去**不是防护增强**，只是架构归位与视觉约束。

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
- 240fps 的帧预算 4.17ms，往返占 0.23%——**延迟上帧级也够**；不每帧通讯是架构选择（几何留 JVM，见 9.4），不是性能不可能。9.5.2 的「轨迹计划」尚未实施

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

> **状态修正（2026-11-08）**：上面第三行「帧级计划 / 轨迹计划」是**设计中的方案，未实施**，
> Done 清单里没有它。按 9.4 定稿，`Geometry` / `PortableMath` 正式豁免、帧级几何留在 JVM，
> 不存在「每 tick 下发一条曲线」这条链路。9.5.1 那句「帧级也够」只说明延迟预算够，
> 不构成「已经这么做」；保留几何是**不想每帧通讯的架构选择**，不是性能不可能。

#### 9.5.3 deadline 降级纪律

协议按**延迟**设计，不按吞吐：

- 每个同步决策带 deadline（1ms ≈ p99 的 67 倍）
- 超时没回 → **返回 `Pass(original motion)`，不是沿用上一拍命令**：重放一个 `Modify` 会把上一拍的缩放系数又作用到本拍的击退上，位置误差会累积。对修改类动作即跳过本拍，游戏永远不等 core（IPC-PROTOCOL.md §8）
- core 进程死了 → 下一 tick 重连，察觉到的那一拍降级；快照本身就是心跳，没有独立的 keepalive
- 崩溃纪律不变：每个入口 `catch_unwind` → 错误码 → 模块降级；进程模型多一层兜底

最坏情况只是丢一拍，不存在「卡住等待」这个状态。

**降级方向不是全局统一的**，按模块各自的失效代价定，这张表此前一直缺：

| op | 无回答时 | 理由 |
|---|---|---|
| `PLAYER_HIT_GATE` | **放行点击** | 吞掉真实一击比放过去更糟 |
| `PLAYER_REACH_SAMPLE` | 返回 `0.0`（不扩展） | 凭空扩展距离更糟 |
| `PLAYER_CLICK_ASSIST` | 返回 `0`（不加点击） | 未经 core 批准不能凭空造一次点击 |
| `PLAYER_EAGLE_PLACE` | 不写 rotation、不点击 | 未经 core 决策放下的方块收不回来；已持有的右键仍照常释放 |
| `PLAYER_BACKTRACK_TICK` | `release()` | 宁可放过也不悬着状态 |
| `Velocity` 同步决策 | `Pass(original motion)` | 见上，`Modify` 不可重放 |
| 其余 tick 级 op | 本拍无动作 | 状态留在 core，下一拍自然续上 |

`Strategies.online`（= `CoreLink.stub != null`）是**会话/进程在不在**：单次 deadline 超时不算 offline，进程退出或 `start()` 找不到 exe 才算。offline 的显示由外部进程判断，不进 Kotlin（见 9.4）。

#### 9.5.4 边界与状态

JNI 三分类在进程模型下更简单——**所有决策状态都长驻 core 进程**：

| 分类 | 处理 |
|---|---|
| 决策形纯函数（withinFov3D / 策略内算术） | 搬 core，结果随决策返回 |
| **帧级几何白名单**（`Geometry` / `PortableMath`） | **留 JVM**，正式豁免（见 9.4）：对已有数字做算术、每帧每实体调用、不含策略 |
| 有状态噪声（GaussianNoise / NoiseProvider 的 RNG） | 状态整体住 core 进程，不过边界 |
| 永不过界（游戏对象 / 字符串 / Map / FSM 胶水） | 留薄桩侧，或变成 wire 数据 |

唯一非纯点不变：`core/network` PacketScheduler 队列存 `Any`（JVM 对象跨 tick）→ 队列留 Kotlin，只有放行决策过边界。连带一条：`PlatformCommand.CancelPacket(handle: Any)` 携带 JVM 对象引用，跨进程要改成**抽象 packet id**（版本化动词表），由薄桩侧调度器解析回真实包。

旧 P6 的「mappings 补描述符列」**取消**：core 永不读游戏内存，读状态是薄桩（JVM 内、按现有 mappings）；边界是自定义定长 wire 格式，不是 JNI 描述符。

#### 9.5.5 切换标准（不变）

金测回放：Kotlin 录（输入序列 + 种子 → 决策序列），core `--replay` 放同一份输入，diff 为零才切换。
决策只依赖（快照，种子，内部状态），与传输无关——IPC 不引入非确定性，f32 同序同结果。

#### 9.5.6 工作量

| 步骤 | 估时 | 状态（2026-11-08） |
|---|---|---|
| wire 协议 + 薄桩编组/解组 + 进程监督 | 2–3 天 | **已完成** |
| core 进程骨架（生命周期 / 握手） | 2–3 天 | **已完成**；`--replay` 回放器仍待做 |
| 策略 + 算法移植（主体） | 3–4 周 | **已完成**：aim / click / combat / movement / reach / velocity / tap / condition + 最后 6 个 Player 模块，见 9.5 开头 |
| 金测回放基建 + 对拍清零 | 1 周 | 夹具与 Kotlin↔Rust 对拍已完成（合成数据）；真实录像回放待接 |
| core 启动权归 injector | — | **已完成**（2026-11-08），含 `--port-file` 交接与 JVM 纯连接路径 |
| 外部 overlay 的 offline 显示 | — | **未开始**：判定依据已在 injector 侧，显示层不进 Kotlin |
| **合计** | **4–6 周** | 移植主体与启动权已完成；剩余是 overlay 可见性，不是算法迁移 |

> 旧表曾写「velocity 已完成，其余模块待做 / 约完成 1/4」，与 9.5 开头「全部模块已移植完毕」
> 直接矛盾，此处按 2026-11-08 实况更正。

## 10. 一句话总结

> **Kotlin 里只留逻辑判断与编组，算法和状态全在外部进程；injector 拉起 core 与 overlay，外部就能看见沟通是否成立；留 ASM，只把它改名；先断提取，再谈混淆；参数永远不在客户端。**
