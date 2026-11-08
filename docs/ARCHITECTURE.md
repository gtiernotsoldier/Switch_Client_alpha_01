# Doppel 架构地图（Architecture Map / 后人入门）

> 这份文档是**实际代码的查找表**，不是理念宣传。它告诉后来的维护者：改一个模块该碰哪些文件、走哪条链、数据从哪来到哪去。
> 理念层面的哲学见根目录 `README.md`；这里只讲**代码真相**（对齐 2026-11，`af1c7ca` 之后）。

## 0. 一句话：两个进程，意志在外

```
javaw.exe                                doppel-core.exe（独立算法进程）
├─ injector 注入的 agent.jar（薄壳）      ├─ 全部算法（24 个 strategy/*.rs）
├─ adapter 模块：判定 + 编组 + 平台落地   ├─ 全部跨 tick 状态机（KeepSprint/LagRange/…）
└─ 零算法、零 kotlin.random、零决策字段   └─ 全部随机性（20 条独立 PCG32 流）
          └──────── 384 字节定长帧 TCP 环回 ────────┘
```

进程所有权：**core 进程归 injector 管**（注入前 spawn，endpoint 写进 `doppel-config.properties`）；游戏 JVM 只是连接器（`CoreSupervisor` 生产路径不 spawn、不监管、不杀进程）。字节表与运行手册见 `Doppel/docs/IPC-PROTOCOL.md`。

## 1. 分层与模块目录（当前 2026 代码真相）

```
Doppel/
├── injector/            # Rust/C++ DLL 注入器：找进程、版本识别、注入 payload
│                        #   + resource.rs::launch_core —— 注入前启动 core 进程（--port-file 就绪信号）
├── agent/               # Java 8 agent：只做编排。ASM 只 hook Display.update() 一个点
├── core-rs/             # ★ 算法进程（Rust，零第三方依赖）
│   ├── src/strategy/    #   24 个文件：全部策略 + 状态机 + RNG 抽样
│   ├── src/rng.rs       #   PCG32（与 JVM 侧 DecisionRng 位对位一致，golden 向量钉死）
│   ├── src/session.rs   #   会话状态：20 条 RNG 流 + 各模块状态机 + op 分发
│   ├── src/wire.rs      #   帧格式（384B 定长）+ Module/Kind/Action 枚举
│   ├── src/codec.rs     #   op 表（append-only）+ 编解码
│   ├── src/transport.rs #   TCP 环回 / UDS 双实现，同一 Conn trait
│   └── golden/          #   42 个 TSV、3.6 万行位级对拍数据
├── ipc/                 # JVM 侧管道：CoreStub / CoreSupervisor / Wire / Strategies
├── contract/            # 纯数据模型与策略接口（PlayerState、VelocityResult…）
├── core/                # Kotlin oracle：与 core-rs 逐位对齐的参考实现（只供 ParityTest 用，不进 agent.jar）
├── safety/              # 安全包装（SetbackTracker 等）
├── adapter/
│   ├── common/          # 跨版本共享：50 个模块 + EventBridge 中枢 + WebUI
│   │   ├── api/         #   EventBridge（中枢）, IEventBridge, IStateExtractor
│   │   ├── module/      #   Module 基类 + 50 模块（combat 21 / movement 10 / player 7 / render 11 / world 1）
│   │   ├── render/      #   OverlayRenderer, RenderContext
│   │   └── webui/       #   WebUI 配置服务器（0.0.0.0:4173，Bearer token）
│   ├── forge/v1_8_9/    # Forge 1.8.9 落地层（ForgeBootstrap / ForgeEventBridge / ForgeStateExtractor / ForgePacketInterceptor）
│   └── fabric/v1_21/    # Fabric 1.21 落地层（对称结构）
├── mappings/forge/v1_8_9.json   # 语义键 → SRG/MCP 名 映射表
├── hud/                 # 外部 overlay（Rust 进程；offline 指示器按 PROTECTION §9.4 住这里，不进 JVM）
├── tickets/             # P0 工单（注入假成功、ClassLoader 签名）
└── docs/                # IPC-PROTOCOL / PROTECTION / CONSTITUTION / CONTRIBUTING / CLIENT-AUTHORITY-AUDIT / hud-contract-v3
```

Gradle 侧模块（`settings.gradle.kts`）：`contract` → `ipc` → `safety` → `core`（oracle）→ `agent` → `adapter:common` → `adapter:forge:v1_8_9`（Fabric 需要 Loom，默认注释）。core-rs 是独立的 cargo 工程。

## 2. 线程与进程（理解一切时序的前提）

