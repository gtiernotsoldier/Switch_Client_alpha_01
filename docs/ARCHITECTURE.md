# SwitchLite 架构地图（Architecture Map / 后人入门）

> 这份文档是**实际代码的查找表**，不是理念宣传。它告诉后来的维护者：改一个模块该碰哪些文件、走哪条链、数据从哪来到哪去。
> 理念层面的哲学见根目录 `README.md`；这里只讲**代码真相**。

## 1. 分层与模块目录（当前 2026 代码真相）

```
SwitchLite/
├── injector/            # Rust/C++ DLL 注入器：改进程、版本识别、注入 payload
├── agent/               # Java agent：javassist 字节码注入 + MappingContext（映射抽象）
├── core/                # 纯 Kotlin 算法，零 MC 依赖
│   ├── strategy/        #   VelocityStrategy, AimStrategy, KeepSprintStrategy, ReachRaycast...
│   ├── algorithm/       #   RotationCalculator, VectorOperations, NoiseProvider
│   ├── condition/       #   ConditionChecker, TriggerOptions（统一条件引擎）
│   ├── model/           #   PlayerState, TargetState, VelocityContext, PlatformCommand
│   └── util/            #   Vec2, Vec3
├── adapter/
│   ├── common/          # 跨版本共享的模块逻辑 + 中枢
│   │   ├── api/         #   EventBridge（中枢）, IEventBridge, IStateExtractor
│   │   ├── module/      #   Module 基类 + 40 个模块（combat/render/movement/player/world）
│   │   ├── render/      #   OverlayRenderer, RenderContext
│   │   └── webui/       #   WebUI 配置服务器（0.0.0.0:4173）
│   ├── forge/v1_8_9/   # Forge 1.8.9 落地层（ForgeBootstrap / ForgeEventBridge / ForgeStateExtractor / ForgePacketInterceptor）
│   └── fabric/v1_21/   # Fabric 落地层（对称结构）
├── mappings/forge/v1_8_9.json   # 语义键 → SRG/MCP 名 映射表
└── docs/ARCHITECTURE.md          # 本文档
```

## 2. 三条线程（理解一切时序的前提）

| 线程 | 谁在跑 | 职责 |
|---|---|---|
| **主线程 (MC render)** | `Display.update()` 注入 → `RenderBridge.onFrame` → `ForgeBootstrap.render()` | 写真实按键、改 motion、落地所有合成本地状态 |
| **主线程 (世界渲染内)** | `renderWorldPass` 注入 → `RenderBridge.onWorldRender` → `ForgeBootstrap.renderWorld()` | 世界空间叠加（HitBox 碰撞箱）——此时 GL 投影/模型/深度缓冲都是世界真实值，天然被墙遮挡（非透视） |
| **后台 20Hz** | `Agent.java` → `ForgeBootstrap.tick()` | **模块决策**（读 PlayerState → 调 core 策略 → 写 EventBridge 期望状态） |
| **Netty 网络线程** | `ForgePacketInterceptor.channelRead()` | 拦 `S12PacketEntityVelocity`/`S27PacketExplosion` → `EventBridge.onVelocityPacket`（自己的）+ `EventBridge.notifyEntityVelocity`（别的实体） |

**铁律**：决策在后台，落地在主线程。模块（后台线程）只写"期望状态"，主线程 render 才是唯一写真实按键/motion 的地方（见 `ForgeEventBridge.applySyntheticInput`）。

## 3. 注入链（客户端如何启动）

```
Rust injector → payload.dll (JNI) → agentmain("jni-attach")
  → javassist retransform Display.update()，在方法体开头插入 RenderBridge.onFrame()
  → 每渲染帧调用 → ForgeBootstrap.render()
  → javassist retransform EntityRenderer.renderWorldPass（func_175068_a），方法末尾插入 RenderBridge.onWorldRender()
  → 每世界帧调用 → ForgeBootstrap.renderWorld()（HitBox 世界空间叠加）
```

