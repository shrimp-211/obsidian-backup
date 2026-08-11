# Obsidian Backup — MCDReforged 插件

通过 Unix Domain Socket (UDS) 桥接 [Obsidian Sidecar](../README.md) 备份引擎，
为 MCDReforged 管理的服务器提供**完整功能**的备份能力。

## 安装

1. 将 `mcdr_backup` 文件夹放入 MCDR 的 `plugins/` 目录
2. 修改 `mcdr_backup/config.json`（socket 路径 / token / 自动备份）
3. 重启 MCDR 或 `!!MCDR reload plugin mcdr_backup`

> 前置：已启动 Obsidian Sidecar 守护进程（见 [obsidian-backup_server](https://github.com/shrimp-211/obsidian-backup_server)）。

## 配置（config.json）

```json
{
    "sidecar_socket": ".obsidian/ipc/obsidian.sock",
    "sidecar_token": "obsidian-default-token",
    "request_timeout_seconds": 60,
    "auto_backup_enabled": true,
    "auto_backup_interval_minutes": 30,
    "auto_backup_tag_prefix": "auto",
    "auto_backup_keep": 10,
    "notify_ops_only": false
}
```

- `auto_backup_*`：定时自动备份与保留策略（保留最近 N 个自动备份）。
- 保留超出的候选快照会打印到日志；Sidecar 无远程 GC 时请人工清理。

## 指令（全功能）

| 指令 | 说明 |
|------|------|
| `!!backup [--tag <t>] [--full] [--cancel]` | 手动备份 / 全量 / 取消 |
| `!!backup help` | 帮助 |
| `!!status` | 流水线实时状态 |
| `!!restore <id> [--file <p>\|--chunk <c>]` | 恢复快照（文件 / 区块 / 全量） |
| `!!verify [repair]` | 完整性巡检 + RS(8+2) 自愈 |
| `!!top [limit]` | 存储热力图 |
| `!!diff <a> <b>` | 快照差异 |
| `!!forecast` | 容量预测 |
| `!!pin <id> --days <n>` | WORM 锁定 |
| `!!clone <id> <name>` | 世界克隆 |
| `!!rollback --duration <d>` | 近线回滚 |
| `!!snapshot export\|import <p>` | 归档导出 / 导入 |
| `!!remote-sync push\|pull <id> \| serve` | 远程同步（有公网 IP 一方 serve） |

所有破坏性操作（备份 / 恢复 / 回滚 / 克隆 / 同步）需要 OP 权限（`has_permission(2)`）。