| 线程/进程 | 谁在跑 | 职责 |
|---|---|---|
| **JVM 主线程 (MC render)** | `Display.update()` 注入 → `RenderBridge.onFrame` → `ForgeBootstrap.render()` | 写真实按键、改 motion、落地所有合成本地状态；KeepSprint 是唯一帧驱动 op（空帧本地短路，不过线） |
| **JVM 后台 20Hz** | `Agent.java` → `ForgeBootstrap.tick()` | **模块问询**（读 PlayerState → 组帧过线 → 收指令 → 写 EventBridge 期望状态） |
| **JVM Netty 网络线程** | `ForgePacketInterceptor.channelRead()` | 拦 `S12/S27` → `EventBridge.onVelocityPacket`（自己的）+ `notifyEntityVelocity`（别的实体） |
| **core-rs 进程** | 每 stub 一个线程，`read → session.handle → write` | 全部算法、状态机、RNG 演进；空闲阻塞在 read 上，CPU 趋近于零 |

**铁律**：决策在后台/渲染线程**问**，落地只在主线程**写**。模块只写"期望状态"，主线程 render 才是唯一写真实按键/motion 的地方（见 `ForgeEventBridge.applySyntheticInput`）。
**第二条铁律**：判定在模块，**意志在 core**。模块回答"这 tick 事实是什么"（攻击中/在冲刺/距离多少），core 回答"做什么"；概率、时机、窗口、记忆全部不过 JVM。

## 3. 启动链（客户端如何起来）

```
Rust injector
  1. launch_core(): 提取内嵌 doppel-core.exe → --listen tcp:127.0.0.1:0 --port-file …
     → core 绑定成功写 port-file（就绪信号）→ endpoint 写入 doppel-config.properties
  2. 找 javaw 窗口 → 版本识别 → payload.dll (JNI) → agentmain("jni-attach")
  3. Agent 读 endpoint → CoreSupervisor.connect() → Hello/HelloAck（token + seed 派生）
  4. ASM retransform Display.update() 插 RenderBridge.onFrame() → 每渲染帧 ForgeBootstrap.render()
```

`agent/Transformer.java` 只 hook `Display.update()`。攻击方法注入（KeepSprint 的字节码方案）已废弃，见 KeepSprint 模块注释。

> **坑**：`ForgePacketInterceptor` 用 `player.sendQueue`（`field_71174_a`）拿 Netty handler，**不是** `mc_netHandler`（`field_71453_ak` 是错的映射，读 null）。找 Netty channel 按**字段类型** `io.netty.channel.Channel` 扫描。pipeline 注入必须 `channel.eventLoop().execute {}`。

## 4. 映射驱动（跨版本的关键）

- 代码**零 hardcoded MC 类名/字段**（除约 31 处 `Class.forName` 遗留）。
- 每次访问走 `MappingContext.getFieldValue(obj, "forge:xxx")` / `invokeMethod(...)`。
- 语义键在 `mappings/forge/v1_8_9.json`：`"class" + "field"/"method"`（存 SRG 名）+ `mcp` 名（兜底）。
- `joined.srg` 只给 **obf→SRG**，不告诉 SRG 对应哪个 MCP 名；新增映射必要时用 FDP/LB 的 Mixin `@Shadow` 核实。

## 5. 数据流：一次决策的完整生命周期（以 Velocity 为例）

1. **拦截**：Netty 线程收 S12 → 校验 `entityId == playerId` → `ForgeEventBridge.onVelocityPacket(ctx)`。
2. **问询**：Velocity 模块把快照（232B：位置/速度 f64 原始位、flag、配置）组帧 → `Strategies.velocity()` → `CoreStub` 过线。
3. **决策**：core-rs `session.rs` 按 Module 分发 → `strategy/velocity.rs` 用 RNG 流 + 门链算出结果 → Decision 帧回。
4. **落地**：`PlatformCommand`（`ModifyMotion`/`CancelPacket`/`ClickBurst`/`Pass`）——`ModifyMotion` → `pendingMotion` 主线程应用；`CancelPacket` Netty 线程丢包；`ClickBurst` Netty 线程直发。
5. **降级**：1 ms 死线超时 → `Pass(原始 motion)`（**不是**重放上一拍命令）；core 死 → 下一 tick 重连，本拍降级。

延迟实测 p50 6.70 µs / p99 12.50 µs（环回），死线余量 80 倍。

## 6. EventBridge：中枢（既是解耦点，也是唯一"上帝对象"警区）

**它是对的**——把 50 个模块与平台解耦成"集线器"是架构决策，不是失误。**它需要的是边界纪律**，不是拆文件。

