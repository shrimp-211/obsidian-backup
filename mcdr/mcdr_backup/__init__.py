# -*- coding: utf-8 -*-
"""Obsidian Backup — MCDReforged plugin.

Bridges MCDR to the Obsidian Sidecar daemon over UDS IPC, exposing the full
feature set as `!!backup` commands:

    !!backup [--tag <t>] [--full] [--cancel]   manual backup / cancel
    !!status                                   pipeline status
    !!restore <id> [--file <p>|--chunk <c>]    restore a snapshot
    !!verify [repair]                          integrity check + RS repair
    !!top [limit]                              storage heat-map
    !!diff <a> <b>                             snapshot diff
    !!forecast                                 capacity forecast
    !!pin <id> --days <n>                      WORM pin
    !!clone <id> <name>                        clone a world
    !!rollback --duration <d>                  near-line rollback
    !!snapshot export|import <p>               archive export / import
    !!remote-sync push|pull <id> | serve       peer sync (public-IP side)

Plus optional interval-based auto-backup with a retention policy.
"""

import json
import os
import threading
import time
from typing import Optional

from mcdreforged.api.all import (
    CommandContext,
    CommandSource,
    GreedyText,
    Integer,
    Literal,
    PluginServerInterface,
    RText,
    RTextList,
    Text,
    RColor,
)

from .ipc_client import IpcClient, IpcError

PLUGIN_METADATA = {
    "id": "mcdr_backup",
    "version": "0.1.0",
    "name": "Obsidian Backup",
}

DEFAULT_CONFIG = {
    "sidecar_socket": ".obsidian/ipc/obsidian.sock",
    "sidecar_token": "obsidian-default-token",
    "request_timeout_seconds": 60,
    "auto_backup_enabled": True,
    "auto_backup_interval_minutes": 30,
    "auto_backup_tag_prefix": "auto",
    "auto_backup_keep": 10,
    "notify_ops_only": False,
}

CONFIG_FILE = "config.json"
HISTORY_FILE = "backup_history.json"

_config = dict(DEFAULT_CONFIG)
_scheduler = None
_backup_lock = threading.Lock()


# =========================================================================
# IPC helpers
# =========================================================================

def _client() -> IpcClient:
    return IpcClient(
        socket_path=_config["sidecar_socket"],
        token=_config["sidecar_token"],
        timeout=float(_config["request_timeout_seconds"]),
    )


def _run(op: str, params=None) -> dict:
    client = _client()
    try:
        return client.request(op, params)
    finally:
        client.close()


# =========================================================================
# Pretty printers
# =========================================================================

