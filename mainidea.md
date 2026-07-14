# 💎 Obsidian Backup（黑曜石备份）：企业级 Minecraft 孤岛式事务型灾备系统完整方案

---

## 📌 一、 项目定位与核心设计原则

Obsidian Backup v3.0 是一款专为 Minecraft 服务器（及高频 IO 游戏实例）量身定制的**企业级内容寻址（CAS）不落盘增量备份系统**。方案基于“**恢复即原始（Restore == Original）**”的铁律，全面剥离了任何可能篡改、破坏游戏原始数据的默认机制，采用完全独立于 JVM 游戏进程的 Sidecar 孤岛架构。

本方案**全面移除了外部 WebUI 监控、REST API 及宿主机 CLI**，将系统暴露面收缩为零，所有控制、诊断、自愈及分析全部通过 **Minecraft 原生指令树（Brigadier）**、异步富文本组件与游戏内视觉指示器驱动。

### 核心设计原则

1. **零侵入进程隔离（Sidecar Architecture）**：将重型分块、收敛加密、数据压缩和网络传输等高 CPU/IO 消耗型任务彻底剥离至独立后台守护进程（Sidecar），游戏主进程仅保留极轻量的逻辑桥，彻底杜绝因备份引发的 JVM GC 停顿与 TPS 掉帧。
2. **两阶段生命周期（Two-Stage Lifecycle）**：将“实时备份（Backup）”与“归档优化（Archive Optimize）”彻底分流。实时备份保持 100% 原始字节物理流式分块；仅在快照转入冷备归档时，才提供可选的 NBT 结构化数据压缩。
3. **确定性自适应调度（Adaptive Scheduler）**：拒绝黑盒 AI，完全基于游戏 TPS、宿主机 CPU、磁盘 IOPS 及公网带宽等硬性指标，通过闭环反馈动态调整 Worker 线程池与网络限流器。
4. **事务型快照管理（Backup Transaction）**：引入严格的 ACID 备份事务机制。从扫描到最终提交，任何一步发生异常立即触发 Rollback（回滚），坚决避免在 CAS 存储仓中生成包含“悬挂对象（Dangling Objects）”的损坏快照。

---

## 🏗️ 二、 系统子系统架构

整个系统划分为控制面与数据面完全解耦的 14 个核心子系统。各组件之间通过本地无端口的 **Unix Domain Socket (UDS)** 链路进行进程间通信（IPC）。

```
Obsidian Backup
├── [Minecraft Game Process] (Java/Kotlin Bridge)
│   ├── MC Native Command Bridge  (Brigadier 强类型指令树与权限控制)
│   ├── Application Hook API     (Before/After 备份与恢复事件总线)
│   └── In-game Visual Notifier   (BossBar/ActionBar 状态与流式进度联动)
│
├── ──── 本地 IPC 通信层 (Unix Domain Socket / Windows Named Pipe) ────
│
└── [Obsidian Sidecar Daemon] (Rust / Go 编写的独立隔离进程)
    ├── Sidecar Core Controller   (核心生命周期、状态机与事务调度中心)
    ├── Journal-Driven Scanner    (WatchService + 区块脏页表轻量扫描器)
    ├── RocksDB Reliable Index    (含 LSM Checkpoint 与元数据自愈重建引擎)
    ├── Chunk Engine              (纯净 FastCDC 分块 / 可选 Archive 压缩管道)
    ├── Object Store & Compactor  (CAS 对象仓 + Git 式 Packfile 紧凑化重组)
    ├── Metadata Store & Signer   (Merkle 树管理器 + Ed25519 快照明细签名)
    ├── Sync & Restore Engine     (单文件/单 Chunk 级差异流式沙箱恢复引擎)
    └── Verification Subsystem    (自动化隔离微型沙箱恢复验证引擎)

```

### 整体架构数据与事务流向

