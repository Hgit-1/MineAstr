import asyncio
import base64
import json
import re
import time
from pathlib import Path
from typing import Any

from astrbot.api import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star, register

try:
    from mcp.types import CallToolResult, ImageContent, TextContent
except ImportError:
    CallToolResult = None
    ImageContent = None
    TextContent = None


MINEASTR_TOOL_HINT = (
    "如果用户询问 Minecraft 服务器状态、在线人数、在线玩家、版本或 MineAstr 连接情况，"
    "请先调用 mineastr_get_server_status 或 mineastr_get_online_players 获取实时数据，再根据工具结果回答。"
    "如果用户在 Minecraft 群聊中明确或隐含表达希望你看看、评价或判断当前画面，请主动先调用 "
    "mineastr_request_screenshot 请求低清晰度截图，再基于截图回答；例如“能看看我现在画面吗”、"
    "“我的建筑建好啦”、“帮我看看这个建筑”、“我这里好像不对”、“这边怎么样”。"
    "截图需要玩家客户端允许，不要假装已经看见画面。"
)
MINEASTR_EXTERNAL_HINT_KEYWORDS = (
    "minecraft",
    "mineastr",
    "mc",
    "mc服务器",
    "minecraft服务器",
    "我的世界",
)
SCREENSHOT_DIR = Path("data") / "mineastr" / "screenshots"