`agent/Transformer.java` hook 两个点：`Display.update()`（2D HUD 渲染管线，必装）和 `EntityRenderer.renderWorldPass`（世界空间叠加，best-effort——失败不影响 HUD）。攻击方法注入（KeepSprint 的字节码方案）已废弃，见 KeepSprint 模块注释。

> **坑**：`ForgePacketInterceptor` 用 `player.sendQueue`（`field_71174_a`）拿 Netty handler，**不是** `mc_netHandler`（`field_71453_ak` 是错的映射，读 null）。找 Netty channel 按**字段类型** `io.netty.channel.Channel` 扫描（混淆环境名字不可靠，类型可靠）。pipeline 注入必须 `channel.eventLoop().execute {}`。

## 4. 映射驱动（跨版本的关键）

- 代码**零 hardcoded MC 类名/字段**（除约 31 处 `Class.forName` 遗留）。
- 每次访问走 `MappingContext.getFieldValue(obj, "forge:xxx")` / `invokeMethod(...)`。
- 语义键在 `mappings/forge/v1_8_9.json`：`"class" + "field"/"method"`（存 SRG 名）+ `mcp` 名（兜底）。
- `joined.srg`（mcp-1.8.9-srg）只给 **obf→SRG**，**不告诉 SRG 对应哪个 MCP 名**。判断"哪个 SRG 是 netHandler"需要 MCP `fields.csv`（不在仓库）。所以映射键里的 SRG 名可能手工填错（如 `mc_netHandler`）。新增映射必要时用 FDP/LB 的 Mixin `@Shadow` 核实。

## 5. 数据流：一个战斗模块（以 Velocity 为例）

1. **拦截**：`ForgePacketInterceptor.channelRead`（Netty）收到 S12 → 校验 `entityId == playerId` → `ForgeEventBridge.onVelocityPacket(msg)`。
2. **决策**：`EventBridge.onVelocityPacket(ctx)` → Velocity 模块的 `onVelocityPacket` → `core/strategy/velocity/*Strategy.execute()`（纯算法，返回 `VelocityResult`）。
3. **落地**：返回 `PlatformCommand`（`ModifyMotion`/`CancelPacket`/`ClickBurst`/`Pass`）。
   - `ModifyMotion` → `ForgeEventBridge.pendingMotion` → 主线程 render 应用。
   - `CancelPacket` → `return` 丢弃包（Netty 线程）。
   - `ClickBurst` → Netty 线程直接发包。
4. **显示**：`EventBridge.velocityModified` + `recordKnockback(original, modified)` 喂给 `VelocityDisplay`/`KnockbackDisplay`/`JumpTiming` HUD。

## 6. EventBridge：中枢（既是解耦点，也是唯一"上帝对象"警区）

**它是对的**——把 40 个模块与平台解耦成"集线器"是架构决策，不是失误。**它需要的是边界纪律**，不是拆文件。

### 准入纪律（写进代码顶部，后人遵守）
> **EventBridge 只放两类东西：**
> 1. **跨线程/跨模块的共享状态**（`@Volatile`：合成输入、物理键、motion、击退数据、`crosshairTarget`）。
> 2. **平台回调解耦**（`registerXHandler`：模块不直接碰 MC，平台通过 handler 落地）。
> **单模块内部逻辑禁止塞进来**——它属于那个模块的 `.kt`。新增前先问"这是 API 还是实现"。

### 分区（当前代码 7 大区）
1. Lifecycle & Tick → 2. Combat: Attack & Knockback → 3. Input: Keys & Synthetic → 4. Motion & Actions → 5. Target & Entity → 6. Render & Display → 7. Player Assist。
新增字段先对号入座。

## 7. 模块内查找表（决策在哪 / 落地在哪 / 算法在哪）