```
       Minecraft Server (Plugin/Mod Process)
                   │
                   ├─► 1. 玩家输入: /obsidian backup --tag "Before_Update"
                   │      (Brigadier 树参数实时补全与 LuckPerms 鉴权)
                   │
                   ├─► 2. Application Hook API 唤醒事件总线，阻塞第三方异步数据库
                   │      (save-off -> save-all flush -> Flush Complete Detector 拦截)
                   │
                   │   3. 通过 IPC (UDS) 发送轻量 JSON 事务激活信号 [BEGIN]
                   ▼
  ═════════════════  Unix Domain Socket (UDS)  ═════════════════
                   │
                   ▼   [进入 Sidecar 独立进程，释放游戏 CPU 核心]
         [Sidecar Core Controller] ──► 激活后台异步流水线 (Worker Pool)
                   │
   [Journal-Driven Scanner] ◄──► [RocksDB Index] ◄──► [Chunk Engine (纯净分块)]
                   │
                   ├─► 4. 内存提取原子状态帧，通过 IPC 反向回传游戏进程
                   │      └─► 游戏内异步渲染 BossBar 进度条与 /obsidian status 状态拓扑
                   │
                   ▼
         [Object Store] ◄──► [Metadata Store (生成解耦的 manifest.json)]
                   │ 5. 加密传输 (XChaCha20-Poly1305) -> [Remote Backup Node]
                   ▼
           [COMMIT Snapshot] ──► 固化 Ed25519 物理签名 ──► 事务完成释放锁

```

---

## 📂 三、 目录与存储结构设计

系统采用 **RocksDB**（本地快速块索引）+ **只读封闭式 Packfile**（数据实体大文件）+ **去中心化物理 Manifest** 的混合结构，彻底消除因数据库损坏导致整仓瘫痪的致命隐患。

### 1. 服务器端（Minecraft 根目录/.obsidian/）

```
.obsidian/
├── config/
│   ├── obsidian.yml               # 主配置 (进程隔离、自适应节流、硬编码排除)
│   └── profiles/                  # 分流环境配置模板
│       ├── production.yml         # 生产环境极限节流模板
│       └── development.yml
├── ipc/
│   └── obsidian.sock              # 本地进程间通信用 UDS 文件 (无开放端口)
├── token/
│   ├── client.key                 # Sidecar 认证私钥 (支持自动轮换)
│   └── server.pub                 # 远端存储节点公钥
└── rocksdb/                       # Sidecar 本地高性能数据库
    ├── scan_cache/                # 记录文件元数据 (Size, MTime, Inode)
    ├── local_blocks/              # 持久化本地块索引 (FileKey → ChunkHash[])
    └── checkpoint/                # ★ 定时物理隔离生成的 LSM Checkpoint 点

```

### 2. 远端存储端（Backup Root 远端备份节点）

```
BackupRoot/
├── snapshots/                     # ★ 去中心化物理快照明细目录
│   ├── snap_v142.json             # 包含所有元数据、版本、指纹与哈希的独立清单
│   └── snap_v142.sig              # 对应该清单的 Ed25519 物理防篡改签名
├── rocksdb/                       # 远端全局元数据库
│   ├── object_index/              # ObjectHash → PackfileID, Offset, Size
│   └── ref_counts/                # ★ 对象全局引用计数表 (用于精确 Mark-Sweep GC)
└── packfiles/                     # 追加写大文件对象容器（上限 512MB，满则 Seal 封闭）
    ├── h_00012.pack               # 热数据封闭包 (物理只读)
    ├── h_00012.idx                # 该 Packfile 的独立索引文件
    └── parity/                    # ★ 对应的 Reed-Solomon (8+2) 纠删码奇偶校验块

```

---

## 🛠️ 四、 核心子系统深度设计

### 1. 互斥独立多模调度中心 (Multi-Scheduler Engine)

为了杜绝子系统间的因果扰动，将调度器拆分为 5 个相互独立的线程池。通过底层的全局调度锁控制矩阵（Lock Matrix）实现强一致性并发约束：

* **`Restore Scheduler`（恢复调度器）**：拥有系统最高绝对优先权。一旦唤醒，立即挂起（Suspend）当前正在运行的 `GC` 与 `Replication` 任务，独占所有磁盘 IO 和系统带宽。
* **`Backup Scheduler`（备份调度器）**：与 `Replication`（副本同步）允许并行，但在数据通道层通过流量整形器（Traffic Shaper）共享同一组网络上限。

