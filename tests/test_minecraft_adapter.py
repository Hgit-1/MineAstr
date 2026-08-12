import importlib.util
from pathlib import Path
import sys
import types
import unittest


def _load_adapter_module():
    package_name = "mineastr_adapter_testpkg"
    package = types.ModuleType(package_name)
    package.__path__ = []
    sys.modules[package_name] = package

    aiohttp_module = sys.modules.get("aiohttp") or types.ModuleType("aiohttp")
    aiohttp_module.WSMsgType = types.SimpleNamespace(TEXT="text", ERROR="error")
    aiohttp_module.web = types.SimpleNamespace(
        WebSocketResponse=object,
        Request=object,
        StreamResponse=object,
        Response=object,
        AppRunner=object,
        Application=object,
        TCPSite=object,
    )
    sys.modules["aiohttp"] = aiohttp_module

    api = sys.modules.setdefault("astrbot.api", types.ModuleType("astrbot.api"))
    api.logger = types.SimpleNamespace(
        debug=lambda *_a, **_k: None,
        info=lambda *_a, **_k: None,
        warning=lambda *_a, **_k: None,
    )

    event_module = types.ModuleType("astrbot.api.event")

    class AstrMessageEvent:
        def __init__(self, message_str, message_obj, platform_meta, session_id):
            self.message_str = message_str
            self.message_obj = message_obj

        async def send(self, _message):
            return None

    event_module.AstrMessageEvent = AstrMessageEvent
    event_module.MessageChain = list
    sys.modules["astrbot.api.event"] = event_module

    components = types.ModuleType("astrbot.api.message_components")

    class Plain:
        def __init__(self, text):
            self.text = text

    components.Plain = Plain
    sys.modules["astrbot.api.message_components"] = components

    platform_module = types.ModuleType("astrbot.api.platform")

    class AstrBotMessage:
        def __init__(self):
            self.group = types.SimpleNamespace(group_name="")

    class MessageMember:
        def __init__(self, user_id, nickname):
            self.user_id = user_id
            self.nickname = nickname

    class MessageType:
        GROUP_MESSAGE = "group"

    class Platform:
        def __init__(self, *_args):
            pass

    class PlatformMetadata:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    platform_module.AstrBotMessage = AstrBotMessage
    platform_module.MessageMember = MessageMember
    platform_module.MessageType = MessageType
    platform_module.Platform = Platform
    platform_module.PlatformMetadata = PlatformMetadata
    platform_module.register_platform_adapter = lambda *_a, **_k: lambda value: value
    sys.modules["astrbot.api.platform"] = platform_module

    session_module = types.ModuleType("astrbot.core.platform.message_session")
    session_module.MessageSesion = object
    sys.modules["astrbot.core.platform.message_session"] = session_module

    coordinator = types.SimpleNamespace(server_events=[], region_chats=[])

    async def receive_server_event(payload, content):
        coordinator.server_events.append((payload, content))

    async def receive_region_chat(*args):
        coordinator.region_chats.append(args)

    coordinator.receive_server_event = receive_server_event
    coordinator.receive_region_chat = receive_region_chat
    knowledge_module = types.ModuleType(f"{package_name}.knowledge")
    knowledge_module.get_knowledge_coordinator = lambda: coordinator
    sys.modules[f"{package_name}.knowledge"] = knowledge_module

    path = Path(__file__).resolve().parents[1] / "minecraft_adapter.py"
    spec = importlib.util.spec_from_file_location(f"{package_name}.minecraft_adapter", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module, coordinator


ADAPTER, COORDINATOR = _load_adapter_module()


class MinecraftAdapterEventTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        COORDINATOR.server_events.clear()
        COORDINATOR.region_chats.clear()
        self.adapter = object.__new__(ADAPTER.MinecraftPlatformAdapter)
        self.adapter.max_message_length = 1000
        self.adapter.server_event_push_enabled = True
        self.adapter.group_id = "minecraft"
        self.adapter.group_name = "Minecraft"
        self.adapter.bot_id = "astrbot"
        self.adapter.bot_display_name = "AstrBot"
        self.adapter.mention_aliases = set()
        self.adapter.connection_manager = object()
        self.events = []
        self.adapter.commit_event = self.events.append

    async def test_server_event_uses_server_identity_and_skips_region_chat(self):
        payload = {
            "type": "chat",
            "message_kind": "server_event",
            "event_type": "player_death",
            "message_id": "event-1",
            "server_id": "minecraft",
            "server_name": "MFMC",
            "player_uuid": "private-uuid",
            "player_name": "Alex",
            "content": "Alex fell from a high place",
        }

        await self.adapter._handle_chat(payload)

        self.assertEqual(1, len(COORDINATOR.server_events))
        self.assertEqual([], COORDINATOR.region_chats)
        message = self.events[0].message_obj
        self.assertEqual("mineastr-server:minecraft", message.sender.user_id)
        self.assertEqual("MFMC", message.sender.nickname)
        self.assertEqual("Alex", message.raw_message["player_name"])

    async def test_server_event_master_switch_drops_event(self):
        self.adapter.server_event_push_enabled = False

        await self.adapter._handle_chat({
            "message_kind": "server_event",
            "event_type": "player_join",
            "content": "Alex 加入了服务器。",
        })

        self.assertEqual([], COORDINATOR.server_events)
        self.assertEqual([], self.events)


if __name__ == "__main__":
    unittest.main()
