import asyncio
import json
import time
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
LOGO_PATH = str(Path(__file__).resolve().with_name("logo.png"))
DEFAULT_CONFIG = {
    "host": "127.0.0.1",
    "port": 8765,
    "path": "/ws",
    "token": "change-me",
    "group_id": "minecraft",
    "group_name": "Minecraft",
    "bot_id": "astrbot",
    "bot_display_name": "AstrBot",
    "max_message_length": 1000,
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
    "max_message_length": {
        "description": "最大聊天长度",
        "type": "int",
        "hint": "单条 Minecraft 消息转发到 AstrBot 前允许的最大长度；超出部分会被截断，建议保持默认。",
        "default": 1000,
    },
}


def _config_value(config: dict[str, Any], key: str) -> Any:
    return config.get(key, DEFAULT_CONFIG[key])


def _trim_content(value: Any, max_len: int) -> str:
    content = str(value or "").replace("\r", "").strip()
    if len(content) > max_len:
        return content[:max_len]
    return content


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


class MinecraftConnectionManager:
    def __init__(self, bot_display_name: str):
        self._bot_display_name = bot_display_name
        self._connections: dict[web.WebSocketResponse, dict[str, Any]] = {}
        self._lock = asyncio.Lock()

    @property
    def connected_count(self) -> int:
        return len(self._connections)

    async def register(self, ws: web.WebSocketResponse, hello: dict[str, Any]) -> None:
        async with self._lock:
            self._connections[ws] = {
                "server_id": hello.get("server_id", "minecraft"),
                "server_name": hello.get("server_name", "Minecraft Server"),
                "connected_at": int(time.time() * 1000),
            }

    async def unregister(self, ws: web.WebSocketResponse) -> None:
        async with self._lock:
            self._connections.pop(ws, None)

    async def close(self) -> None:
        async with self._lock:
            connections = list(self._connections.keys())
            self._connections.clear()
        for ws in connections:
            await ws.close()

    async def send_chat(self, content: str, sender_name: str | None = None) -> None:
        if not content:
            return
        payload = {
            "type": "chat",
            "message_id": str(uuid.uuid4()),
            "sender_name": sender_name or self._bot_display_name,
            "content": content,
        }
        await self._broadcast(payload)

    async def send_pong(self, ws: web.WebSocketResponse, time_ms: int | None = None) -> None:
        await ws.send_str(json.dumps({"type": "pong", "time_ms": time_ms or int(time.time() * 1000)}))

    async def send_error(self, ws: web.WebSocketResponse, message: str) -> None:
        await ws.send_str(json.dumps({"type": "error", "message": message}))

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
        self.config = {**DEFAULT_CONFIG, **(platform_config or {})}
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
        self.max_message_length = int(_config_value(self.config, "max_message_length"))
        self.connection_manager = MinecraftConnectionManager(self.bot_display_name)
        self._runner: web.AppRunner | None = None

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

    async def _handle_websocket(self, request: web.Request) -> web.StreamResponse:
        if not self._authorized(request):
            return web.Response(status=401, text="未授权")

        ws = web.WebSocketResponse(heartbeat=30)
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

        payload_type = payload.get("type")
        if payload_type == "hello":
            await self._handle_hello(ws, payload)
        elif payload_type == "chat":
            await self._handle_chat(payload)
        elif payload_type == "ping":
            await self.connection_manager.send_pong(ws, payload.get("time_ms"))
        else:
            await self.connection_manager.send_error(ws, f"不支持的消息类型：{payload_type}")

    async def _handle_hello(self, ws: web.WebSocketResponse, payload: dict[str, Any]) -> None:
        protocol = int(payload.get("protocol", 0))
        if protocol != PROTOCOL_VERSION:
            await self.connection_manager.send_error(ws, f"不支持的协议版本：{protocol}")
            return
        await self.connection_manager.register(ws, payload)
        logger.info(
            "MineAstr 已注册服务器 %s（%s）",
            payload.get("server_id", "minecraft"),
            payload.get("server_name", "Minecraft Server"),
        )

    async def _handle_chat(self, payload: dict[str, Any]) -> None:
        content = _trim_content(payload.get("content"), self.max_message_length)
        if not content:
            return
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
        player_uuid = str(payload.get("player_uuid") or payload.get("player_name") or "unknown")
        player_name = str(payload.get("player_name") or player_uuid)
        message.type = MessageType.GROUP_MESSAGE
        message.group_id = self.group_id
        message.message_str = content
        message.message = [Plain(content)]
        message.raw_message = payload
        message.self_id = self.bot_id
        message.session_id = self.group_id
        message.message_id = str(payload.get("message_id") or uuid.uuid4())
        message.sender = MessageMember(user_id=player_uuid, nickname=player_name)
        return message