### 2. 异步非阻塞流水线 Worker 池 (Worker Pool Architecture)

Sidecar 内部全面升级为**生产者-消费者拓扑模型**。各阶段通过有界环形队列（Bounded Ring Buffer）连接，每个 Worker 线程池可基于自适应策略独立限制 CPU/并发度：

* **`Scanner Worker`**：依靠 `WatchService` 结合**区块脏页表（Region Dirty Table）**，平时实时记录变更的 Region 轴线。备份唤醒时仅对脏页表中的文件进行轻量扫描，大地图扫描耗时从数十分钟直接降至毫秒级。
* **`Chunk Worker`**：对提取的原始流执行 FastCDC 按 MCA 区块边界切分。
* **`Compress Worker`**：使用基于 Minecraft 历史 NBT 训练出的静态字典进行 **ZSTD 多线程高压缩**。
* **`Encrypt Worker`**：引入远端 KMS 的盲签名（Blind Signatures）机制派生密钥，对数据执行端到端全密文 **XChaCha20-Poly1305** 加密。

### 3. ACID 备份事务管理 (Backup Transaction)

为杜绝网络中断、突发掉电或校验失败导致 CAS 存储仓中产生不完整快照或悬挂对象，系统内嵌完整的事务生命周期：

* **`BEGIN`**：分配全局唯一事务 ID（TxID），拍摄当前的本地 RocksDB 索引镜像，锁定区块脏页表。
* **`EXECUTE`**：流式分块、压缩、加密并异步上传 Object 至仓储。未提交的 Object 在远端打上 `Transient` 标签。
* **`ROLLBACK`**：若任何一个 Worker 报错或物理哈希校验不通过，立即释放内存清单，向远端发送 `TxAbort` 信号。未提交的暂态 Object 将在下次 GC 时被无损秒清，**不污染现有快照链**。
* **`COMMIT`**：双重物理哈希校验无误后，原子写入 `manifest.json` 与 Ed25519 签名，RocksDB 正式固化引用计数更新。

### 4. 沙箱隔离与原子切换恢复引擎 (Restore Sandbox)

杜绝任何形式的在游戏原目录“原地在线覆盖恢复”。

* **沙箱释放**：恢复引擎在临时隔离区单独拉起 `world_sandbox_tx99/` 目录，结合本地未变变更块与云端下载块，在沙箱内部组装出目标世界。
* **原子切换（Atomic Rename Swap）**：在沙箱内完成全量 `CRC32-C` 与 `SHA256` 双重物理校验后，通过底层系统调用执行毫秒级目录重命名替换：

$$\text{world/} \xrightarrow{\text{< 1ms}} \text{world\_old\_tmp/}$$


$$\text{world\_sandbox\_tx99/} \xrightarrow{\text{< 1ms}} \text{world/}$$



随后异步平稳 unlink（删除）老旧的损坏目录。
* **断点续传（Restore Resume）**：若在向沙箱流式写入时遭遇断电，重启后 Sidecar 读取沙箱临时检查点（Checkpoint Manifest），直接跳过已完成的文件块，继续向下追加，避免重头下载。

---

## 🎮 五、 Minecraft 特性专属适配

### 1. 玩家数据（playerdata）覆盖安全保护

* **前置拦截**：当管理员触发 `/obsidian restore --file playerdata/` 时，适配器自动检索在线玩家列表。若目标玩家在线，直接熔断并抛出红字提示，防止因玩家在线导致内存背包数据在退出时反向覆写磁盘。
* **后置踢出**：通过桥接总线向服务器发送高优先级 Native 指令，瞬间踢出（Kick）对应玩家，阻断其内存刷盘，Sidecar 随后执行秒级沙箱覆盖。

### 2. 硬编码生产环境排除列表

默认完全忽略与世界状态无关的运行时锁文件及高频缓存，防止文件占用引发的事务挂起：

```yaml
exclusion_rules:
  hardcoded_ignores:
    - "**/session.lock"               # 严禁备份此文件，否则恢复后的新实例将因锁占用无法启动
    - "**/logs/**"                    # 排除历史日志
    - "**/cache/**"                   # 排除运行时插件缓存
    - "**/libraries/**"               # 排除核心依赖库

```