@register(
    "astrbot_plugin_mineastr",
    "MineAstr",
    "将 Minecraft 聊天桥接为 AstrBot 群聊会话，并提供服务器查询与低清晰度截图工具。",
    "0.3.1",
)
class MineAstrPlugin(Star):
    def __init__(self, context: Context):
        super().__init__(context)
        self._screenshot_last_request_at: dict[tuple[str, str, str], float] = {}
        from .minecraft_adapter import MinecraftPlatformAdapter  # noqa: F401

    async def initialize(self):
        logger.info("MineAstr 插件已初始化。请在 AstrBot 中启用 minecraft 平台适配器。")

    async def terminate(self):
        logger.info("MineAstr 插件已终止。")

    @filter.on_llm_request()
    async def mineastr_on_llm_request(self, event: AstrMessageEvent, request: Any) -> None:
        text = (getattr(event, "message_str", "") or "").lower()
        platform_id = ""
        get_platform_id = getattr(event, "get_platform_id", None)
        if callable(get_platform_id):
            platform_id = str(get_platform_id() or "")
        if platform_id != "minecraft" and not any(keyword in text for keyword in MINEASTR_EXTERNAL_HINT_KEYWORDS):
            return

        current_prompt = getattr(request, "system_prompt", "") or ""
        if MINEASTR_TOOL_HINT in current_prompt:
            return
        request.system_prompt = f"{current_prompt}\n\n{MINEASTR_TOOL_HINT}".strip()

    def _minecraft_adapter(self) -> Any | None:
        getter = getattr(self.context, "get_platform_inst", None)
        if not callable(getter):
            return None
        adapter = getter("minecraft")
        if adapter is None:
            return None
        if (
            not hasattr(adapter, "query_status")
            or not hasattr(adapter, "query_players")
            or not hasattr(adapter, "request_screenshot")
        ):
            return None
        return adapter

    @staticmethod
    def _tool_json(title: str, payload: dict[str, Any]) -> str:
        return f"{title}：\n{json.dumps(payload, ensure_ascii=False, indent=2)}"

    def _tool_image_result(
        self,
        title: str,
        payload: dict[str, Any],
        image_base64: str | None,
        mime_type: str,
    ) -> Any:
        text = self._tool_json(title, payload)
        if not image_base64 or CallToolResult is None or ImageContent is None or TextContent is None:
            return text
        try:
            return CallToolResult(
                content=[
                    TextContent(type="text", text=text),
                    ImageContent(type="image", data=image_base64, mimeType=mime_type),
                ]
            )
        except Exception as exc:
            logger.warning("MineAstr 构造截图工具图片结果失败，已退回文本结果：%s", exc)
            return text

    @staticmethod
    def _event_raw_message(event: AstrMessageEvent) -> dict[str, Any]:
        message_obj = getattr(event, "message_obj", None)
        raw = getattr(message_obj, "raw_message", None)
        return raw if isinstance(raw, dict) else {}

    @staticmethod
    def _safe_filename(value: Any, fallback: str = "unknown") -> str:
        text = str(value or fallback)
        text = re.sub(r"[^A-Za-z0-9_.-]+", "_", text).strip("._")
        return text or fallback

    def _save_screenshot_result(self, payload: dict[str, Any]) -> dict[str, Any]:
        data = payload.get("data")
        if not isinstance(data, dict):
            return payload
        image_base64 = data.get("image_base64")
        if not isinstance(image_base64, str) or not image_base64:
            return payload

        image_bytes = base64.b64decode(image_base64, validate=True)
        mime_type = str(data.get("mime_type") or "image/jpeg")
        suffix = ".jpg" if mime_type == "image/jpeg" else ".bin"
        server_id = self._safe_filename(payload.get("server_id"), "minecraft")
        player_name = self._safe_filename(data.get("player_name"), "player")
        message_id = self._safe_filename(payload.get("message_id"), str(int(time.time() * 1000)))
        timestamp = time.strftime("%Y%m%d-%H%M%S")

        SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
        path = SCREENSHOT_DIR / f"{timestamp}_{server_id}_{player_name}_{message_id}{suffix}"
        path.write_bytes(image_bytes)

        saved = dict(payload)
        saved_data = dict(data)
        saved_data.pop("image_base64", None)
        saved_data["file_path"] = str(path.resolve())
        saved_data["saved_bytes"] = len(image_bytes)
        saved["data"] = saved_data
        return saved

    @staticmethod
    def _screenshot_cooldown_key(
        server_id: str | None,
        player_uuid: str,
        player_name: str,
    ) -> tuple[str, str, str]:
        return (
            server_id or "minecraft",
            player_uuid or "",
            (player_name or "").lower(),
        )

    def _mark_screenshot_cooldown(self, key: tuple[str, str, str], cooldown_seconds: float) -> float:
        if cooldown_seconds <= 0:
            return 0.0
        now = time.monotonic()
        last_request_at = self._screenshot_last_request_at.get(key)
        if last_request_at is not None:
            remaining = cooldown_seconds - (now - last_request_at)
            if remaining > 0:
                return remaining

        self._screenshot_last_request_at[key] = now
        expire_before = now - max(cooldown_seconds * 3, 60.0)
        stale_keys = [
            stale_key
            for stale_key, requested_at in self._screenshot_last_request_at.items()
            if requested_at < expire_before
        ]
        for stale_key in stale_keys:
            self._screenshot_last_request_at.pop(stale_key, None)
        return 0.0

    @filter.llm_tool(name="mineastr_get_server_status")
    async def mineastr_get_server_status(self, event: AstrMessageEvent, server_id: str = "") -> str:
        """查询 Minecraft 服务器状态，包括连接状态、服务器名称、版本和在线人数。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        adapter = self._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用，暂时无法查询 Minecraft 服务器。"
        target = server_id.strip() or None
        try:
            payload = await adapter.query_status(target)
        except Exception as exc:
            logger.warning("MineAstr 查询 Minecraft 状态失败：%s", exc)
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        return self._tool_json("Minecraft 服务器状态查询结果", payload)

    @filter.llm_tool(name="mineastr_get_online_players")
    async def mineastr_get_online_players(self, event: AstrMessageEvent, server_id: str = "") -> str:
        """查询 Minecraft 当前在线玩家列表和玩家数量。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空；接入多个服务器时填写要查询的 server_id。
        """
        adapter = self._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用，暂时无法查询 Minecraft 在线玩家。"
        target = server_id.strip() or None
        try:
            payload = await adapter.query_players(target)
        except Exception as exc:
            logger.warning("MineAstr 查询 Minecraft 在线玩家失败：%s", exc)
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        return self._tool_json("Minecraft 在线玩家查询结果", payload)

    @filter.llm_tool(name="mineastr_request_screenshot")
    async def mineastr_request_screenshot(
        self,
        event: AstrMessageEvent,
        server_id: str = "",
        player_name: str = "",
        player_uuid: str = "",
        reason: str = "",
    ) -> Any:
        """请求指定 Minecraft 客户端发送低清晰度截图。

        Args:
            server_id(str): 可选的 Minecraft 服务器 ID。只接入一个服务器时留空。
            player_name(str): 可选的玩家名。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            player_uuid(str): 可选的玩家 UUID。来自 Minecraft 群聊且留空时默认使用当前发言玩家。
            reason(str): 可选的截图原因，会展示给处于询问模式的玩家。
        """
        adapter = self._minecraft_adapter()
        if adapter is None:
            return "MineAstr 的 minecraft 平台适配器未启用，暂时无法请求 Minecraft 截图。"

        raw = self._event_raw_message(event)
        target_uuid = player_uuid.strip() or str(raw.get("player_uuid") or "").strip()
        target_name = player_name.strip() or str(raw.get("player_name") or "").strip()
        target_server = server_id.strip() or str(raw.get("server_id") or "").strip() or None
        request_reason = reason.strip() or "AstrBot 需要查看当前 Minecraft 画面以回答玩家问题。"
        cooldown_seconds = float(getattr(adapter, "screenshot_cooldown_seconds", 10.0) or 0.0)
        cooldown_key = self._screenshot_cooldown_key(target_server, target_uuid, target_name)
        cooldown_remaining = self._mark_screenshot_cooldown(cooldown_key, cooldown_seconds)
        if cooldown_remaining > 0:
            wait_seconds = max(1, int(cooldown_remaining + 0.999))
            return self._tool_json(
                "Minecraft 低清晰度截图请求结果",
                {
                    "ok": False,
                    "result": f"截图请求过于频繁，请等待 {wait_seconds} 秒后再试。",
                    "error": "screenshot_cooldown",
                    "retry_after_seconds": wait_seconds,
                    "server_id": target_server,
                    "player_uuid": target_uuid,
                    "player_name": target_name,
                },
            )

        try:
            payload = await adapter.request_screenshot(
                target_server,
                player_uuid=target_uuid,
                player_name=target_name,
                reason=request_reason,
            )
            image_base64 = None
            mime_type = "image/jpeg"
            if payload.get("ok"):
                data = payload.get("data")
                if isinstance(data, dict):
                    maybe_image = data.get("image_base64")
                    if isinstance(maybe_image, str):
                        image_base64 = maybe_image
                    mime_type = str(data.get("mime_type") or mime_type)
                payload = self._save_screenshot_result(payload)
        except asyncio.TimeoutError:
            logger.warning("MineAstr 请求 Minecraft 截图超时。")
            image_base64 = None
            mime_type = "image/jpeg"
            payload = {
                "ok": False,
                "result": "请求截图超时，客户端未响应。",
                "error": "screenshot_timeout",
                "local_status": await adapter.local_status(),
            }
        except Exception as exc:
            logger.warning("MineAstr 请求 Minecraft 截图失败：%s", exc)
            image_base64 = None
            mime_type = "image/jpeg"
            payload = {
                "ok": False,
                "error": str(exc) or exc.__class__.__name__,
                "local_status": await adapter.local_status(),
            }
        return self._tool_image_result("Minecraft 低清晰度截图请求结果", payload, image_base64, mime_type)
