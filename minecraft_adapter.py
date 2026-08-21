import asyncio
import gc
import json
import re
import sys
import time
import types
import uuid
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web
from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, MessageChain
from astrbot.api.message_components import Plain
from astrbot.api.platform import (
    AstrBotMessage,
    MessageMember,
    MessageType,
    Platform,
    PlatformMetadata,
    register_platform_adapter,
)
try:
    from astrbot.core.platform.message_session import MessageSesion
except ImportError:
    from astrbot.core.platform.astr_message_event import MessageSesion


PROTOCOL_VERSION = 1
QUERY_TIMEOUT_SECONDS = 5.0
SCREENSHOT_QUERY_TIMEOUT_SECONDS = 30.0
LOGO_PATH = str(Path(__file__).resolve().with_name("logo.png"))
MINECRAFT_LEADING_MENTION_RE = re.compile(r"^\s*@(?P<target>[^\s@]+)(?P<body>(?:\s+.*)?)$")
DEFAULT_MENTION_ALIASES = "AstrBot,Aria,astrbot"
MAX_SENDER_NAME_LENGTH = 64
RUNTIME_STATE_MODULE = "_mineastr_astrbot_runtime_state"
PLUGIN_OPERATIONAL_CONFIG_KEYS = (
    "knowledge_sync_enabled",
    "knowledge_embedding_provider_id",
    "modrinth_enrichment_enabled",
    "server_site_sync_enabled",
    "server_site_allowed_paths",
    "server_site_excluded_paths",
    "activity_region_sync_enabled",
    "knowledge_chat_provider_id",
    "agent_actions_enabled",
    "agent_require_admin_approval",
    "agent_observation_distance",
)
DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "path": "/ws",
    "token": "change-me",
    "group_id": "minecraft",
    "group_name": "Minecraft",
    "bot_id": "astrbot",
    "bot_display_name": "AstrBot",
    "mention_aliases": DEFAULT_MENTION_ALIASES,
    "max_message_length": 1000,
    "outbound_max_message_length": 2000,
    "server_event_push_enabled": True,
    "websocket_max_message_bytes": 4194304,
    "screenshot_cooldown_seconds": 10,
    "screenshot_timeout_seconds": 30,
    "knowledge_sync_enabled": True,
    "knowledge_embedding_provider_id": "",
    "modrinth_enrichment_enabled": True,
    "server_site_sync_enabled": True,
    "server_site_allowed_paths": "",
    "server_site_excluded_paths": "/login*\n/account*\n/admin*\n/api/*\n/static/*",
    "activity_region_sync_enabled": True,
    "knowledge_chat_provider_id": "",
    "agent_actions_enabled": True,
    "agent_require_admin_approval": False,
    "agent_observation_distance": 8,
}
CONFIG_METADATA = {
    "host": {
        "description": "WebSocket 监听地址",
        "type": "string",
        "hint": "单机部署通常保持 127.0.0.1；跨机器连接时改为可被 MC 服务器访问的地址。",
        "default": "127.0.0.1",
    },
    "port": {
        "description": "WebSocket 监听端口",
        "type": "int",
        "hint": "需要与 MineAstr Mod 配置中的 websocketUrl 端口一致；端口被占用时可以换成其他未使用端口。",
        "default": 8765,
    },
    "path": {
        "description": "WebSocket 路径",
        "type": "string",
        "hint": "需要与 MineAstr Mod 配置中的 websocketUrl 路径一致；不清楚如何修改时保持 /ws。",
        "default": "/ws",
    },
    "token": {
        "description": "连接认证 Token",
        "type": "string",
        "hint": "Minecraft Mod 连接 AstrBot 时使用，两端必须完全一致；建议把 change-me 改成较长的随机字符串。",
        "default": "change-me",
    },
    "group_id": {
        "description": "AstrBot 群组 ID",
        "type": "string",
        "hint": "所有 Minecraft 聊天都会进入这个虚拟群聊；一般保持 minecraft，改动后会被 AstrBot 视为另一个群。",
        "default": "minecraft",
    },
    "group_name": {
        "description": "AstrBot 群组名称",
        "type": "string",
        "hint": "用于显示这个虚拟 Minecraft 群聊的名称，只影响识别和展示。",
        "default": "Minecraft",
    },
    "bot_id": {
        "description": "机器人 ID",
        "type": "string",
        "hint": "AstrBot 在 minecraft 虚拟平台中的机器人账号 ID；一般不需要修改。",
        "default": "astrbot",
    },
    "bot_display_name": {
        "description": "机器人显示名称",
        "type": "string",
        "hint": "AstrBot 回复广播到 Minecraft 时方括号内显示的名称。",
        "default": "AstrBot",
    },
    "mention_aliases": {
        "description": "Minecraft @ 唤醒别名",
        "type": "string",
        "hint": "玩家在 Minecraft 聊天开头使用这些名字 @ 机器人时，会被转换为 AstrBot 唤醒消息。多个别名用英文逗号分隔，例如 AstrBot,Aria。",
        "default": DEFAULT_MENTION_ALIASES,
    },
    "max_message_length": {
        "description": "最大聊天长度",
        "type": "int",
        "hint": "单条 Minecraft 消息转发到 AstrBot 前允许的最大长度；超出部分会被截断，建议保持默认。",
        "default": 1000,
    },
    "outbound_max_message_length": {
        "description": "广播回游戏的最大长度",
        "type": "int",
        "hint": "AstrBot 回复广播到 Minecraft 前允许的最大长度；过长回复会被截断，避免刷屏或触发客户端显示问题。",
        "default": 2000,
    },
    "server_event_push_enabled": {
        "description": "Minecraft 服务器事件推送",
        "type": "bool",
        "hint": "接收并投递 Mod 发来的玩家上下线、死亡和公开成就事件；Mod 侧仍可分类关闭。",
        "default": True,
    },
    "websocket_max_message_bytes": {
        "description": "WebSocket 单包大小上限",
        "type": "int",
        "hint": "MineAstr 插件接收 Mod WebSocket 消息的最大字节数；截图查询结果也会经过这里，建议保持默认。",
        "default": 4194304,
    },
    "screenshot_cooldown_seconds": {
        "description": "截图请求冷却秒数",
        "type": "int",
        "hint": "同一目标玩家在冷却时间内重复请求截图时，插件会直接拦截，避免连续弹窗和网络压力。",
        "default": 10,
    },
    "screenshot_timeout_seconds": {
        "description": "截图请求超时秒数",
        "type": "int",
        "hint": "等待 Minecraft 客户端返回截图的最长时间；超时后会立即把失败原因返回给模型。",
        "default": 30,
    },
    "knowledge_sync_enabled": {
        "description": "服务器 Mod 知识同步",
        "type": "bool",
        "hint": "自动同步 Mod、物品、方块、标签和配方快照。",
        "default": True,
    },
    "knowledge_embedding_provider_id": {
        "description": "AstrBot Embedding Provider ID",
        "type": "string",
        "hint": "用于自动创建每服务器原生 RAG 知识库；留空时仅使用结构化检索。",
        "default": "",
    },
    "modrinth_enrichment_enabled": {
        "description": "Modrinth 与官方文档补充",
        "type": "bool",
        "hint": "使用 JAR 哈希匹配 Modrinth，并安全抓取其 Wiki 和 GitHub README。",
        "default": True,
    },
    "server_site_sync_enabled": {
        "description": "服务器官网知识同步",
        "type": "bool",
        "hint": "读取 Minecraft 独立服务器配置下发的介绍地址；只抓取同源公网 HTTPS 页面。",
        "default": True,
    },
    "server_site_allowed_paths": {
        "description": "官网允许路径",
        "type": "string",
        "hint": "每行一个 glob；留空表示允许所有同源路径。",
        "default": "",
    },
    "server_site_excluded_paths": {
        "description": "官网排除路径",
        "type": "string",
        "hint": "每行一个 glob；在 AI 选页前强制排除。",
        "default": "/login*\n/account*\n/admin*\n/api/*\n/static/*",
    },
    "activity_region_sync_enabled": {
        "description": "活动地区知识同步",
        "type": "bool",
        "hint": "同步 Minecraft 服务端生成的降精度地区摘要，并发起地区简介征集。",
        "default": True,
    },
    "knowledge_chat_provider_id": {
        "description": "知识分析模型 Provider ID",
        "type": "string",
        "hint": "用于选择官网页面和整理地区简介；留空时使用 AstrBot 默认聊天模型，失败则安全降级为规则选择。",
        "default": "",
    },
    "agent_actions_enabled": {
        "description": "AI 玩家 Agent 操作",
        "type": "bool",
        "hint": "允许 AstrBot 向服务端托管的 Mineflayer提交任务；服务端仍执行最终安全检查。",
        "default": True,
    },
    "agent_require_admin_approval": {
        "description": "Agent 任务要求管理员审批",
        "type": "bool",
        "hint": "推荐开启。开启后只有 AstrBot 管理员上下文可提交会改变 Bot 行为的任务；观察和状态查询仍可用。",
        "default": False,
    },
    "agent_observation_distance": {
        "description": "Agent 默认观察距离",
        "type": "int",
        "hint": "Mineflayer结构化视场和附近实体的默认距离，范围 1 到 32 格。",
        "default": 8,
    },
}


