# Obsidian Backup — 技术实现总结

> 企业级 Minecraft 孤岛式事务型灾备系统（CAS 不落盘增量备份）
>
> 项目仓库：https://github.com/shrimp-211/obsidian-backup
> 当前版本：v0.2.0 · 约 7,900 行代码 · 7 次 commit

---

## 一、项目定位与核心设计

Obsidian Backup v3.0 是一款专为 Minecraft 服务器量身定制的**内容寻址（CAS）增量备份系统**，遵循三条铁律：

1. **零侵入进程隔离（Sidecar 架构）**：重型分块、加密、压缩、网络传输全部剥离至独立的 Rust 守护进程，游戏主进程（JVM）仅保留轻量逻辑桥，杜绝备份引发的 GC 停顿与 TPS 掉帧。
2. **两阶段生命周期**：实时备份保持 100% 原始字节流式分块；快照转入冷备归档时才做 NBT 结构化压缩。
3. **恢复即原始（Restore == Original）**：沙箱隔离 + 原子切换（Atomic Rename Swap），杜绝"原地在线覆盖恢复"。

**零外露端口**：全面移除 WebUI / REST API / 宿主机 CLI，所有控制通过游戏内 Brigadier 指令树 + UDS 本地 IPC 完成。

---

## 二、系统架构总览

```
┌─────────────────────────────────────────────────────────────┐
│  Minecraft 游戏进程 (Java/Kotlin Bridge)                      │
│  ├── NeoForge 1.21.1 (Kotlin) — 原始实现                      │
│  ├── Fabric 1.21.1 / 1.20.1 (Java)                           │
│  ├── Forge 1.21.1 / 1.20.1 (Java)                            │
│  ├── Paper/Bukkit 1.21.1 / 1.20.1 (Java)                     │
│  └── 共用 common/ 纯 Java 共享库                              │
└───────────────┬─────────────────────────────────────────────┘
                │ Unix Domain Socket (UDS) JSON IPC
                ▼
┌─────────────────────────────────────────────────────────────┐
│  Obsidian Sidecar (Rust 独立进程)                             │
│  ├── IPC Server         — UDS 监听 + 令牌认证                  │
│  ├── BackupEngine       — 扫描→分块→去重→存储→事务              │
│  ├── ChunkEngine        — FastCDC 内容定义分块 + BLAKE3        │
│  ├── BlockIndex         — RocksDB 5 列族块索引                 │
│  ├── ObjectStore        — CAS 对象存储 + Packfile              │
│  └── TransactionManager — ACID 事务 (BEGIN/COMMIT/ROLLBACK)   │
└───────────────┬─────────────────────────────────────────────┘
                │
                ▼
  Obsidian CLI (Rust) — 独立管理工具，经同一 UDS 接口通信
```

---

## 三、Gradle 多项目构建系统

```
settings.gradle          根项目入口，包含全部 5 个子项目
gradle.properties        构建属性（默认 mc=1.21.1）
versions.gradle          版本矩阵中枢（核心设计）
├── common/              共享 Java 库（零 MC 依赖）
├── mod-neoforge/        NeoForge 1.21.1 (Kotlin) — 独立版本管理
├── fabric/              Fabric (Loom)
├── forge/               MinecraftForge (ForgeGradle)
└── bukkit/              Paper/Bukkit
```

### versions.gradle 多版本矩阵

```groovy
switch (ext.mc) {
    case '1.21.1':
        v.fabric_loader = '0.16.7'
        v.fabric_api = '0.102.0'
        v.forge_version = '51.0.30'
        v.paper_api = '1.21.1-R0.1-SNAPSHOT'
        v.java_version = 21
    case '1.20.1':
        v.fabric_loader = '0.15.11'
        v.fabric_api = '0.92.2'
        v.forge_version = '47.3.0'
        v.paper_api = '1.20.1-R0.1-SNAPSHOT'
        v.java_version = 17
}
```

**一键切换版本**：
```bash
gradle :fabric:build              # 默认 1.21.1
gradle :fabric:build -Pmc=1.20.1  # 1.20.1
```

