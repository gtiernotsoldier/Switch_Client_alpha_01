# 🥪 SwitchLite

> A Minecraft Ghost Client built with the **Sandwich Architecture** — clean separation between core logic and platform-specific code.
>
> 一个基于 **三明治架构** 的 Minecraft 幽灵客户端 —— 核心逻辑与平台代码完全解耦。

 [Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

---

## 🥪 Sandwich 架构 · 现代幽灵客户端设计宣言

### 我们为何存在

反作弊系统（Grim、Polar、Vulcan、Watchdog）正在变得前所未有的智能。它们不再只检测单个作弊特征，而是通过时序分析、运动学建模、机器学习来识别非人类行为模式。传统的"功能堆叠、固定阈值、暴力绕过"模式已经失效——那些客户端正在大规模"坐牢"。

我们需要一种全新的设计哲学：**不是对抗特征，而是模仿人类；不是追求极致效果，而是追求合法生存。**

Sandwich 架构 由此诞生。

---

### 📜 核心理念：一部"现代对抗反作弊的宪法"

我们坚信，一个能够长期生存的幽灵客户端，必须遵守以下四条根本原则：

#### 1. ️ 更安全性 —— Safety First

- **默认合法**：所有模块的默认配置必须在人类行为极限范围内，绝不产生超自然运动或输入。
- **条件触发**：模块只在特定战术场景下激活（如 `onlyGround`、`onlyMoveForward`、`onlyWhenTargetGoesBack`），避免常驻暴露。
- **软边界**：所有修改都应是"柔和、渐进、带随机扰动"的，例如 AimAssist 只将准星拉回碰撞箱边缘，而不是锁死中心点。
- **暴力模块移除**：我们绝不包含 KillAura、Fly、Speed、Jesus 等明显违反游戏物理的功能。

#### 2. 🔍 更高的可调试性 —— Debuggability

- **策略与执行分离**：核心算法（Core）与 Minecraft 适配层（Adapter）完全解耦，Core 可进行单元测试、离线模拟。
- **日志与回放**：适配层可记录每 tick 的状态、决策和网络包，便于被踢后复盘分析。
- **策略热加载**：所有风险参数（击退保留率、CPS 范围、触发条件）均通过 JSON 配置文件定义，支持运行时修改、云端热更，无需重新编译。
- **统一条件引擎**：所有模块共用 `TriggerCondition` 系统，避免重复的 if 判断，集中调试。

#### 3. 🧠 更优秀的策略性 —— Strategy

- **动态自适应**：模块行为根据距离、玩家状态、目标动作、服务器反作弊指纹实时调整。例如 AutoClicker 的 CPS 随目标距离变化，Velocity 仅在敌人后退时生效。
- **策略可插拔**：通过 `AnticheatDetector` 识别当前反作弊类型（Grim/Vulcan/Watchdog），自动加载对应的 JSON 策略包。不同反作弊有不同"甜点参数"。
- **全局行为规划**：所有网络包由统一调度器（`PacketScheduler`）管理，模拟真实网络延迟、丢包、乱序，保证时序一致性。
- **云端策略库**：策略配置文件可托管在云端，客户端启动时自动拉取最新版本，实现"热更新"绕过。

#### 4. 🤝 更好的辅助性 —— Assistance

- **玩家是主角**：所有模块都是"副驾驶"，不替代玩家决策。AimAssist 不自动选择目标，AutoClicker 只在玩家按住左键时优化节奏，WTap 只在玩家向前移动时生效。
- **减少重复劳动**：模块只处理机械、易错的操作（如自动补货、切换工具、搭路潜行），让玩家专注于战术和瞄准。
- **自然交互**：模块的输出必须带有"人类的不完美"——随机扰动、反应延迟、过冲、抖动。让反作弊觉得"这是一个高 ping 但熟练的玩家"。

---

## 🧱 架构总览：三明治分层

```
┌─────────────────────────────────────────────┐
│          C++ 注入器 (injector)               │
│  · 进程检测 / 版本识别                       │
│  · 加载映射库 / 缓存文件                     │
│  · 注入 Agent / 部署 Fabric Mod             │
├─────────────────────────────────────────────┤
│          Java Agent (agent)                 │
│  · 类加载时字节码修改（Javassist/ASM）       │
│  · 提供 MappingContext（跨版本类/方法映射）  │
│  · 缓存序列化（避免每次启动反射）            │
├─────────────────────────────────────────────┤
│          Kotlin 核心库 (core)               │
│  · 纯数学 / 无 Minecraft 依赖               │
│  · 策略接口（AimStrategy, VelocityStrategy） │
│  · 算法实现（旋转、击退、预测、噪声）        │
│  · 条件引擎（TriggerCondition）             │
│  · 数据模型（PlayerState, Vec3）            │
├─────────────────────────────────────────────┤
│          版本适配层 (adapter)               │
│  · forge/1.8.9（独立实现）                  │
│  · fabric/common（1.16～1.21 共享源码）     │
│  · fabric/v1_xx（版本差异适配器）           │
│  · 每个模块 = 一个 .kt 文件                 │
│  · 监听 Minecraft 事件 → 调用 core 策略     │
│  · 将决策写回游戏（motion, 按键, 发包）     │
└─────────────────────────────────────────────┘
```

---

## 📦 项目结构

```
SwitchLite/
├── core/                          # 纯逻辑，无 MC 依赖
│   ├── algorithm/                 # RotationCalculator, VectorOperations, NoiseProvider
│   ├── condition/                 # ConditionChecker, TriggerOptions
│   ├── model/                     # PlayerState, TargetState, CombatContext
│   ── util/                      # Vec2, Vec3, MathUtils
├── adapter/
│   ├── common/                    # 共享模块逻辑（单份代码）
│   │   ├── api/                   # EventBridge, IEventBridge, IStateExtractor
│   │   ├── module/                # Module base, Category, delegates
│   │   └── module/combat/         # AimAssist, AutoClicker, etc.
│   ├── forge/v1_8_9/             # Forge 1.8.9 翻译层
│   └── fabric/v1_21/             # Fabric 1.21 翻译层
├── agent/                         # MappingContext（Java 反射层）
├── config/presets/                # 配置预设
├── injector/resources/            # Mod 元数据和资源
├── mappings/                      # 语义 key → MC 成员映射
└── scripts/                       # 构建和工具脚本
```

---

## 📦 模块设计示例（遵循宪法）

| 模块 | 传统做法 | Sandwich 做法 |
|------|---------|--------------|
| **AimAssist** | 锁定头部/中心，线性平滑 | 仅当准星偏离碰撞箱时柔和拉回边缘，带抖动和反应延迟 |
| **AutoClicker** | 固定 CPS 均匀随机 | 距离自适应 CPS，伽马分布，仅玩家攻击时生效 |
| **Velocity** | 固定保留率 0% | 范围随机（20-60%），条件触发，模拟丢包 |
| **WTap** | 固定 tick 发包 | 随机 tick + 概率 + 仅向前移动时触发 |
| **Backtrack** | 固定延迟 | 动态延迟服从网络分布，随机丢包，与全局调度器协同 |
| **Disabler** | 单一漏洞利用 | 策略库动态加载，多种绕过随机切换，行为合法化 |

---

## 🔧 四层架构详解

| 层级 | 语言 | 职责 | 关键组件 |
|------|------|------|---------|
| **注入器** | C++ | 进程检测、版本识别、注入 Agent / 部署 Fabric Mod | 进程枚举、远程线程、版本探测 |
| **Agent** | Java | 类加载时字节码修改，提供跨版本映射 | Javassist / ASM，MappingContext，缓存序列化 |
| **Core** | Kotlin | 纯数学算法、策略接口、条件引擎、噪声扰动 | AimStrategy, VelocityStrategy, TriggerCondition, NoiseProvider |
| **Adapter** | Kotlin | 版本适配（1.8.9 Forge / 1.20+ Fabric） | VelocityModule, AimModule, BattleInsight 等 |

**核心原则：**
- **算法在 Core**：所有决策逻辑（击退修改、旋转计算、CPS 生成）与 Minecraft 完全解耦，可单元测试。
- **逻辑在模块**：每个适配层模块是一个完整文件，监听事件、提取状态、调用 Core、写回游戏。
- **执行在 Adapter**：具体发包、修改 motion、模拟按键等由适配层完成，Core 不感知。

---

## ️ 四大基础设施（横切关注点）

| 基础设施 | 作用 | 实现位置 |
|---------|------|---------|
| **映射库 + 缓存** | 跨版本类/方法/字段访问，零反射启动 | Agent + JSON + 序列化 |
| **条件引擎** | 统一触发规则（onlyGround, onLook, chance, delay, ticks） | Core TriggerCondition + ConditionChecker |
| **噪声扰动** | 所有策略输出强制添加随机化（高斯/均匀分布） | Core NoiseProvider 装饰器 |
| **全局包调度器** | 管理所有网络包发送顺序、延迟、丢包模拟 | Adapter PacketScheduler |

这些基础设施对模块透明，模块开发者只需声明配置，无需重复实现。

---

## ⚡ 关键组件

### EventBridge（单例）
中枢神经系统。模块直接调用：
```kotlin
EventBridge.setPlayerRotation(Vec2(yaw, pitch))
EventBridge.applyMotion(Vec3(x, y, z))
EventBridge.onVelocityPacket(ctx)
```
平台在启动时注册为处理器——模块永远不知道自己运行在哪个平台上。

### MappingContext
基于 Java 反射的映射层，通过语义 key 解析 Minecraft 类/方法/字段。代码中零硬编码 MC 类名。
```kotlin
MappingContext.getFieldValue(player, "forge:entity_posX")
MappingContext.invokeMethod(world, "fabric:world_getEntityByID", entityId)
```

### Platform Commands
速度处理返回密封命令：
- `ModifyMotion` — 替换运动值
- `CancelPacket` — 完全丢弃包
- `ClickBurst` — 发送快速攻击包
- `Pass` — 让原始值通过

---

## 🎮 战斗模块示例（Velocity）

**Core 层：**
```kotlin
interface VelocityStrategy {
    fun modifyVelocity(original: Vec3, player: PlayerState, target: TargetState?, config: VelocityConfig): Vec3
}
class DefaultVelocityStrategy : VelocityStrategy { /* 纯数学实现 */ }
```

**Adapter 层（1.8.9）：**
```kotlin
@EventTarget fun onPacket(event: PacketEvent) {
    val config = buildConfig()
    val modified = SandwichCore.velocityStrategy.modifyVelocity(original, playerState, targetState, config)
    applyModifiedMotion(modified)
}
```

支持模式：Legit（范围随机 + 条件触发）、Delay（包延迟）、Click（自动连点）。
条件：`onlyMove`, `onlyMoveForward`, `onlyWhenTargetGoesBack`, `onlyGround`, `onLook`, `disabledInAir`。
随机化：水平/垂直保留率范围、触发概率、延迟 tick。

---

## ️ 辅助模块：BattleInsight

纯显示模块，不修改游戏行为，提供：

- **KB 胜率**：实时击退距离对比。
- **HitSelect 时机**：提示对方攻击后的 1-3 tick 窗口。
- **JumpReset 提示**：被击退时建议跳跃。
- **走位提示**：根据距离和地形建议 W/A/S/D。

所有提示均基于真实数据采集（击退包、攻击动画、玩家输入），反作弊无法检测。

---

## 🌐 跨版本策略

- **Forge 1.8.9**：独立适配层（MCP 运行时名）。
- **Fabric 1.16 ~ 1.21**：
  - `common/` 共享 80% 源码（事件、状态提取、策略调用）。
  - `v1_16/`, `v1_20/` 等子模块存放版本差异适配器（API 变化、包名映射）。

映射库 + 缓存：C++ 检测版本 → Agent 加载对应 JSON → 适配层通过 MappingContext 获取类/方法/字段，无硬编码。

---

## 📋 开发状态

本项目处于 **alpha** 阶段。架构已完成，两个平台翻译层已实现，模块正在逐步填充。

### ✅ 已完成
- [x] Sandwich 架构结构
- [x] 核心算法（RotationCalculator, VectorOperations, NoiseProvider）
- [x] 数据模型（PlayerState, TargetState, CombatContext）
- [x] EventBridge 单例模式
- [x] 属性委托（float, int, boolean, enum, triggerOptions, probability）
- [x] Forge 1.8.9 平台层
- [x] Fabric 1.21 平台层
- [x] MappingContext 反射系统

###  进行中
- [ ] 模块实现（AimAssist, AutoClicker, Velocity 等）
- [ ] 包拦截（Fabric Mixin / Forge 包事件）
- [ ] 目标选择系统
- [ ] 配置 UI

---

## 🌍 未来展望

反作弊会越来越强，但我们不会去"攻破"它们。我们的目标是：**让 Sandwich 架构的客户端行为，与真实人类玩家在统计学上无法区分。**

我们相信，辅助 ≠ 作弊。只要尊重游戏规则、尊重其他玩家、尊重反作弊的底线，幽灵客户端就可以作为一种"训练工具"、"辅助外设"长期存在。

欢迎加入我们，共同定义下一代幽灵客户端。

---

## 🔧 开发与贡献指南

1. **克隆仓库**：`git clone https://github.com/gtiernotsoldier/Switch_Client_alpha_01.git`
2. **构建**：使用 Gradle 分别构建 core、agent、adapter 各模块。
3. **调试**：单元测试 Core 层策略，集成测试在本地 Forge/Fabric 环境。
4. **贡献**：欢迎提交 PR 改进策略算法、添加新版本适配层、优化噪声模型。但**绝不允许**添加暴力模块（KillAura、Fly 等）。

---

##  许可证

本项目采用 **GPLv3** 许可证。任何衍生作品必须开源，并保留原始版权声明。我们欢迎社区 fork 和二次开发，但请遵守本"宪法"精神，维护幽灵客户端的生态健康。

---

## 🤝 联系我们

> *"算法在 core，逻辑在模块，执行在 adapter" —— 这既是代码组织原则，也是设计哲学。*

🔗 [Discord](https://discord.gg/Sq4rWn4JG) · [GitHub](https://github.com/gtiernotsoldier/Switch_Client_alpha_01)

**Sandwich 核心团队**