### 3. Paper 异步保存完整性检测（Flush Complete Detector）

针对 Paper/Folia 的高并发多线程 IO 架构下 `save-all flush` 返回时文件可能并未实际落盘的痛点，`Flush Complete Detector` 注入底层 `RegionFileStorage` 监视器，实时盘点异步 IO 队列（Async Save Queue）。只有当队列长归零且磁盘 `fsync` 信号成功返回后，Sidecar 数据流才正式起航。

---

## 🎛 六、 纯原生命令驱动与内省监控系统

全面废除任何形式的外部 Web 端、REST 端口及独立的系统 CLI。所有的功能、高级运维策略以及原本属于 Grafana 面板的监控指标，完全收拢至游戏内及控制台的 **Brigadier 强类型指令树**中。

### 1. 核心核心诊断与监控指令矩阵

#### 📌 A. 实时性能与队列监控：`/obsidian status`

通过 UDS IPC 拉取 Sidecar 内存中的轻量级环形原子状态帧，在游戏内异步渲染成高密度的流水线实时状态图，彻底消灭外露端口。

> **游戏内控制台/聊天栏异步输出效果：**
> `[23:00:15 INFO] [Obsidian] ─── 核心流水线实时状态诊断 ───`
> `运行状态: ` 🟢 **ACTIVE (备份中)** ` | 事务 ID: tx_99f2a`
> `自适应级: ` 🟡 **WARN (检测到 TPS 波动，已自动节流 35%)**
> ` `📦 缓冲队列阻滞状态 (鼠标悬停可查看当前 Worker 线程数):` `  [Scanner] ──(0 块)──► [Chunk] ──(14 块)──► [Compress] ──(156 块 ⚠️)──► [Encrypt] ──(2 块)──► [Upload]` `  * 瓶颈诊断：[Compress] 队列积压，ZSTD 多线程物理压缩正在全力运转消耗 CPU。` `
> `⚡ 性能速率指标:`
> `  - 游戏 TPS: 19.85 | 宿主机 CPU 占用: 24.1% | 堆外内存: 412 MB`
> `  - 磁盘读写 IOPS: 1,420 / 3,500 (安全) | 公网网络上传速度: 18.5 MB/s`
> `  ` **`[ ⏸️ 暂停备份 ]`** ` ` **`[ 🛑 终止并回滚事务 ]`** *(带有高亮点击事件)*

#### 📌 B. 存储热力图与空间膨胀分析：`/obsidian top`

一键揪出吃光硬盘空间的罪魁祸首，提供根源成因剖析。

> **游戏内控制台/聊天栏异步输出效果：**
> `[23:00:16 INFO] [Obsidian] ─── 全局存储仓空间热力图 (TOP 5) ───`
> `📊 综合全局去重比: 94.2% | 字典压缩增益: +18.2%`
> ` `📂 空间膨胀源文件排行 (鼠标悬停可查看该文件引发的去重碎片率):` ` 1. `🟥`world/region/r.12.32.mca  [体积: 8.4 GB] [分析原因: 密集红石高频刷怪场]` ` 2. `🟨`world_nether/region/r.0.0.mca [体积: 4.1 GB] [分析原因: 确定为常驻主城强加载区]` ` 3. `🟩`plugins/CoreProtect/database.db [体积: 2.9 GB]` `
> `👤 玩家数据膨胀排行 (PlayerData):`
> `1.` `Notch (UUID: 01fa...) [体积: 142 MB] [悬停查看: 携带了超大 NBT 嵌套潜影盒容器]`

---

### 2. 完整指令矩阵规范表