### 关键架构决策

| 决策 | 理由 |
|------|------|
| `common` 模块编译为 Java 17 字节码 | 1.20.1 运行时是 Java 17，Java 21 字节码会导致 `UnsupportedClassVersionError` |
| `common` 依赖仅 Gson + JDK | 与 Minecraft 版本完全解耦，所有加载器共用 |
| 每个加载器子项目 `apply from: versions.gradle` | 单一事实来源，新增 MC 版本只需加一个 case |
| NeoForge 保持独立版本管理 | 明确标记"仅 1.21.1"，避免与其他模块耦合 |

---

## 四、common 共享库（纯 Java）

`common/src/main/java/com/obsidian/backup/common/`

| 文件 | 职责 |
|------|------|
| `IpcProtocol.java` | 15 种操作码 + 请求/响应模型 + 参数 Builder |
| `IpcClient.java` | UDS IPC 客户端（AtomicBoolean 线程安全 + 认证握手） |
| `ObsidianConfig.java` | 统一配置模型（socket 路径 / token / 超时 / BossBar 开关） |

### IPC 协议设计（JSON over UDS，换行分隔）

```json
// 请求
{"tx_id": "a1b2c3d4", "op": "backup", "params": {"tag": "Before_Update", "incremental": true}}

// 认证握手（连接后第一条消息必须为 auth）
{"tx_id": null, "op": "auth", "params": {"token": "..."}}
→ {"tx_id": null, "status": "ok", "message": "authenticated"}

// 响应
{"tx_id": "a1b2c3d4", "status": "ok", "data": {"snapshot_id": "snap_..."}}
```

### 15 种操作码

`backup` `status` `restore` `diff` `browse` `top` `verify` `pin` `clone` `rollback` `cancel` `forecast` `export` `import` `auth`

### IpcClient 线程安全设计

- `AtomicBoolean connected` — 保证读线程与主线程之间的可见性
- 读线程显式捕获 `ClosedChannelException` / `InterruptedException` — 优雅退出
- `disconnect()` 顺序：置位 → interrupt → join(500ms) → close 资源
- 异步 `sendRequest`（回调）+ 同步 `sendRequestSync`（阻塞）双模式

---

## 五、Rust Sidecar 守护进程

`sidecar/src/` — 约 3,900 行 Rust，tokio 异步运行时

### 5.1 IPC Server (`ipc/server.rs`)

- `UnixListener` + tokio 异步 accept 循环
- **令牌认证握手**：连接后首条消息必须携带有效 token，否则拒绝
- `verify_token` 使用**常数时间比较**（逐字节 XOR 折叠）防时序攻击
- 15 种操作码分发（`dispatch` match）

### 5.2 ACID 事务管理 (`backup/transaction.rs`)

```
BEGIN    → 清空瞬态对象表，分配 TxID，记录开始时间
EXECUTE  → 流式分块、去重、写入（由 BackupEngine 驱动）
COMMIT   → RocksDB WAL flush_wal(true) — 关键持久性保证
ROLLBACK → 遍历瞬态对象，decrement_ref；引用归零后由 GC 清理
```

- `TransactionState`: `Pending → Active → Committing → Committed` 或 `→ Aborted → RollingBack`
- 瞬态对象（TransientObject）追踪，防止中止事务产生悬挂对象
- `begin()` 为 async（`.lock().await`），消除 `try_lock().expect()` panic 风险
- 移除 `Default::default()` 的 panic（违反 Rust 约定），改为 `empty()` 测试专用方法

### 5.3 备份流水线 (`backup/mod.rs`)

```
SCAN → CHUNK + DEDUP（并行）→ COMMIT
```

**并行处理**：`tokio::spawn` + `Semaphore(MAX_CONCURRENT_FILES=4)` 限制并发，每文件独立 task 处理。