def _fmt_bytes(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024.0 or unit == "TB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024.0
    return f"{n:.1f} TB"


def _reply(src: CommandSource, text) -> None:
    src.reply(RText(text))


def _ok(src, msg) -> None:
    _reply(src, RText("✓ ").set_color(RColor.green) + msg)


def _err(src, msg) -> None:
    _reply(src, RText("✗ ").set_color(RColor.red) + msg)


# =========================================================================
# Command handlers
# =========================================================================

def cmd_backup(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    if ctx.get("cancel"):
        _run_op(src, "cancel", {}, "备份事务已终止并回滚")
        return
    # Serialise manual + auto backups to avoid concurrent transactions.
    if not _backup_lock.acquire(blocking=False):
        _err(src, "已有备份正在进行，请稍候")
        return
    tag = ctx.get("tag")
    incremental = not bool(ctx.get("full"))
    try:
        data = _run_op(
            src,
            "backup",
            {"tag": tag, "incremental": incremental},
            None,
        )
    finally:
        _backup_lock.release()
    if data:
        snap = data.get("snapshot_id", "?")
        _ok(
            src,
            RTextList(
                RText(f"备份完成! 快照 {snap} | "),
                RText(f"文件 {data.get('files_scanned', 0)} | "),
                RText(f"大小 {_fmt_bytes(data.get('bytes_processed', 0))} | "),
                RText(f"新块 {data.get('chunks_new', 0)} / 去重 {data.get('chunks_deduped', 0)} | "),
                RText(f"耗时 {data.get('duration_ms', 0) / 1000.0:.1f}s"),
            ),
        )
        _record_history(snap, tag)


def cmd_status(src: CommandSource, ctx: CommandContext) -> None:
    data = _run_op(src, "status", {}, None)
    if not data:
        return
    queue = data.get("queue_status", {})
    storage = data.get("storage_stats", {})
    _reply(
        src,
        RTextList(
            RText(f"── Obsidian 状态 ──\n"),
            RText(f"状态: {data.get('state', 'idle')}"),
            RText(f" | TPS: {data.get('tps', 0):.2f}"),
            RText(f" | CPU: {data.get('cpu_percent', 0):.1f}%\n"),
            RText(f"队列: 扫描{queue.get('scanner', 0)} 分块{queue.get('chunk', 0)} "
                  f"压缩{queue.get('compress', 0)} 加密{queue.get('encrypt', 0)} "
                  f"上传{queue.get('upload', 0)}\n"),
            RText(f"存储: 快照{storage.get('total_snapshots', 0)} | "
                  f"大小{_fmt_bytes(storage.get('total_size_bytes', 0))} | "
                  f"去重{storage.get('dedup_ratio', 0):.1f}%"),
        ),
    )


def cmd_restore(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    snap_id = ctx.get("id", "latest")
    params = {"snapshot_id": snap_id, "file_path": ctx.get("path"), "chunk_coord": ctx.get("coord")}
    data = _run_op(src, "restore", params, None)
    if data:
        _ok(
            src,
            RTextList(
                RText(f"恢复完成! 文件 {data.get('files_restored', 0)} | "),
                RText(f"大小 {_fmt_bytes(data.get('bytes_restored', 0))}"),
            ),
        )


def cmd_verify(src: CommandSource, ctx: CommandContext) -> None:
    repair = bool(ctx.get("repair"))
    data = _run_op(src, "verify", {"repair": repair}, None)
    if data:
        _ok(
            src,
            RTextList(
                RText(f"巡检完成: 总计{data.get('total_checked', 0)} "
                      f"健康{data.get('healthy', 0)} "
                      f"损坏{data.get('corrupted', 0)}"),
                RText(f" 已修复{data.get('repaired', 0)}" if repair else ""),
            ),
        )


def cmd_top(src: CommandSource, ctx: CommandContext) -> None:
    limit = ctx.get("limit") or 5
    data = _run_op(src, "top", {"limit": limit}, None)
    if data:
        _reply(src, RText(f"── 存储热力图 TOP (去重 {data.get('dedup_ratio', 0):.1f}%) ──")
               .set_color(RColor.gold))
        for i, f in enumerate(data.get("files", []), 1):
            reason = f.get("reason") or ""
            _reply(src, RTextList(RText(f"{i}. {f.get('path', '?')} "),
                                  RText(f"[{_fmt_bytes(f.get('size', 0))}]").set_color(RColor.aqua),
                                  RText(f" ({reason})" if reason else "")))


def cmd_diff(src: CommandSource, ctx: CommandContext) -> None:
    data = _run_op(src, "diff", {"id_a": ctx.get("a"), "id_b": ctx.get("b")}, None)
    if data:
        _reply(src, RText("── 快照差异 ──").set_color(RColor.gold))
        for p in data.get("added", []):
            _reply(src, RText(f"  + {p}").set_color(RColor.green))
        for p in data.get("modified", []):
            _reply(src, RText(f"  * {p}").set_color(RColor.yellow))
        for p in data.get("deleted", []):
            _reply(src, RText(f"  - {p}").set_color(RColor.red))


def cmd_forecast(src: CommandSource, ctx: CommandContext) -> None:
    data = _run_op(src, "forecast", {}, None)
    if data:
        _ok(src, RTextList(
            RText(f"容量 {data.get('total_capacity_gb', 0):.1f} GB | "),
            RText(f"增长 {data.get('growth_rate_mb_per_day', 0):.1f} MB/天 | "),
            RText(f"剩余 {data.get('days_remaining', 0):.1f} 天"),
        ))


def cmd_pin(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    _run_op(src, "pin", {"snapshot_id": ctx.get("id"), "days": ctx.get("days") or 30},
            "快照已 WORM 锁定")


def cmd_clone(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    _run_op(src, "clone", {"snapshot_id": ctx.get("id"), "new_name": ctx.get("name")},
            "世界克隆完成")


def cmd_rollback(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    _run_op(src, "rollback", {"duration": ctx.get("duration", "1m")}, "回滚完成")


def cmd_snapshot(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    action = ctx.get("action")
    path = ctx.get("path")
    _run_op(src, action, {"path": path}, f"快照{action}完成")


def cmd_remote_sync(src: CommandSource, ctx: CommandContext) -> None:
    if not src.has_permission(2):
        _err(src, "需要 OP 权限")
        return
    action = ctx.get("action")
    snap_id = ctx.get("id")
    params = {"action": action, "snapshot_id": snap_id}
    if action == "serve":
        params = {"action": "serve"}
    _run_op(src, "remote_sync", params, f"远程同步({action})完成")


def _run_op(src: CommandSource, op: str, params: dict, ok_msg: str):
    """Send an op, print ok/err. Returns `data` on success or None."""
    try:
        resp = _run(op, params)
    except IpcError as exc:
        _err(src, str(exc))
        return None
    data = resp.get("data")
    if ok_msg:
        _ok(src, ok_msg)
    return data


# =========================================================================
# Retention policy (auto-backup history)
# =========================================================================

def _history_path() -> str:
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), HISTORY_FILE)


def _load_history() -> list:
    try:
        with open(_history_path(), "r", encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return []


def _save_history(items: list) -> None:
    try:
        with open(_history_path(), "w", encoding="utf-8") as fh:
            json.dump(items, fh, ensure_ascii=False, indent=2)
    except OSError:
        pass


def _record_history(snap_id: str, tag) -> None:
    if not (tag or "").startswith(_config["auto_backup_tag_prefix"]):
        return  # only track auto backups for retention
    items = _load_history()
    items.append({"snapshot_id": snap_id, "at": time.strftime("%Y-%m-%dT%H:%M:%S")})
    keep = int(_config["auto_backup_keep"])
    if len(items) > keep:
        expired = items[:-keep]
        items = items[-keep:]
        _save_history(items)
        # Sidecar has no remote GC yet — surface the candidates for pruning.
        print(f"[ObsidianBackup] {len(expired)} auto backups exceed retention ({keep}); "
              f"prune candidates: {[e['snapshot_id'] for e in expired]}")
    else:
        _save_history(items)


# =========================================================================
# Auto backup scheduler
# =========================================================================

class _AutoBackupScheduler:
    def __init__(self, server: PluginServerInterface):
        self.server = server
        self._timer: Optional[threading.Timer] = None
        self._stopped = False

    def start(self) -> None:
        self._schedule()

    def stop(self) -> None:
        self._stopped = True
        if self._timer is not None:
            self._timer.cancel()

    def _schedule(self) -> None:
        if self._stopped:
            return
        interval = int(_config["auto_backup_interval_minutes"]) * 60
        self._timer = threading.Timer(interval, self._tick)
        self._timer.daemon = True
        self._timer.start()

    def _tick(self) -> None:
        if self._stopped:
            return
        self.server.execute("!!backup --tag {0}".format(_config["auto_backup_tag_prefix"]))
        self._schedule()


# =========================================================================
# Command registration
# =========================================================================

def register_commands(server: PluginServerInterface) -> None:
    # !!backup
    server.register_command(
        Literal("!!backup")
        .then(Literal("--tag").then(GreedyText("tag").runs(cmd_backup)))
        .then(Literal("--full").runs(cmd_backup))
        .then(Literal("--cancel").runs(lambda s, c: cmd_backup(s, {"cancel": True})))
        .runs(cmd_backup)
    )
    server.register_command(Literal("!!status").runs(cmd_status))
    server.register_command(
        Literal("!!restore")
        .then(
            Text("id")
            .then(Literal("--file").then(GreedyText("path").runs(cmd_restore)))
            .then(Literal("--chunk").then(Text("coord").runs(cmd_restore)))
            .runs(cmd_restore)
        )
    )
    server.register_command(
        Literal("!!verify")
        .then(Literal("repair").runs(lambda s, c: cmd_verify(s, {"repair": True})))
        .runs(lambda s, c: cmd_verify(s, {"repair": False}))
    )
    server.register_command(
        Literal("!!top")
        .then(Integer("limit").runs(cmd_top))
        .runs(cmd_top)
    )
    server.register_command(
        Literal("!!diff")
        .then(Text("a").then(Text("b").runs(cmd_diff)))
    )
    server.register_command(Literal("!!forecast").runs(cmd_forecast))
    server.register_command(
        Literal("!!pin")
        .then(Text("id").then(Literal("--days").then(Integer("days").runs(cmd_pin))))
    )
    server.register_command(
        Literal("!!clone")
        .then(Text("id").then(Text("name").runs(cmd_clone)))
    )
    server.register_command(
        Literal("!!rollback")
        .then(Literal("--duration").then(GreedyText("duration").runs(cmd_rollback)))
    )
    server.register_command(
        Literal("!!snapshot")
        .then(
            Literal("export").then(
                GreedyText("path").runs(
                    lambda s, c: cmd_snapshot(s, {"action": "export", "path": c.get("path")})
                )
            )
        )
        .then(
            Literal("import").then(
                GreedyText("path").runs(
                    lambda s, c: cmd_snapshot(s, {"action": "import", "path": c.get("path")})
                )
            )
        )
    )
    server.register_command(
        Literal("!!remote-sync")
        .then(
            Literal("push").then(
                Text("id").runs(
                    lambda s, c: cmd_remote_sync(s, {"action": "push", "id": c.get("id")})
                )
            )
        )
        .then(
            Literal("pull").then(
                Text("id").runs(
                    lambda s, c: cmd_remote_sync(s, {"action": "pull", "id": c.get("id")})
                )
            )
        )
        .then(
            Literal("serve").runs(
                lambda s, c: cmd_remote_sync(s, {"action": "serve"})
            )
        )
    )


HELP_TEXT = RTextList(
    RText("Obsidian Backup (MCDR) 指令:\n", RColor.gold),
    RText("!!backup [--tag <t>] [--full] [--cancel]  手动备份/取消\n", RColor.white),
    RText("!!status                                 流水线状态\n"),
    RText("!!restore <id> [--file <p>|--chunk <c>]  恢复快照\n"),
    RText("!!verify [repair]                        完整性巡检 + RS 自愈\n"),
    RText("!!top [limit]                            存储热力图\n"),
    RText("!!diff <a> <b>                           快照差异\n"),
    RText("!!forecast                               容量预测\n"),
    RText("!!pin <id> --days <n>                    WORM 锁定\n"),
    RText("!!clone <id> <name>                      世界克隆\n"),
    RText("!!rollback --duration <d>                近线回滚\n"),
    RText("!!snapshot export|import <p>             归档导出/导入\n"),
    RText("!!remote-sync push|pull <id> | serve     远程同步\n"),
)


def on_load(server: PluginServerInterface, prev_module):
    global _config, _scheduler
    _config = server.load_config_simple(CONFIG_FILE, default_config=DEFAULT_CONFIG)
    server.register_help_message("!!backup", RText("Obsidian 备份系统 (全功能)"))
    server.register_command(Literal("!!backup help").runs(lambda s, c: _reply(s, HELP_TEXT)))
    register_commands(server)
    if bool(_config["auto_backup_enabled"]):
        _scheduler = _AutoBackupScheduler(server)
        _scheduler.start()
        server.logger.info(
            "[ObsidianBackup] auto backup enabled every {} min (keep {})",
            _config["auto_backup_interval_minutes"],
            _config["auto_backup_keep"],
        )


def on_unload(server: PluginServerInterface):
    global _scheduler
    if _scheduler is not None:
        _scheduler.stop()
        _scheduler = None