| 原生指令语法 | 权限节点 | 交互式文本 / BossBar 视觉反馈设计 |
| --- | --- | --- |
| `/obsidian status` | `obsidian.admin.monitor` | 异步输出带颜色的有界队列阻滞拓扑图与自适应软硬件实时速率。 |
| `/obsidian top` | `obsidian.admin.monitor` | 输出占用物理存储最高的文件、MCA 区域以及玩家数据排行。 |
| `/obsidian forecast` | `obsidian.admin.monitor` | 动态计算去重净增长，预测物理磁盘空间在当前趋势下还能支撑的**天数**。 |
| `/obsidian diff <id_A> <id_B>` | `obsidian.admin.snapshot` | 表格化高亮输出两个快照之间新增、修改、删除的文件及精确的 MCA 区块数。 |
| `/obsidian browse <id> [path]` | `obsidian.admin.snapshot` | 虚拟浏览快照内部文件树。悬停看历史大小，点击自动填充文件提取路径。 |
| `/obsidian restore <id> --file <path>` | `obsidian.admin.restore` | **单文件精准恢复**（支持前置在线玩家拦截与 Kick 逻辑）。 |
| `/obsidian restore <id> --chunk <w>:<x>,<z>` | `obsidian.admin.restore` | **单 Chunk 级无损回滚**。仅精准覆写对应的 MCA 扇区，不干扰周边区块。 |
| `/obsidian clone <id> <new_name>` | `obsidian.admin.snapshot` | **世界秒级克隆**。无损提取快照中的指定世界并挂载为独立新世界用于热更新测试。 |
| `/obsidian rollback --duration 1m` | `obsidian.admin.restore` | **近线瞬间闪回**。基于本地环形 Op-Log 逆向重放变动，实现无需断服的微调回滚。 |
| `/obsidian verify repair` | `obsidian.admin.verify` | 唤醒整仓巡检。若发现受损物理块，BossBar 变为紫色并利用 RS(8+2) 纠删码强制自愈。 |
| `/obsidian snapshot [export/import] <p>` | `obsidian.admin.snapshot` | 在游戏内直接下发指令，令 Sidecar 物理导出或逆向导入标准的 `.tar.zst` 单体归档包。 |
| `/obsidian pin <id> --days <count>` | `obsidian.admin.snapshot` | **WORM 锁定**。打上 Pin 标签，锁定期内任何手动/自动 GC 均无法删除此快照。 |

---

## 🔒 七、 企业级纵深防御安全机制

### 1. 快照清单非对称签名 (Snapshot Manifest Signature)

每个快照树最终生成一个自包含的 `manifest.json` 清单。Sidecar 在提交事务时，使用本地物理隔离的 **Ed25519 私钥** 对清单文件进行哈希签名并伴随存储（生成 `.sig` 文件）。在执行任何恢复或巡检时，首先验证签名，任何黑客离线篡改物理 Packfile 或清单明细的操作都会引发自熔断。

### 2. 双人审计确认恢复机制 (Dual-Admin Authorization)

针对高危的生产服全量覆盖恢复操作，开启双人审计。管理员 A 输入恢复命令后，备份进入挂起状态，系统在控制台/安全通道生成一个独立确认令牌（Token）。必须由管理员 B（或宿主机控制台控制端）在 5 分钟内输入 `/obsidian confirm <token>`，沙箱原子切换方可下发执行。

### 3. 隔离区管理 (Quarantine Subsystem)

在静默巡检（Verify）过程中，若发现某个历史快照的 Merkle 树拓扑残缺或哈希对不上，系统**绝不直接执行删除**。该快照状态机瞬间变更为 `Quarantine`（隔离），自动从常规历史列表中隐藏，锁定其引用的所有物理 Object 不参与 GC 清洗，保留现场等待管理员通过 `/obsidian verify repair` 引入外部纠删块恢复。

---

## 📅 八、 渐进式开发路线图

本路线图彻底剔除了不切实际的 AI 概念，聚焦于底层数据一致性、原生交互与高价值高级灾备功能的落地。

| 阶段 | 核心交付功能 | 架构演进目标 |
| --- | --- | --- |
| Phase 1<br>

<br>(事务型内核) | • 动态分块与本地 RocksDB 索引底座<br>

<br>• 无端口 Sidecar 进程与本地 UDS IPC 链路<br>

<br>• **ACID 备份事务机制**（Begin/Commit/Rollback）<br>

<br>• 基础单机版 CLI 差异化备份与全量恢复 | 验证无锁化、游戏零内耗的独立进程级事务数据通道，确保核心去重逻辑完全移出 JVM。 |
| Phase 2<br>

<br>(原生人机交互) | • **Brigadier 强类型指令树全面覆盖与鉴权**<br>