**分块去重流程**（每文件）：
1. 流式读取（64KB BufReader buffer）
2. FastCDC 内容定义分块
3. 对每个 chunk 查 RocksDB `chunk_exists`
4. 已存在 → `increment_ref`（去重命中）
5. 不存在 → 写对象存储 + `insert_chunk` + 登记瞬态对象
6. 记录文件→chunk 映射

**`BackupResult` 字段**：`snapshot_id / files_scanned / files_changed / files_skipped / bytes_processed / chunks_deduped / chunks_new / duration_ms`

### 5.4 路径穿越防护

```rust
fn validate_safe_path(base: &Path, user_path: &str) -> Result<PathBuf> {
    // 1. 拒绝空路径
    // 2. 拒绝含 ".." 的路径
    // 3. 拒绝绝对路径（/ 或 \ 开头）
    // 4. canonicalize 后验证 resolved.starts_with(base)
}
```

应用于：restore 文件路径、chunk 坐标、clone 世界名、export/import 路径。

### 5.5 FastCDC 分块引擎 (`backup/chunker.rs`)

| 参数 | 值 | 理由 |
|------|-----|------|
| min_size | 4 KB | 避免过多小 chunk |
| avg_size | 64 KB | 去重率与开销平衡 |
| max_size | 256 KB | 限制最坏情况内存 |

- Gear-hash 滚动哈希 + `hash & mask == 0` 边界判定
- BLAKE3 内容哈希（内容寻址）
- 确定性：相同内容 → 相同 chunk（已测）

### 5.6 RocksDB 索引 (`storage/index.rs`) — 5 列族

| 列族 | 键 → 值 | 用途 |
|------|---------|------|
| `file_chunks` | 文件路径 → JSON chunk 哈希数组 | 恢复时重组文件 |
| `chunk_refs` | chunk 哈希 → u64 引用计数 (LE) | 去重 + GC |
| `chunk_meta` | chunk 哈希 → {path, offset, size} | 元数据 |
| `snapshot` | 快照 ID → 文件列表 | diff/browse |
| `file_meta` | 文件路径 → {size, mtime, chunk_count} | top 分析 |

关键方法：`insert_file_chunks` / `get_file_chunks` / `chunk_exists` / `increment_ref` / `decrement_ref` / `get_ref_count` / `flush_wal` / `get_largest_files`

### 5.7 CAS 对象存储 (`storage/object_store.rs`)

- 对象初始以**独立文件**写入（快速路径）
- Packfile 生命周期：写入 → 达 512MB 上限 → Seal（CRC32C footer + .idx 索引）→ 只读
- 去重率统计：`(raw_bytes - stored_bytes) / raw_bytes * 100`

---

## 六、游戏端四加载器实现

### 6.1 NeoForge（Kotlin）— 功能最全

| 文件 | 职责 |
|------|------|
| `ObsidianBackupMod.kt` | 主入口，UDS 连接生命周期，server tick 轮询 IPC |
| `command/ObsidianCommandRoot.kt` | **12 条 Brigadier 指令树**（最完整） |
| `ipc/IpcClient.kt` | UDS IPC 客户端（AtomicBoolean 版） |
| `ipc/IpcProtocol.kt` | 12 操作码 + 请求/响应模型 |
| `hook/BackupHooks.kt` | 5 个 Forge 事件（Before/After/Failed/Restore） |
| `ui/BossBarIndicator.kt` | BossBar 进度（固定 UUID 复用，无泄漏） |
| `ui/ChatRenderer.kt` | 富文本渲染（status/top/diff） |

**完整指令矩阵**（NeoForge 独占）：
```
/obsidian status            # 流水线实时状态诊断
/obsidian top [limit]       # 存储热力图 TOP
/obsidian forecast          # 存储容量预测
/obsidian backup [--tag] [--full|--cancel]
/obsidian restore <id> [--file <path>|--chunk <coord>]
/obsidian diff <a> <b>      # 快照差异对比
/obsidian browse <id> [path]
/obsidian clone <id> <name>
/obsidian rollback --duration <1m>
/obsidian verify [repair]
/obsidian pin <id> --days <n>
/obsidian snapshot export|import <path>
```