### 准入纪律（写进代码顶部，后人遵守）
> **EventBridge 只放两类东西：**
> 1. **跨线程/跨模块的共享状态**（`@Volatile`：合成输入、物理键、motion、击退数据、`crosshairTarget`、sprint claim）。
> 2. **平台回调解耦**（`registerXHandler`：模块不直接碰 MC，平台通过 handler 落地）。
> **单模块内部逻辑禁止塞进来**——它属于那个模块的 `.kt`；**跨 tick 决策状态更禁止**——它属于 core-rs。

### 分区（当前代码 7 大区）
1. Lifecycle & Tick → 2. Combat: Attack & Knockback → 3. Input: Keys & Synthetic → 4. Motion & Actions → 5. Target & Entity → 6. Render & Display → 7. Player Assist。
新增字段先对号入座。

## 7. 模块查找表（判定在哪 / 算法在哪 / 落地在哪）

**算法列全部在 core-rs**（op 号见 `core-rs/src/codec.rs` 的 `pub mod op`，append-only 永不重编号）：

| 模块 | core-rs 算法（op） | 模块侧剩下的（判定+落地） |
|---|---|---|
| Velocity | `strategy/velocity.rs`（含 KnockbackDelay：`VEL_DELAY_ENQUEUE/PUMP`） | S12 拦截编组、`pendingMotion` 落地 |
| AimAssist | `strategy/aim.rs` + `geom.rs` | 鼠标增量采样、rotation 写入 |
| AutoClicker | `strategy/click.rs` | 左键状态采样、attack 编组 |
| Movement 家族（Strafe/InstantStop…） | `strategy/movement.rs`（`MOV_SOCD/STRAFE/SLIDING`） | 键态判定、合成输入落地 |
| WTap / STap | `strategy/tap.rs` + `tap_sample.rs`（`TAP_WTAP_SAMPLE`=7 / `TAP_STAP_SAMPLE`=8，独立流） | 前进判定、W/S 键写入 |
| ADTap | `adtap_sample.rs`（`TAP_ADTAP_SAMPLE`=9，独立 `rng_adtap`） | A/D 判定、键写入 |
| TriggerBot | `combat.rs`（`CBT_TRIGGER/ARBITER/PREDICT/RANGE/DECIDE`） | 门条件编组、attack 落地 |
| AutoBlock | `autoblock_sample.rs`（`CBT_AUTOBLOCK_CHANCE`=5，独立流） | 持剑/威胁判定、右键与 C08 |
| BlockHit | `blockhit_sample.rs`（`CBT_BLOCKHIT_SAMPLE`=6，独立流） | 挥空判定、攻击节奏落地 |
| Reach | `reach.rs` + `reach_sample.rs`（`REACH_INTERSECT` / `PLAYER_REACH_SAMPLE`=3） | 射线编组、`objectMouseOver` 写入 |
| Backtrack | `backtrack.rs`（`PLAYER_BACKTRACK_TICK`=1） | 时钟编组、包延迟开关 |
| KnockbackDisplace | `knockback_displace.rs`（`PLAYER_KBD_ATTACK`=2） | 攻击时刻采样、yaw 写入 |
| ClickAssist | `click_assist.rs`（`PLAYER_CLICK_ASSIST`=4，13 谓词打包进 flags） | 谓词采样、补击落地 |
| HitSelect | `hit_select.rs`（`PLAYER_HIT_TICK`=5 / `PLAYER_HIT_GATE`=6） | crit 条件、点击放行 |
| Eagle | `eagle.rs`（`PLAYER_EAGLE_TICK`=0）；Eagle AutoPlace → `eagle_place.rs`（`PLAYER_EAGLE_PLACE`=7，独立流） | 边缘/俯仰判定、sneak 与放置落地 |
| SprintReset | `sprint_reset.rs`（`PLAYER_SPRINT_RESET`=8，独立流） | gate/range/hurt 判定、C0B 发送 |
| JumpReset | `jump_reset.rs`（`PLAYER_JUMP_KNOCK`=9 / `PLAYER_JUMP_PUMP`=10，独立流） | 内部条件、正面角度、跳键排队 |
| SuperKnockback | `super_knockback.rs`（`PLAYER_SUPER_KNOCKBACK`=11，独立流；三相机器） | trigger/range 判定、C0B + sprint claim |
| **KeepSprint** | `keep_sprint.rs`（`PLAYER_KEEPSPRINT`=12，**首个帧频 op**，无 RNG） | 攻击复合判定、forward 仲裁、claimSprint + C0B 落地 |
| **LagRange** | `lag_range.rs`（`PLAYER_LAGRANGE`=13，无 RNG，确定性 hold 机） | GUI/距离/剑判定、单一 `packetLagActive` 写入 |
| KnockbackDelay | `velocity.rs` 的 delay 队列（`VEL_DELAY_ENQUEUE/PUMP`） | 包参数编组、延迟泵落地 |
| HUD 家族（11 个 render + JumpTiming/KnockbackDisplay） | 无（纯显示，读 EventBridge） | 本地窗口计时/测距（显示语义，不是决策） |
| TargetFilter / AntiBot / Teams | 无（纯本地过滤谓词） | 玩家/生物过滤 → `isViableTarget` |