def _config_value(config: dict[str, Any], key: str) -> Any:
    return config.get(key, DEFAULT_CONFIG[key])


def _runtime_state() -> types.ModuleType:
    state = sys.modules.get(RUNTIME_STATE_MODULE)
    if state is None:
        state = types.ModuleType(RUNTIME_STATE_MODULE)
        state.connection_managers = {}
        sys.modules[RUNTIME_STATE_MODULE] = state
    return state


def configure_plugin_operational_settings(config: Any) -> None:
    """Publish plugin-level switches for adapters created later by AstrBot."""
    selected: dict[str, Any] = {}
    if config:
        for key in PLUGIN_OPERATIONAL_CONFIG_KEYS:
            try:
                if key in config:
                    selected[key] = config[key]
            except (KeyError, TypeError):
                continue
    _runtime_state().plugin_operational_config = selected


def _runtime_plugin_operational_settings() -> dict[str, Any]:
    configured = getattr(_runtime_state(), "plugin_operational_config", None)
    return dict(configured) if isinstance(configured, dict) else {}


def _runtime_connection_managers() -> dict[tuple[str, int, str], Any]:
    """Keep live WebSocket state across AstrBot's in-process module reloads."""
    state = _runtime_state()
    managers = getattr(state, "connection_managers", None)
    if not isinstance(managers, dict):
        managers = {}
        state.connection_managers = managers
    return managers