### 6.2 Fabric / Forge / Bukkit — 精简版

三者在 common 模块基础上实现**核心指令子集**（status/backup/restore/top/diff/verify/forecast/cancel），保持各平台 API 原生风格：

| 加载器 | 指令注册方式 | 事件系统 |
|--------|-------------|---------|
| Fabric | `CommandRegistrationCallback` (Fabric API) | `ServerLifecycleEvents` + `ServerTickEvents` |
| Forge | `RegisterCommandsEvent` (Forge bus) | `ServerStartedEvent` + `ServerTickEvent` |
| Bukkit | `CommandExecutor` + `TabCompleter` | `Bukkit.getScheduler()` + `plugin.yml` 权限 |

---

## 七、安全设计

| 防护 | 实现 |
|------|------|
| 零外露端口 | 仅本地 UDS socket，无 TCP/HTTP |
| IPC 认证 | 共享令牌 + 常数时间比较 |
| 路径穿越防护 | `validate_safe_path`（.. / 绝对路径 / canonicalize 双重校验） |
| 快照 ID 白名单 | 仅允许 `[a-zA-Z0-9_-]` |
| 常量时间比较 | 逐字节 XOR 折叠，防时序攻击 |
| `.gitignore` 排除 | 运行时数据（socket/rocksdb/store/sandbox）不入库 |

---

## 八、测试与 CI

### 单元测试（Rust）
- `chunker`: 空数据 / 小文件单块 / 大文件多块 / 确定性
- `scanner`: 排除规则（session.lock 应被排除）
- `index`: 文件块往返 / 引用计数递增递减
- `transaction`: 生命周期 / 瞬态对象清理

### 集成测试（`sidecar/tests/integration_test.rs` — 8 个）
- 完整备份+恢复循环
- 状态报告正确性
- 存储热力图分析
- 完整性校验（verify）
- 路径穿越拒绝
- 非法克隆名拒绝
- 并发备份不死锁
- 预测需两个快照

### CI（`.github/workflows/build.yml`）
```yaml
matrix:
  mc: ['1.21.1', '1.20.1']
  loader: [fabric, forge, bukkit]
```
- Rust: sidecar + client-cli (build/test/clippy)
- Java: 各加载器 × 各 MC 版本（自动切换 JDK 21/17）
- common: 独立构建

---

## 九、构建与部署

```bash
# 1. 构建 Rust Sidecar（Linux）
cd sidecar && cargo build --release

# 2. 构建 CLI
cd client-cli && cargo build --release

# 3. 构建游戏端（1.21.1）
gradle :fabric:build :forge:build :bukkit:build

# 4. 或 1.20.1
gradle :fabric:build :bukkit:build -Pmc=1.20.1

# 5. 部署流程
#   a. 将 sidecar 二进制放入服务端根目录
#   b. 将 mod/plugin jar 放入 mods/plugins 目录
#   c. 首次启动 Sidecar：obsidian-sidecar --server-root /path/to/server
#   d. 启动游戏服务端，mod 自动连接 UDS
#   e. 游戏内执行 /obsidian backup
```

### Sidecar 启动参数

```
obsidian-sidecar [--config <path>] [--socket <path>] [--server-root <path>]
                 [--log-level <level>] [--oneshot] [--tag <tag>]
```

---

## 十、目录结构与文件清单