| 模块 | 算法 (core) | 决策 (module) | 平台落地 (forge) |
|---|---|---|---|
| Velocity | `strategy/velocity/*Strategy` | `module/combat/Velocity.kt` | `ForgePacketInterceptor` + `ForgeEventBridge.pendingMotion` |
| JumpReset | `RotationCalculator.yawToDirection` | `module/combat/JumpReset.kt` | `ForgeEventBridge` jump handler（主线程按跳键脉冲） |
| Reach | `strategy/reach/ReachRaycast.intersectBox` | `module/combat/Reach.kt` | `ForgeEventBridge.doReachRaycast`（写 `objectMouseOver`） |
| KeepSprint | `strategy/keepsprint/*`（见注释：已弃用） | `module/combat/KeepSprint.kt` | 无（纯本地，见模块注释） |
| AimAssist | `strategy/aim/Legit* / SelfAdaptive*` | `module/combat/AimAssist.kt` | `setPlayerRotation` |
| AutoBlock/WTap/STap/BlockHit | 共用 `strategy/combat/CombatTrigger` | `module/combat/*.kt` | `setKeyBindPressed` / C0B |
| JumpTiming (HUD) | 无（窗口计时在模块） | `module/render/JumpTiming.kt`：**每命中窗口只计一次**（疾跑命中才开窗，重复按跳不计数）+ JumpReset 脉冲适配（`(JR)` 标记） | `ForgePacketInterceptor` S12 通知（Netty 线程精确时间戳） |
| KnockbackDisplay (HUD) | 无（位移测距在模块） | `module/render/KnockbackDisplay.kt`：IN 收到（原始向量+D+Velocity cut%）/ OUT 打出（S12 与自身攻击关联+目标位移）双数据源 | S12 通知 + `EventBridge.getEntityPosition` |
| HitBox (Render) | 1.7 尺寸表在模块（待实测校准） | `module/render/HitBox.kt`（类别过滤/颜色/线宽） | `renderWorldPass` 钩子 → `ForgeEventBridge.registerHitBoxFrameProvider` |

**通用模式**：core 是纯函数（可 JUnit 测）；module 读 `PlayerState`→调 core→写 `EventBridge` 期望；forge/fabric 在 `registerListeners()` 里用 `EventBridge.registerXHandler { ... }` 落地。

## 8. 关键文件清单（改 bug 最常碰）

| 想改什么 | 看哪个文件 |
|---|---|
| 加一个新模块 | `adapter/common/module/<category>/X.kt` + `ForgeBootstrap.registerAll()` 注册；2D HUD 类加进 `OverlayRenderer`，**世界空间类**（如 HitBox）要加 `renderWorldPass` 钩子 + `ForgeEventBridge` 帧提供器 |
| 改击退/S12 行为 | `ForgePacketInterceptor` + `EventBridge.onVelocityPacket` + `core/strategy/velocity` |
| 改按键落地 | `ForgeEventBridge.registerListeners()` + `applySyntheticInput()` |
| 改 HUD 显示 | `OverlayRenderer.render()` + `module/render/*.kt` |
| 改世界渲染叠加 | `Transformer`(renderWorldPass 钩子) + `ForgeEventBridge.registerHitBoxFrameProvider` + `module/render/HitBox.kt` |
| 改 WebUI 配置持久化 | `module/render/WebUI.kt` + `webui/ConfigStore` |
| 加映射键 | `mappings/forge/v1_8_9.json`（用 srg + FDP/LB Mixin 核实） |

## 9. 已知的坑（别踩）

- `mc_netHandler` 映射是坏的（返回 null）——用 `player.sendQueue`。
- Netty pipeline 操作必须在 event-loop 线程；channel 字段按类型找。
- 后台线程**不要**直接改 `mc.thePlayer.motion` / 调实体方法（如 `player.jump()`）——走合成输入 `setKeyBindPressed`，主线程落地。
- HitBox 的"非透视"依赖 `renderWorldPass` 注入点的深度缓冲：无光影（OptiFine M5）正常；开光影（shaders）时深度缓冲可能被合成阶段改写，箱子可能透墙。
- `ConditionChecker.check(triggerOptions, player, target)` 是统一条件；不同模块的 `triggerOptions` 独立，别跨模块复用。
- Fabric 与 Forge 各有一套 `EventBridge.registerX` 落地，两个平台都要实现新 API，才算完整。

---

*维护提示：本文件是对实际代码的映射表；每次重构 EventBridge/ForgeEventBridge/注入链时，更新这里对应小节，别让它过时。*