def _discover_legacy_connection_manager(
    host: str, port: int, path: str
) -> Any | None:
    """Adopt the live manager from an adapter created before shared state existed."""
    candidates: list[Any] = []
    for value in gc.get_objects():
        try:
            value_type = type(value)
            if value_type.__name__ != "MinecraftPlatformAdapter":
                continue
            if not value_type.__module__.endswith(".minecraft_adapter"):
                continue
            if (
                str(getattr(value, "host", "")) != host
                or int(getattr(value, "port", -1)) != port
                or str(getattr(value, "path", "")) != path
            ):
                continue
            manager = getattr(value, "connection_manager", None)
            if callable(getattr(manager, "query", None)) and callable(
                getattr(manager, "snapshot", None)
            ):
                candidates.append(manager)
        except (AttributeError, ReferenceError, TypeError, ValueError):
            continue
    if not candidates:
        return None
    return max(candidates, key=lambda item: int(getattr(item, "connected_count", 0)))


def select_live_platform_adapter(preferred: Any | None) -> Any | None:
    """Prefer the same-endpoint adapter that owns an actual live connection."""
    endpoint = None
    if preferred is not None:
        try:
            endpoint = (
                str(getattr(preferred, "host")),
                int(getattr(preferred, "port")),
                str(getattr(preferred, "path")),
            )
        except (AttributeError, TypeError, ValueError):
            endpoint = None
    candidates: list[Any] = []
    if preferred is not None:
        candidates.append(preferred)
    for value in gc.get_objects():
        try:
            value_type = type(value)
            if value_type.__name__ != "MinecraftPlatformAdapter":
                continue
            if not value_type.__module__.endswith(".minecraft_adapter"):
                continue
            if endpoint is not None and (
                str(getattr(value, "host", "")),
                int(getattr(value, "port", -1)),
                str(getattr(value, "path", "")),
            ) != endpoint:
                continue
            if callable(getattr(value, "local_status", None)) and all(
                value is not item for item in candidates
            ):
                candidates.append(value)
        except (AttributeError, ReferenceError, TypeError, ValueError):
            continue
    if not candidates:
        return preferred
    return max(
        candidates,
        key=lambda value: (
            int(getattr(getattr(value, "connection_manager", None), "connected_count", 0)),
            value is preferred,
        ),
    )


def _trim_content(value: Any, max_len: int) -> str:
    content = str(value or "").replace("\r", "").strip()
    if len(content) > max_len:
        return content[:max_len]
    return content


def _trim_outbound_content(value: Any, max_len: int) -> str:
    content = str(value or "").replace("\r", "").strip()
    if max_len > 0 and len(content) > max_len:
        return content[: max(0, max_len - 1)] + "…"
    return content


def _trim_sender_name(value: Any, fallback: str) -> str:
    sender = str(value or fallback).replace("\r", "").replace("\n", " ").strip()
    if len(sender) > MAX_SENDER_NAME_LENGTH:
        return sender[:MAX_SENDER_NAME_LENGTH]
    return sender or fallback


def _parse_aliases(value: Any) -> set[str]:
    aliases: set[str] = set()
    for item in str(value or "").split(","):
        alias = item.strip().casefold()
        if alias:
            aliases.add(alias)
    return aliases


def _plain_text_from_chain(message: MessageChain) -> str:
    parts: list[str] = []
    chain = getattr(message, "chain", message)
    for item in chain:
        if isinstance(item, Plain):
            parts.append(item.text)
        elif hasattr(item, "text"):
            parts.append(str(item.text))
        else:
            logger.warning("MineAstr 已忽略不支持的出站消息片段：%s", type(item).__name__)
    return "".join(parts).strip()


def _query_error_message(exc: BaseException) -> str:
    if isinstance(exc, asyncio.TimeoutError):
        return "等待 Minecraft 服务器查询结果超时"
    return str(exc) or exc.__class__.__name__


