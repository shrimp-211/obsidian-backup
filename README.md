# 💎 Obsidian Backup — Minecraft 服务端灾备

> 企业级 Minecraft **内容寻址（CAS）增量备份**游戏端桥接层。
>
> [![License: MPL-2.0](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)
> [![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1--26.2-green)]()

本仓库为 **Minecraft 服务端一侧**的加载器实现（Fabric / Forge / Bukkit / NeoForge / MCDR），
通过本地 **IPC** 与独立的 [Obsidian Sidecar 备份守护进程](https://github.com/shrimp-211/obsidian-backup_server)
通信，实现零侵入、零外露端口的备份 / 恢复 / 分析能力。

- **Linux / macOS**：Unix Domain Socket (UDS)
- **Windows**：Named Pipe

备份引擎（Rust）位于独立的 [obsidian-backup_server](https://github.com/shrimp-211/obsidian-backup_server) 仓库，
游戏主进程仅保留轻量逻辑桥，彻底杜绝备份引发的 JVM GC 停顿与 TPS 掉帧。

---

## 📦 支持矩阵

| 加载器 | 1.20.1 | 1.21.1 | 1.21.11 | 26.2 |
|--------|:------:|:------:|:-------:|:----:|
| NeoForge (Kotlin) | — | ✅ | ✅ | ❌¹ |
| Fabric | ✅ | ✅ | ✅ | ✅ |
| Forge | ✅ | ✅ | ❌² | ❌¹ |
| Paper/Bukkit | ✅ | ✅ | ✅ | ✅ |
| MCDR 插件 | ✅ | ✅ | ✅ | ✅ |

> ¹ 26.2 需要 Gradle 9，ForgeGradle 6 / neogradle 尚未适配 Gradle 9。
> ² forge 1.21.11（forge 61）需 ForgeGradle 7，尚未发布。

---

## 📥 下载（获取编译产物）

### 方式一：GitHub Release（推荐，正式发布）

打 tag 后自动生成，永久保存：

- 打开 <https://github.com/shrimp-211/obsidian-backup/releases>
- 下载对应加载器的 jar（如 `obsidian-backup-fabric-1.21.1.jar`）

### 方式二：GitHub Actions Artifacts（日常构建）

每次 push 自动上传，保留 90 天：

- 打开 <https://github.com/shrimp-211/obsidian-backup/actions>
- 点击最新一次成功的 run
- 底部 **Artifacts** 区下载（如 `obsidian-backup-fabric-1.21.1`）

### 方式三：自行构建

```bash
# 主构建（1.20.1 / 1.21.1 / 1.21.11，Gradle 8.14）
./gradlew :fabric:build :forge:build :bukkit:build :neoforge:build      # 默认 1.21.1
./gradlew :fabric:build :forge:build :bukkit:build -Pmc=1.20.1         # 1.20.1
./gradlew :fabric:build :bukkit:build :neoforge:build -Pmc=1.21.11    # 1.21.11

# 26.2 独立构建（Gradle 9.5.1 + Java 25）
cd mc26.2
./gradlew :fabric:build :bukkit:build
```

构建产物位置：

| 加载器 | 产物路径 |
|--------|---------|
| Fabric | `fabric/build/libs/obsidian-backup-fabric-<mc>.jar` |
| Forge | `forge/build/libs/obsidian-backup-forge-<mc>.jar` |
| Bukkit | `bukkit/build/libs/obsidian-backup-bukkit-<mc>.jar` |
| NeoForge | `mod-neoforge/build/libs/obsidian-backup-neoforge.jar` |
| Fabric 26.2 | `mc26.2/fabric/build/libs/obsidian-backup-fabric-26.2.jar` |
| Bukkit 26.2 | `mc26.2/bukkit/build/libs/obsidian-backup-bukkit-26.2.jar` |

---

## 🚀 部署（三步）

### 前提

一个 Minecraft 服务端 + 已下载的 [obsidian-sidecar](https://github.com/shrimp-211/obsidian-backup_server/releases) 二进制。

### 第一步：启动 Sidecar 守护进程

```bash
# 在 Minecraft 服务端根目录下启动（独立进程）
./obsidian-sidecar --server-root /path/to/minecraft/server
```

> Sidecar 会在 `.obsidian/` 下创建 ipc socket、RocksDB 索引、对象存储等运行时数据。

### 第二步：放入 mod / plugin

```bash
# Fabric / Forge / NeoForge
cp obsidian-backup-fabric-1.21.1.jar /path/to/server/mods/

# Bukkit / Paper
cp obsidian-backup-bukkit-1.21.1.jar /path/to/server/plugins/
```

### 第三步：启动游戏服务端

服务端启动后，mod 自动连接 Sidecar 的 IPC，完成令牌认证握手。控制台应看到：

```
[Obsidian Backup] Connected and authenticated at .obsidian/ipc/obsidian.sock
```

---

## ⚙️ 配置

配置通过 **JVM 系统属性**（`-D` 参数）覆盖，均含默认值：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `obsidian.socket` | `.obsidian/ipc/obsidian.sock` | Sidecar 的 IPC 地址（Unix socket 路径 / Windows pipe 名） |
| `obsidian.token` | `obsidian-default-token` | IPC 认证令牌（须与 Sidecar 一致） |
| `obsidian.connect_timeout` | `5000` | 连接超时（毫秒） |
| `obsidian.request_timeout` | `30000` | 请求超时（毫秒） |

示例（在启动脚本中）：

```bash
java -Dobsidian.socket=.obsidian/ipc/obsidian.sock \
     -Dobsidian.token=my-secret-token \
     -jar server.jar nogui
```

> ⚠️ 生产环境请**务必修改默认 token**，并在 Sidecar 侧配置相同的 token。

---

## 🎮 游戏内指令

游戏内（或服务器控制台）执行 `/obsidian` 系列指令。**需要 OP 权限（level 2+）**。

### 备份

```
/obsidian backup                      # 增量备份
/obsidian backup --tag BeforeUpdate   # 带标签备份
/obsidian backup --full               # 全量备份
/obsidian backup --cancel             # 取消当前备份事务
```

### 状态与诊断

```
/obsidian status            # 流水线实时状态（队列/吞吐/TPS）
/obsidian top [limit]       # 存储热力图 TOP（默认 5）
/obsidian forecast          # 存储容量预测
/obsidian diff <a> <b>      # 两个快照差异对比
/obsidian browse <id> [path] # 浏览快照文件树
```

### 恢复

```
/obsidian restore <id>                          # 全量恢复（沙箱+原子切换）
/obsidian restore <id> --file world/region/r.0.0.mca   # 单文件恢复
/obsidian restore <id> --chunk 0:0,0             # 单区块恢复
/obsidian rollback --duration 5m                 # 近线闪回（5 分钟前）
```

### 数据管理

```
/obsidian verify [repair]    # 完整性巡检 + RS(8+2) 纠删码自愈
/obsidian pin <id> --days 30 # WORM 锁定 30 天（防 GC 删除）
/obsidian clone <id> <name>  # 世界秒级克隆
/obsidian snapshot export <path>  # 导出归档
/obsidian snapshot import <path>  # 导入归档
```

### 远程同步

```
/obsidian remote-sync serve            # 公网 IP 一侧：启动监听
/obsidian remote-sync push <id>        # 另一侧：推送快照
/obsidian remote-sync pull <id>        # 另一侧：拉取快照
```

> 远程同步为**双向主动**：拥有公网 IP 的一方执行 `serve`，另一方执行 `push`/`pull`。
> 传输用 XChaCha20-Poly1305 加密 + 共享令牌认证。

---

## 🖥️ Windows 说明

| 平台 | IPC 机制 | 说明 |
|------|---------|------|
| Linux / macOS | Unix Domain Socket | socket 文件路径（如 `.obsidian/ipc/obsidian.sock`） |
| Windows | Named Pipe | pipe 名称（自动从 socket 路径转换） |

Windows 上 mod 会自动使用 Named Pipe，无需额外配置。Sidecar 二进制用 MSVC 构建（见备份服务端仓库的 Windows Release 产物）。

---

## 🔒 安全

- **零外露端口**：仅本地 IPC，无 TCP/HTTP 监听。
- **IPC 认证**：连接即认证握手，共享令牌 + 常数时间比较，防时序攻击。
- **快照防篡改**：manifest Ed25519 签名，恢复 / 巡检前强制验证。
- **数据自愈**：RS(8+2) 纠删码分片存储，最多自愈 2 个分片。
- **路径穿越防护**：restore / clone / export 路径双重校验。

---

## 🏗️ 架构

```
Minecraft Server (本仓库, Java/Kotlin)
├── NeoForge / Fabric / Forge / Bukkit / MCDR
├── common/ 共享 Java 库（零 MC 依赖）
└── Brigadier 指令树 (/obsidian …)
        │  IPC (UDS / Named Pipe) JSON
        ▼
Obsidian Sidecar (Rust, 独立仓库 obsidian-backup_server)
├── BackupEngine — 扫描→分块→去重→存储→事务
├── ChunkEngine — FastCDC 内容定义分块 + BLAKE3
├── RocksDB 5 列族块索引 + CAS 对象存储 + Packfile
├── ACID 事务 + Ed25519 签名 + RS(8+2) 纠删码
└── RemoteSync — 双向主动发送, XChaCha20-Poly1305 加密
```

---

## 📖 文档

- [TECHNICAL_SUMMARY.md](TECHNICAL_SUMMARY.md) — 技术实现总结
- [mainidea.md](mainidea.md) — 原始需求与设计文档
- [mcdr/README.md](mcdr/README.md) — MCDR 插件使用说明
- [obsidian-backup_server](https://github.com/shrimp-211/obsidian-backup_server) — Rust 备份引擎仓库

## 🤝 贡献

欢迎提交 Issue 与 Pull Request，请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 📄 许可证

[Mozilla Public License 2.0](LICENSE) (MPL-2.0)