**通用模式**：模块 = 判定（游戏语境谓词）+ 编组（组帧）+ 落地（把 core 的 step 翻译成平台副作用）。**算法、概率、时间窗口、跨 tick 状态、RNG 一律在 core-rs**。adapter 全部 50 模块已无任何 `kotlin.random` 调用（2026-11-08 达成）。

### RNG 流清单（20 条，`session.rs`）

velocity / aim / click / tap（side 选择）/ wtap / stap / adtap / combat（触发引擎）/ autoblock / blockhit / player / backtrack / kbd / reach / click_assist / hit_select / eagle_place / sprint_reset / jump_reset / super_knockback。KeepSprint 与 LagRange 无流（确定性）。**流隔离的纪律**：一个模块的抽样绝不能挪动另一个模块的回放。

### 状态归谁（一秒判断法）

问"这个字段跨 tick 活着吗？跨了就搬 core"。模块开关切换时 adapter 发 `Reset` 帧（配置保留，学到的状态全忘）——不发不会响亮地失败，只会让下一次 enable 继承脏状态。

## 8. 关键文件清单（改 bug 最常碰）

| 想改什么 | 看哪个文件 |
|---|---|
| 加一个新模块 | `adapter/common/module/<category>/X.kt` + `AgentBridge.moduleEntries`（Fabric 回退路径）/ `ForgeBootstrap` 注册；算法进 `core-rs/src/strategy/` + 新 op（**只能追加**） |
| 改算法/概率/窗口 | `core-rs/src/strategy/<name>.rs` + 同步 `core/src/main/.../<Name>Logic.kt`（oracle）+ **重新生成 golden** |
| 改击退/S12 行为 | `ForgePacketInterceptor` + `EventBridge.onVelocityPacket` + `core-rs/src/strategy/velocity.rs` |
| 改 op / 帧格式 | `core-rs/src/codec.rs`（op 表）+ `ipc/.../Wire.kt`（镜像）+ `Doppel/docs/IPC-PROTOCOL.md`（字节表） |
| 改按键落地 | `ForgeEventBridge.registerListeners()` + `applySyntheticInput()` |
| 改 HUD 显示 | `OverlayRenderer.render()` + `module/render/*.kt`（offline 指示器除外——住 `hud/` 外部进程） |
| 改进程生命周期 | `injector/src/resource.rs`（core spawn）+ `ipc/.../CoreSupervisor.kt`（连接器） |
| 加映射键 | `mappings/forge/v1_8_9.json`（用 srg + FDP/LB Mixin 核实） |
| 重新生成对拍数据 | `cd Doppel/core-rs && cargo run -- --emit-golden golden`（JVM 侧 ParityTest 直接回放） |

## 9. 已知的坑（别踩）

- `mc_netHandler` 映射是坏的（返回 null）——用 `player.sendQueue`。
- Netty pipeline 操作必须在 event-loop 线程；channel 字段按类型找。
- 后台线程**不要**直接改 `mc.thePlayer.motion` / 调实体方法——走合成输入，主线程落地。
- **op 号 append-only**：stub 和 core 是两个可独立升级的二进制，重编号会让旧 stub 静默算出另一个函数。
- **fixture 里的 f32 走原始位**：`last_dist` 这类值在 JVM 是 f32，进 TSV 必须是位级转储，不能经 f64 十进制往返（velocity 的 f64 位规则同源教训）。
- **core/（Kotlin）是 oracle 不是产品**：它不进 agent.jar；改 core-rs 算法必须同步改 oracle 并重生成 golden，否则 ParityTest 红。
- 模块重开**必须**发 `Reset`（`onEnable`/`onDisable` 里 `Strategies.resetPlayer()` 等），否则 core 里的旧状态泄漏进新会话。
- 每个同步决策有 1 ms 死线；超时的正确行为是**按该 op 的降级方向**行事（表见 `Doppel/docs/PROTECTION.md` 9.5.3），不是重放上一拍。
- Fabric 与 Forge 各有一套 `EventBridge.registerX` 落地，两个平台都要实现新 API，才算完整。

---

*维护提示：本文件是对实际代码的映射表；每次重构 EventBridge/ForgeEventBridge/注入链/迁移模块进 core 时，更新这里对应小节，别让它过时。*