<br>• **异步拉取原子状态帧与 `/obsidian status/top` 文本拓扑渲染**<br>

<br>• **BossBar / ActionBar 四色流式进度指示器**<br>

<br>• Paper 异步保存完整性检测器（Flush Detector）<br>

<br>• Git 类似式 Packfile 封闭（Seal）与 Compaction 紧凑化 | 彻底消除外露端口与 WebUI 依赖，收敛安全面，实现符合专业运维直觉的全交互式命令行监控。 |
| Phase 3<br>

<br>(企业自愈防护) | • RocksDB LSM Checkpoint 与 Manifest 反向自愈重建<br>

<br>• **Ed25519 快照清单防篡改签名与双人确认安全策略**<br>

<br>• 不可变快照（防勒索 WORM 锁定锁）机制落地<br>

<br>• 远端加密同步与 RS(8+2) 纠删码隔离区自愈管道<br>

<br>• 插件生态扩展事件总线（Before/After 事件 Hook API） | 打造无懈可击的安全纵深防御体系，建立针对勒索病毒及物理介质损坏的自动化灾备自愈网。 |
| Phase 4<br>

<br>(高级数据编排) | • **快照级差异对比引擎（Snapshot Diff）**<br>

<br>• **虚拟快照目录树深度浏览（Browse）**<br>

<br>• **单文件 / 单 Chunk 精准零损覆盖恢复**<br>

<br>• 测试世界克隆（World Clone）与 Near-CDP 分钟级瞬间闪回<br>

<br>• **确定性自适应调度器（Adaptive Scheduler）** | 移除概念泡沫，赋予运维人员对服务器空间、时间以及系统级硬件资源负载的极致精细化控制权。 |

---

## 📋 九、 分流生产环境配置文件模板 (`profiles/production.yml`)

```yaml
# Obsidian Backup v3.0 企业级生产环境标准指引配置
profile: production

# 多模独立调度中心资源时段限制
scheduler:
  concurrency_policy:
    restore_prioritized: true
    suspend_gc_on_restore: true
  backup_windows:
    # 凌晨低峰期不限速全力运转
    - window: "02:00-06:00"
      bandwidth_limit: "unlimited"
      cpu_worker_limit: 8
      io_iops_max: 5000
    # 白天高峰期严格节流，优先保障游戏主线程 TPS
    - window: "06:01-01:59"
      bandwidth_limit: "20MB/s"
      cpu_worker_limit: 2
      io_iops_max: 800

# 核心安全防御配置
security:
  dual_admin_confirmation: true # 高危全量覆盖恢复强制启用双人令牌确认
  snapshot_signing:
    enabled: true
    private_key_secure_path: "/etc/obsidian/keys/sign.key"
  immutable_locks:
    weekly_retention_locked: true # 每周日生成的全量快照强制上 WORM 锁，免疫勒索清空

# 恢复沙箱与原子切换设置
sandbox_restore:
  temp_dir: "./.obsidian/sandbox"
  atomic_swap: true
  verify_before_swap: true      # 必须通过 CRC32-C 与 SHA256 双重校验才允许Rename切换
  resume_checkpoint: true       # 允许断点续传恢复

# 确定性自适应负载节流
adaptive_scheduler:
  enabled: true
  metrics_polling_interval_ms: 1000
  thresholds:
    tps_critical: 15.5          # 若游戏 TPS 跌破 15.5，立即挂起 GC 和副本同步，降维至单线程分块
    tps_danger: 16.5            # 若游戏 TPS 跌破 16.5，动态压缩网络吞吐与磁盘 IOPS
    host_memory_cap_mb: 2048    # 严格封锁 Sidecar 堆外原生内存池配额，防止内存泄漏

# 物理只读容器配置
storage_structure:
  packfile:
    adaptive_sizing: true
    max_packfile_size_mb: 512   # 到达 512MB 立即封口，固化 Footer 变为只读
    enable_crc32c_footer: true
  rocksdb_reliability:
    checkpoint_interval_minutes: 60
    auto_rebuild_from_pack: true # 本地索引损毁时，允许直接通过物理只读 Packfile 的索引反向重建

```