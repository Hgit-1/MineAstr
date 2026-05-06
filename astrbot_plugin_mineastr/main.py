from astrbot.api import logger
from astrbot.api.star import Context, Star, register


@register(
    "astrbot_plugin_mineastr",
    "MineAstr",
    "将 Minecraft 聊天桥接为 AstrBot 群聊会话。",
    "0.1.0",
)
class MineAstrPlugin(Star):
    def __init__(self, context: Context):
        super().__init__(context)
        from .minecraft_adapter import MinecraftPlatformAdapter  # noqa: F401

    async def initialize(self):
        logger.info("MineAstr 插件已初始化。请在 AstrBot 中启用 minecraft 平台适配器。")

    async def terminate(self):
        logger.info("MineAstr 插件已终止。")
