# -*- coding: utf-8 -*-
"""Obsidian Sidecar UDS IPC client (Python).

Talks to the Rust sidecar daemon over a Unix Domain Socket using
newline-delimited JSON — the same protocol as the Java/Kotlin loaders:

    1. connect()  -> first message MUST be an AUTH request with the token
    2. request()  -> send {tx_id, op, params}, await {tx_id, status, data}
"""

import json
import socket
import uuid
from typing import Any, Dict, Optional


class IpcError(Exception):
    """Raised on transport or sidecar-level errors."""


class IpcClient:
    def __init__(self, socket_path: str, token: str, timeout: float = 30.0):
        self.socket_path = socket_path
        self.token = token
        self.timeout = timeout
        self._sock: Optional[socket.socket] = None

    # -- transport ---------------------------------------------------------

    def connect(self) -> None:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(self.timeout)
        try:
            sock.connect(self.socket_path)
        except OSError as exc:
            sock.close()
            raise IpcError(
                f"cannot connect to sidecar at {self.socket_path}: {exc}"
            ) from exc
        self._sock = sock

        # Authentication handshake — the sidecar requires the first message
        # to carry a valid token, otherwise it drops the connection.
        self._send(
            {"tx_id": None, "op": "auth", "params": {"token": self.token}}
        )
        resp = self._recv()
        if resp.get("status") != "ok":
            self.close()
            raise IpcError(f"sidecar auth failed: {resp.get('message')}")

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def request(self, op: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        if self._sock is None:
            self.connect()
        tx_id = uuid.uuid4().hex[:8]
        self._send({"tx_id": tx_id, "op": op, "params": params or {}})
        resp = self._recv()
        if resp.get("status") == "error":
            raise IpcError(f"{op} failed: {resp.get('message', 'unknown error')}")
        return resp

    # -- internals ----------------------------------------------------------

    def _send(self, obj: Dict[str, Any]) -> None:
        assert self._sock is not None
        payload = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
        self._sock.sendall(payload)

    def _recv(self) -> Dict[str, Any]:
        assert self._sock is not None
        buf = b""
        while True:
            chunk = self._sock.recv(65536)
            if not chunk:
                raise IpcError("sidecar closed the connection (EOF)")
            buf += chunk
            if b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                return json.loads(line.decode("utf-8"))
