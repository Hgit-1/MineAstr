from astrbot.api import logger
from astrbot.api.star import Context, Star, register


@register(
    "astrbot_plugin_mineastr",
    "MineAstr",
    "Bridge Minecraft server chat into AstrBot as a group conversation.",
    "0.1.0",
)
class MineAstrPlugin(Star):
    def __init__(self, context: Context):
        super().__init__(context)
        from .minecraft_adapter import MinecraftPlatformAdapter  # noqa: F401

    async def initialize(self):
        logger.info("MineAstr plugin initialized. Enable the minecraft platform adapter in AstrBot.")

    async def terminate(self):
        logger.info("MineAstr plugin terminated.")