```
ObsidianBackup/
├── .github/workflows/build.yml      CI 多版本构建矩阵
├── gradle.properties                构建属性（mc=1.21.1）
├── settings.gradle                  Gradle 根项目（5 子项目）
├── versions.gradle                  版本矩阵中枢
├── mainidea.md                      原始需求文档
├── TECHNICAL_SUMMARY.md            本文档
├── common/                          ★ 共享 Java 库
│   └── src/main/java/.../common/
│       ├── IpcProtocol.java
│       ├── IpcClient.java
│       └── ObsidianConfig.java
├── mod-neoforge/                    ★ NeoForge 1.21.1 (Kotlin)
│   └── src/main/kotlin/.../backup/
│       ├── ObsidianBackupMod.kt
│       ├── command/ObsidianCommandRoot.kt
│       ├── config/ModConfig.kt
│       ├── hook/BackupHooks.kt
│       ├── ipc/IpcClient.kt
│       ├── ipc/IpcProtocol.kt
│       ├── ui/BossBarIndicator.kt
│       └── ui/ChatRenderer.kt
├── fabric/                          ★ Fabric (Java)
├── forge/                           ★ Forge (Java)
├── bukkit/                          ★ Paper/Bukkit (Java)
├── sidecar/                         ★ Rust 守护进程
│   ├── Cargo.toml
│   ├── src/
│   │   ├── main.rs                  daemon/oneshot 双模式
│   │   ├── lib.rs                   库入口（供集成测试）
│   │   ├── config.rs                YAML 配置 + glob 排除规则
│   │   ├── ipc/server.rs            UDS + 令牌认证 + 15 操作码
│   │   ├── backup/mod.rs            BackupEngine 核心（约 1300 行）
│   │   ├── backup/chunker.rs        FastCDC + BLAKE3
│   │   ├── backup/scanner.rs        增量扫描 + 排除
│   │   ├── backup/transaction.rs    ACID 事务
│   │   ├── storage/index.rs         RocksDB 5 列族
│   │   └── storage/object_store.rs  CAS + Packfile
│   └── tests/integration_test.rs    8 个集成测试
└── client-cli/                      ★ Rust CLI
    └── src/main.rs                  15 种子命令
```

---

## 十一、关键设计决策记录

| 决策 | 权衡 | 结论 |
|------|------|------|
| Rust 写 Sidecar | 性能 vs 开发速度 | 独立进程 + 内存安全 + 高性能，值得 |
| UDS 而非 TCP | 本机进程隔离 + 零端口 | 符合"零外露"铁律 |
| BLAKE3 而非 SHA256 | 速度 vs 标准兼容 | BLAKE3 是内容寻址的理想选择（速度快 3 倍） |
| 自研 CDC 而非 fastcdc crate | 控制 vs 依赖 | 自研 Gear-hash，注释标注生产可换 fastcdc |
| JSON over UDS 而非二进制 | 简单可调试 vs 效率 | Phase 1 优先可调试性，性能瓶颈在磁盘非协议 |
| `-Pmc` 属性而非多源集 | 简洁 vs 复杂度 | 单源集 + 版本矩阵切换，避免代码重复 |
| common 编译 Java 17 | 兼容性 vs 新特性 | 最低公分母，确保 1.20.1/1.21.1 双兼容 |

---

## 十二、已知限制与后续规划

### 当前限制
1. **分块器非真流式**：`read_file_buffered` 仍将全文件读入内存（>16MB 文件有 OOM 风险），需重构 ChunkEngine 接受 `impl Read`。
2. **Packfile 密封为占位**：`maybe_seal_packfiles` 只做计数判断，实际密封逻辑未实现。
3. **Ed25519 签名未落地**：`snapshot_signing.enabled=true` 但无密钥路径，实际签名未执行。
4. **RS(8+2) 纠删码未实现**：verify repair 仅警告。
5. **NeoForge 未接入 versions.gradle**：仅支持 1.21.1，需在 file 内注明。

### Roadmap（对应 mainidea.md 四阶段）
| 阶段 | 状态 | 交付 |
|------|------|------|
| Phase 1 事务型内核 | ✅ 完成 | 分块 + RocksDB + Sidecar + ACID 事务 |
| Phase 2 原生人机交互 | 🚧 部分 | Brigadier 指令树 ✅ / BossBar ✅ / Packfile 密封 ⏳ |
| Phase 3 企业自愈防护 | ⏳ 待做 | Ed25519 签名 / 双人审计 / WORM 锁 / 远端加密同步 |
| Phase 4 高级数据编排 | ⏳ 待做 | 单 Chunk 恢复 ✅ / 世界克隆 ✅ / 自适应调度器 ⏳ |