class MinecraftConnectionManager:
    def __init__(self, bot_display_name: str, outbound_max_message_length: int):
        self._bot_display_name = bot_display_name
        self._outbound_max_message_length = max(1, outbound_max_message_length)
        self._connections: dict[web.WebSocketResponse, dict[str, Any]] = {}
        self._pending_queries: dict[str, tuple[web.WebSocketResponse, asyncio.Future[dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()

    @property
    def connected_count(self) -> int:
        return len(self._connections)

    def reconfigure(self, bot_display_name: str, outbound_max_message_length: int) -> None:
        self._bot_display_name = bot_display_name
        self._outbound_max_message_length = max(1, outbound_max_message_length)

    async def register(self, ws: web.WebSocketResponse, hello: dict[str, Any]) -> None:
        now = int(time.time() * 1000)
        async with self._lock:
            self._connections[ws] = {
                "server_id": hello.get("server_id", "minecraft"),
                "server_name": hello.get("server_name", "Minecraft Server"),
                "mod_version": hello.get("mod_version", "unknown"),
                "minecraft_version": hello.get("minecraft_version", "unknown"),
                "connected_at": now,
                "last_seen_at": now,
                "query_capabilities": list(hello.get("query_capabilities") or []),
                "server_introduction_url": str(hello.get("server_introduction_url") or ""),
            }

    async def unregister(self, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            self._connections.pop(ws, None)
            pending_ids = [
                message_id
                for message_id, (pending_ws, _) in self._pending_queries.items()
                if pending_ws is ws
            ]
            for message_id in pending_ids:
                _, future = self._pending_queries.pop(message_id)
                if not future.done():
                    future.set_exception(RuntimeError("Minecraft WebSocket 已断开"))

    async def snapshot(self) -> list[dict[str, Any]]:
        async with self._lock:
            return [dict(meta) for meta in self._connections.values()]

    async def close(self) -> None:
        async with self._lock:
            connections = list(self._connections.keys())
            pending = list(self._pending_queries.values())
            self._connections.clear()
            self._pending_queries.clear()
        for _, future in pending:
            if not future.done():
                future.set_exception(RuntimeError("MineAstr WebSocket 服务正在关闭"))
        for ws in connections:
            await ws.close()

    async def send_chat(self, content: str, sender_name: str | None = None) -> None:
        content = _trim_outbound_content(content, self._outbound_max_message_length)
        if not content:
            return
        payload = {
            "type": "chat",
            "message_id": str(uuid.uuid4()),
            "sender_name": _trim_sender_name(sender_name, self._bot_display_name),
            "content": content,
        }
        await self._broadcast(payload)

    async def send_server_chat(
        self, server_id: str, content: str, sender_name: str | None = None
    ) -> None:
        content = _trim_outbound_content(content, self._outbound_max_message_length)
        if not content:
            return
        ws, _ = await self._select_connection(server_id)
        await ws.send_str(json.dumps({
            "type": "chat",
            "message_id": str(uuid.uuid4()),
            "sender_name": _trim_sender_name(sender_name, self._bot_display_name),
            "content": content,
        }, ensure_ascii=False))

    async def send_pong(self, ws: web.WebSocketResponse, time_ms: int | None = None) -> None:
        await ws.send_str(json.dumps({"type": "pong", "time_ms": time_ms or int(time.time() * 1000)}))

    async def send_error(self, ws: web.WebSocketResponse, message: str) -> None:
        await ws.send_str(json.dumps({"type": "error", "message": message}))

    async def mark_seen(self, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            if ws in self._connections:
                self._connections[ws]["last_seen_at"] = int(time.time() * 1000)

    async def resolve_query(self, payload: dict[str, Any]) -> None:
        message_id = str(payload.get("message_id") or "")
        if not message_id:
            return
        async with self._lock:
            pending = self._pending_queries.pop(message_id, None)
        if not pending:
            logger.debug("MineAstr 已忽略未知查询结果：%s", message_id)
            return
        _, future = pending
        if not future.done():
            future.set_result(payload)

    async def query(
        self,
        query_type: str,
        server_id: str | None = None,
        params: dict[str, Any] | None = None,
        timeout: float = QUERY_TIMEOUT_SECONDS,
    ) -> dict[str, Any]:
        ws, _ = await self._select_connection(server_id)
        return await self._query_ws(ws, query_type, params=params, timeout=timeout)

    async def query_all(self, query_type: str) -> list[dict[str, Any]]:
        async with self._lock:
            targets = [
                (ws, dict(meta))
                for ws, meta in self._connections.items()
                if not ws.closed
            ]
        if not targets:
            return []

        tasks = [self._query_ws(ws, query_type) for ws, _ in targets]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        normalized: list[dict[str, Any]] = []
        for (_, meta), result in zip(targets, results):
            if isinstance(result, Exception):
                normalized.append(
                    {
                        "type": "query_result",
                        "query": query_type,
                        "ok": False,
                        "server_id": meta.get("server_id", "minecraft"),
                        "server_name": meta.get("server_name", "Minecraft Server"),
                        "error": _query_error_message(result),
                    }
                )
            else:
                normalized.append(result)
        return normalized

    async def _broadcast(self, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False)
        async with self._lock:
            connections = list(self._connections.keys())
        for ws in connections:
            if ws.closed:
                await self.unregister(ws)
                continue
            try:
                await ws.send_str(data)
            except Exception as exc:
                logger.warning("MineAstr 发送 WebSocket 数据失败：%s", exc)
                await self.unregister(ws)

    async def _select_connection(self, server_id: str | None) -> tuple[web.WebSocketResponse, dict[str, Any]]:
        async with self._lock:
            connections = [
                (ws, dict(meta))
                for ws, meta in self._connections.items()
                if not ws.closed
            ]
        if not connections:
            raise RuntimeError("当前没有已连接的 Minecraft 服务器")
        if server_id:
            for ws, meta in connections:
                if str(meta.get("server_id")) == server_id:
                    return ws, meta
            raise RuntimeError(f"未找到 server_id={server_id} 的 Minecraft 服务器")
        return connections[0]

    async def _query_ws(
        self,
        ws: web.WebSocketResponse,
        query_type: str,
        params: dict[str, Any] | None = None,
        timeout: float = QUERY_TIMEOUT_SECONDS,
    ) -> dict[str, Any]:
        if ws.closed:
            raise RuntimeError("Minecraft WebSocket 已关闭")
        message_id = str(uuid.uuid4())
        future: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
        async with self._lock:
            self._pending_queries[message_id] = (ws, future)
        payload = {
            "type": "query",
            "message_id": message_id,
            "query": query_type,
            "time_ms": int(time.time() * 1000),
        }
        if params:
            payload.update(params)
        try:
            await ws.send_str(json.dumps(payload, ensure_ascii=False))
            return await asyncio.wait_for(future, timeout=timeout)
        finally:
            async with self._lock:
                self._pending_queries.pop(message_id, None)


class MinecraftPlatformEvent(AstrMessageEvent):
    def __init__(
        self,
        message_str: str,
        message_obj: AstrBotMessage,
        platform_meta: PlatformMetadata,
        session_id: str,
        connection_manager: MinecraftConnectionManager,
        bot_display_name: str,
    ):
        super().__init__(message_str, message_obj, platform_meta, session_id)
        self._connection_manager = connection_manager
        self._bot_display_name = bot_display_name

    async def send(self, message: MessageChain):
        content = _plain_text_from_chain(message)
        if content:
            await self._connection_manager.send_chat(content, self._bot_display_name)
        await super().send(message)


@register_platform_adapter(
    "minecraft",
    "Minecraft 群聊桥接",
    default_config_tmpl=DEFAULT_CONFIG,
    adapter_display_name="Minecraft 群聊桥接",
    logo_path=LOGO_PATH,
    config_metadata=CONFIG_METADATA,
)
class MinecraftPlatformAdapter(Platform):
    def __init__(self, platform_config: dict[str, Any], platform_settings: dict[str, Any], event_queue):
        try:
            super().__init__(platform_config or {}, event_queue)
        except TypeError:
            super().__init__(event_queue)
        self.config = {
            **DEFAULT_CONFIG,
            **(platform_config or {}),
            **_runtime_plugin_operational_settings(),
        }
        self.settings = platform_settings or {}
        self.host = str(_config_value(self.config, "host"))
        self.port = int(_config_value(self.config, "port"))
        self.path = str(_config_value(self.config, "path"))
        if not self.path.startswith("/"):
            self.path = "/" + self.path
        self.token = str(_config_value(self.config, "token"))
        self.group_id = str(_config_value(self.config, "group_id"))
        self.group_name = str(_config_value(self.config, "group_name"))
        self.bot_id = str(_config_value(self.config, "bot_id"))
        self.bot_display_name = str(_config_value(self.config, "bot_display_name"))
        self.mention_aliases = _parse_aliases(_config_value(self.config, "mention_aliases"))
        self.max_message_length = max(1, int(_config_value(self.config, "max_message_length")))
        self.outbound_max_message_length = max(1, int(_config_value(self.config, "outbound_max_message_length")))
        self.server_event_push_enabled = bool(_config_value(self.config, "server_event_push_enabled"))
        self.websocket_max_message_bytes = max(8192, int(_config_value(self.config, "websocket_max_message_bytes")))
        self.screenshot_cooldown_seconds = max(0.0, float(_config_value(self.config, "screenshot_cooldown_seconds")))
        self.screenshot_timeout_seconds = max(1.0, float(_config_value(self.config, "screenshot_timeout_seconds")))
        self.agent_actions_enabled = bool(_config_value(self.config, "agent_actions_enabled"))
        self.agent_require_admin_approval = bool(_config_value(self.config, "agent_require_admin_approval"))
        self.agent_observation_distance = max(1, min(32, int(_config_value(self.config, "agent_observation_distance"))))
        self.knowledge_sync_enabled = bool(_config_value(self.config, "knowledge_sync_enabled"))
        self.knowledge_embedding_provider_id = str(_config_value(self.config, "knowledge_embedding_provider_id"))
        self.modrinth_enrichment_enabled = bool(_config_value(self.config, "modrinth_enrichment_enabled"))
        self.server_site_sync_enabled = bool(_config_value(self.config, "server_site_sync_enabled"))
        self.server_site_allowed_paths = str(_config_value(self.config, "server_site_allowed_paths"))
        self.server_site_excluded_paths = str(_config_value(self.config, "server_site_excluded_paths"))
        self.activity_region_sync_enabled = bool(_config_value(self.config, "activity_region_sync_enabled"))
        self.knowledge_chat_provider_id = str(_config_value(self.config, "knowledge_chat_provider_id"))
        self._runtime_manager_key = (self.host, self.port, self.path)
        managers = _runtime_connection_managers()
        manager = managers.get(self._runtime_manager_key)
        legacy_manager = _discover_legacy_connection_manager(*self._runtime_manager_key)
        if legacy_manager is not None and int(getattr(legacy_manager, "connected_count", 0)) > int(
            getattr(manager, "connected_count", 0)
        ):
            manager = legacy_manager
            managers[self._runtime_manager_key] = manager
            logger.info(
                "MineAstr 已接管热重载前的平台 WebSocket 状态：%s 个服务器连接。",
                manager.connected_count,
            )
        if manager is None or not callable(getattr(manager, "query", None)):
            manager = MinecraftConnectionManager(self.bot_display_name, self.outbound_max_message_length)
            managers[self._runtime_manager_key] = manager
        else:
            reconfigure = getattr(manager, "reconfigure", None)
            if callable(reconfigure):
                reconfigure(self.bot_display_name, self.outbound_max_message_length)
            else:
                # Compatible with a manager created by MineAstr 0.9 before hot reload.
                manager._bot_display_name = self.bot_display_name
                manager._outbound_max_message_length = self.outbound_max_message_length
        self.connection_manager = manager
        self._runner: web.AppRunner | None = None

    def _bot_mention_aliases(self) -> set[str]:
        aliases = {
            str(self.bot_id or "").casefold(),
            str(self.bot_display_name or "").casefold(),
        }
        aliases.update(self.mention_aliases)
        aliases.discard("")
        return aliases

    def _parse_minecraft_message(self, content: str) -> tuple[list[Any], str, str | None]:
        text = content.strip()
        match = MINECRAFT_LEADING_MENTION_RE.match(text)
        if not match:
            return [Plain(text)], text, None

        target = str(match.group("target") or "").strip().rstrip("，,。.!！？?；;:：")
        body = str(match.group("body") or "").lstrip()
        if target.casefold() not in self._bot_mention_aliases():
            return [Plain(text)], text, None

        # AstrBot 的唤醒阶段会优先看 wake_prefix。为了兼容不同环境里
        # At 组件与 self_id 识别不一致的情况，这里把 Minecraft 里的
        # "@xxx 内容" 转成一个内部唤醒消息，而不是完全依赖 At。
        wake_body = body or ""
        message_str = f"/{wake_body}" if not wake_body.startswith("/") else wake_body
        chain: list[Any] = [Plain(wake_body)]
        logger.debug("MineAstr 已识别 Minecraft 提及到机器人：%s", target)
        return chain, message_str, target

    def meta(self) -> PlatformMetadata:
        return PlatformMetadata(
            name="minecraft",
            description="通过 MineAstr WebSocket 接入 Minecraft 聊天",
            id="minecraft",
        )

    async def run(self):
        app = web.Application()
        app.router.add_get(self.path, self._handle_websocket)
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.host, self.port)
        await site.start()
        logger.info("MineAstr WebSocket 正在监听 ws://%s:%s%s", self.host, self.port, self.path)

        try:
            await asyncio.Event().wait()
        finally:
            await self.connection_manager.close()
            if self._runner:
                await self._runner.cleanup()

    async def send_by_session(self, session: MessageSesion, message_chain: MessageChain):
        content = _plain_text_from_chain(message_chain)
        if not content:
            return
        await self.connection_manager.send_chat(content, self.bot_display_name)

    async def query_status(self, server_id: str | None = None) -> dict[str, Any]:
        if server_id:
            return await self.connection_manager.query("status", server_id)
        return {
            "type": "query_result",
            "query": "status",
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.query_all("status"),
        }

    async def query_players(self, server_id: str | None = None) -> dict[str, Any]:
        if server_id:
            return await self.connection_manager.query("players", server_id)
        return {
            "type": "query_result",
            "query": "players",
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.query_all("players"),
        }

    async def query_knowledge_manifest(self, server_id: str) -> dict[str, Any]:
        return await self.connection_manager.query("knowledge_manifest", server_id, timeout=15.0)

    async def query_knowledge_page(
        self,
        server_id: str,
        snapshot_id: str,
        category: str,
        cursor: int,
        page_size: int,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "knowledge_page",
            server_id,
            params={
                "snapshot_id": snapshot_id,
                "category": category,
                "cursor": cursor,
                "page_size": page_size,
            },
            timeout=15.0,
        )

    async def query_knowledge_status(self, server_id: str) -> dict[str, Any]:
        return await self.connection_manager.query("knowledge_status", server_id, timeout=15.0)

    async def query_knowledge_rescan(self, server_id: str, scope: str = "local") -> dict[str, Any]:
        return await self.connection_manager.query(
            "knowledge_rescan", server_id, params={"scope": scope}, timeout=15.0
        )

    async def query_activity_regions_manifest(self, server_id: str) -> dict[str, Any]:
        return await self.connection_manager.query("activity_regions_manifest", server_id, timeout=15.0)

    async def query_activity_regions_page(
        self, server_id: str, snapshot_id: str, cursor: int, page_size: int
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "activity_regions_page", server_id,
            params={"snapshot_id": snapshot_id, "cursor": cursor, "page_size": page_size},
            timeout=15.0,
        )

    async def send_server_chat(self, server_id: str, content: str) -> None:
        await self.connection_manager.send_server_chat(server_id, content, self.bot_display_name)

    async def query_player_state(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "player_state",
            server_id,
            params={"player_uuid": player_uuid.strip(), "player_name": player_name.strip()},
        )

    async def query_inventory(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        include_ender_chest: bool = False,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "inventory",
            server_id,
            params={
                "player_uuid": player_uuid.strip(),
                "player_name": player_name.strip(),
                "include_ender_chest": bool(include_ender_chest),
            },
        )

    async def query_nearby_entities(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        radius: float = 12.0,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "nearby_entities",
            server_id,
            params={
                "player_uuid": player_uuid.strip(),
                "player_name": player_name.strip(),
                "radius": max(1.0, min(32.0, float(radius))),
            },
        )

    async def analyze_region(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        horizontal_radius: int = 8,
        vertical_radius: int = 6,
        dimension: str = "",
        x: int | None = None,
        y: int | None = None,
        z: int | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "player_uuid": player_uuid.strip(),
            "player_name": player_name.strip(),
            "horizontal_radius": max(1, min(24, int(horizontal_radius))),
            "vertical_radius": max(1, min(16, int(vertical_radius))),
        }
        if x is not None and y is not None and z is not None:
            params.update(
                {
                    "dimension": dimension.strip() or "minecraft:overworld",
                    "x": int(x),
                    "y": int(y),
                    "z": int(z),
                }
            )
        return await self.connection_manager.query("region_features", server_id, params=params, timeout=10.0)

    async def run_server_command(
        self,
        server_id: str | None,
        command: str,
        requester_id: str,
        requester_uuid: str,
        requester_name: str,
        requester_platform: str,
    ) -> dict[str, Any]:
        return await self.connection_manager.query(
            "command",
            server_id,
            params={
                "command": command.strip(),
                "requester_id": requester_id.strip(),
                "requester_uuid": requester_uuid.strip(),
                "requester_name": requester_name.strip(),
                "requester_platform": requester_platform.strip(),
            },
            timeout=10.0,
        )

    async def request_screenshot(
        self,
        server_id: str | None = None,
        player_uuid: str = "",
        player_name: str = "",
        reason: str = "",
    ) -> dict[str, Any]:
        params = {
            "player_uuid": player_uuid.strip(),
            "player_name": player_name.strip(),
            "reason": reason.strip() or "AstrBot 请求查看当前 Minecraft 画面。",
            "max_width": 1280,
            "max_height": 720,
            "max_bytes": 1048576,
            "format": "jpeg",
        }
        return await self.connection_manager.query(
            "screenshot",
            server_id,
            params=params,
            timeout=self.screenshot_timeout_seconds,
        )

    async def query_agent_status(self, server_id: str | None = None) -> dict[str, Any]:
        return await self.connection_manager.query("agent_status", server_id, timeout=8.0)

    async def observe_agent(self, server_id: str | None = None, distance: int | None = None) -> dict[str, Any]:
        selected = self.agent_observation_distance if distance is None else max(1, min(32, int(distance)))
        return await self.connection_manager.query(
            "agent_observe", server_id, params={"distance": selected}, timeout=12.0
        )

    async def submit_agent_task(
        self,
        server_id: str | None,
        task_type: str,
        args: dict[str, Any],
        task_id: str = "",
        approved_by_admin: bool = False,
        requester: dict[str, str] | None = None,
    ) -> dict[str, Any]:
        if not self.agent_actions_enabled:
            raise RuntimeError("AstrBot 配置已禁用 AI 玩家 Agent 操作")
        params: dict[str, Any] = {
            "task_id": task_id.strip(), "task_type": task_type.strip(), "args": args,
            "approved_by_admin": bool(approved_by_admin),
        }
        if requester:
            params.update({key: str(value)[:100] for key, value in requester.items() if value})
        return await self.connection_manager.query(
            "agent_task",
            server_id,
            params=params,
            timeout=15.0,
        )

    async def cancel_agent_task(self, server_id: str | None = None) -> dict[str, Any]:
        return await self.connection_manager.query("agent_cancel", server_id, timeout=8.0)

    async def manage_agent_waypoint(
        self, server_id: str | None, action: str, **values: Any
    ) -> dict[str, Any]:
        params = {"action": action.strip().lower()}
        params.update(values)
        return await self.connection_manager.query("agent_waypoints", server_id, params=params, timeout=12.0)

    async def local_status(self) -> dict[str, Any]:
        return {
            "ok": True,
            "connected_count": self.connection_manager.connected_count,
            "servers": await self.connection_manager.snapshot(),
            "shared_connection_state": True,
        }

    async def _handle_websocket(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            return web.Response(status=401, text="未授权")

        ws = web.WebSocketResponse(heartbeat=30, max_msg_size=self.websocket_max_message_bytes)
        await ws.prepare(request)
        logger.info("MineAstr WebSocket 客户端已连接：%s", request.remote)

        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    await self._handle_text(ws, msg.data)
                elif msg.type == WSMsgType.ERROR:
                    logger.warning("MineAstr WebSocket 出错：%s", ws.exception())
        finally:
            await self.connection_manager.unregister(ws)
            logger.info("MineAstr WebSocket 客户端已断开")
        return ws

    def _authorized(self, request: web.Request) -> bool:
        if not self.token:
            return True
        expected = f"Bearer {self.token}"
        return request.headers.get("Authorization") == expected

    async def _handle_text(self, ws: web.WebSocketResponse, data: str) -> None:
        try:
            payload = json.loads(data)
        except json.JSONDecodeError:
            await self.connection_manager.send_error(ws, "无效的 JSON")
            return
        if not isinstance(payload, dict):
            await self.connection_manager.send_error(ws, "WebSocket 消息必须是 JSON 对象")
            return

        try:
            payload_type = payload.get("type")
            if payload_type == "hello":
                await self._handle_hello(ws, payload)
            elif payload_type == "chat":
                await self.connection_manager.mark_seen(ws)
                await self._handle_chat(payload)
            elif payload_type == "ping":
                await self.connection_manager.mark_seen(ws)
                await self.connection_manager.send_pong(ws, payload.get("time_ms"))
            elif payload_type == "query_result":
                await self.connection_manager.mark_seen(ws)
                await self.connection_manager.resolve_query(payload)
            else:
                await self.connection_manager.send_error(ws, f"不支持的消息类型：{payload_type}")
        except (TypeError, ValueError, RuntimeError) as exc:
            logger.warning("MineAstr 处理 WebSocket 消息失败：%s", exc)
            await self.connection_manager.send_error(ws, str(exc))

    async def _handle_hello(self, ws: web.WebSocketResponse, payload: dict[str, Any]) -> None:
        try:
            protocol = int(payload.get("protocol", 0))
        except (TypeError, ValueError):
            await self.connection_manager.send_error(ws, "协议版本必须是整数")
            return
        if protocol != PROTOCOL_VERSION:
            await self.connection_manager.send_error(ws, f"不支持的协议版本：{protocol}")
            return
        await self.connection_manager.register(ws, payload)
        try:
            from .knowledge import get_knowledge_coordinator

            coordinator = get_knowledge_coordinator()
            if coordinator is not None:
                coordinator.server_connected(
                    self,
                    str(payload.get("server_id") or "minecraft"),
                    [str(item) for item in payload.get("query_capabilities") or []],
                    str(payload.get("server_introduction_url") or ""),
                    {
                        "mod_version": str(payload.get("mod_version") or "unknown"),
                        "minecraft_version": str(payload.get("minecraft_version") or "unknown"),
                    },
                )
        except Exception as exc:
            logger.warning("MineAstr 无法启动服务器知识同步：%s", exc)
        logger.info(
            "MineAstr 已注册服务器 %s（%s）",
            payload.get("server_id", "minecraft"),
            payload.get("server_name", "Minecraft Server"),
        )

    async def _handle_chat(self, payload: dict[str, Any]) -> None:
        content = _trim_content(payload.get("content"), self.max_message_length)
        if not content:
            return
        is_server_event = str(payload.get("message_kind") or "") == "server_event"
        if is_server_event and not self.server_event_push_enabled:
            return
        try:
            from .knowledge import get_knowledge_coordinator

            coordinator = get_knowledge_coordinator()
            if coordinator is not None:
                if is_server_event:
                    await coordinator.receive_server_event(payload, content)
                else:
                    await coordinator.receive_region_chat(
                        str(payload.get("server_id") or "minecraft"),
                        str(payload.get("player_uuid") or ""),
                        str(payload.get("player_name") or ""),
                        content,
                        False,
                    )
        except Exception as exc:
            logger.warning("MineAstr 收集地区简介候选失败：%s", exc)
        message = self._convert_chat(payload, content)
        event = MinecraftPlatformEvent(
            message_str=message.message_str,
            message_obj=message,
            platform_meta=self.meta(),
            session_id=message.session_id,
            connection_manager=self.connection_manager,
            bot_display_name=self.bot_display_name,
        )
        self.commit_event(event)

    def _convert_chat(self, payload: dict[str, Any], content: str) -> AstrBotMessage:
        message = AstrBotMessage()
        is_server_event = str(payload.get("message_kind") or "") == "server_event"
        if is_server_event:
            server_id = str(payload.get("server_id") or "minecraft")
            player_uuid = f"mineastr-server:{server_id}"
            player_name = str(payload.get("server_name") or "Minecraft 服务器")
            message_chain, message_str, mention_target = [Plain(content)], content, None
        else:
            player_uuid = str(payload.get("player_uuid") or payload.get("player_name") or "unknown")
            player_name = str(payload.get("player_name") or player_uuid)
            message_chain, message_str, mention_target = self._parse_minecraft_message(content)
        message.type = MessageType.GROUP_MESSAGE
        message.group_id = self.group_id
        if message.group:
            message.group.group_name = self.group_name
        message.message_str = message_str
        message.message = message_chain
        raw_message = dict(payload)
        if mention_target:
            raw_message["minecraft_mentioned_bot"] = True
            raw_message["minecraft_mention_target"] = mention_target
        message.raw_message = raw_message
        message.self_id = self.bot_id
        message.session_id = self.group_id
        message.message_id = str(payload.get("message_id") or uuid.uuid4())
        message.sender = MessageMember(user_id=player_uuid, nickname=player_name)
        return message
