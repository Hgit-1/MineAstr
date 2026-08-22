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

    def test_plugin_operational_config_reaches_adapter_created_later(self):
        ADAPTER.configure_plugin_operational_settings({
            "knowledge_embedding_provider_id": "embedding-provider",
            "agent_require_admin_approval": True,
        })
        try:
            adapter = ADAPTER.MinecraftPlatformAdapter({}, {}, None)
            self.assertEqual("embedding-provider", adapter.knowledge_embedding_provider_id)
            self.assertTrue(adapter.agent_require_admin_approval)
        finally:
            ADAPTER.configure_plugin_operational_settings({})

    async def test_hello_sends_runtime_bot_display_name_to_server(self):
        sent = []

        class ConnectionManager:
            async def register(self, _ws, _payload):
                return None

            async def send_error(self, _ws, message):
                sent.append({"type": "error", "message": message})

        class WebSocket:
            async def send_json(self, payload):
                sent.append(payload)

        self.adapter.connection_manager = ConnectionManager()
        self.adapter.bot_display_name = "Aria"

        await self.adapter._handle_hello(WebSocket(), {
            "protocol": ADAPTER.PROTOCOL_VERSION,
            "server_id": "minecraft",
            "server_name": "MFMC",
        })

        self.assertEqual("configuration", sent[0]["type"])
        self.assertEqual("Aria", sent[0]["bot_display_name"])

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

    async def test_agent_task_is_typed_and_carries_admin_approval(self):
        calls = []

        class Manager:
            async def query(self, query, server_id, params=None, timeout=0):
                calls.append((query, server_id, params, timeout))
                return {"ok": True}

        self.adapter.connection_manager = Manager()
        self.adapter.agent_actions_enabled = True

        result = await self.adapter.submit_agent_task(
            "server-a", "goto", {"x": 1, "y": 64, "z": 2}, "task-1", True,
            {"requester_id": "player-1", "requester_name": "Alex"},
        )

        self.assertTrue(result["ok"])
        self.assertEqual("agent_task", calls[0][0])
        self.assertEqual("task-1", calls[0][2]["task_id"])
        self.assertTrue(calls[0][2]["approved_by_admin"])
        self.assertEqual("player-1", calls[0][2]["requester_id"])
        self.assertEqual("Alex", calls[0][2]["requester_name"])

    async def test_agent_master_switch_blocks_mutation_but_not_observation(self):
        calls = []

        class Manager:
            async def query(self, query, server_id, params=None, timeout=0):
                calls.append(query)
                return {"ok": True}

        self.adapter.connection_manager = Manager()
        self.adapter.agent_actions_enabled = False
        self.adapter.agent_observation_distance = 8

        with self.assertRaises(RuntimeError):
            await self.adapter.submit_agent_task(None, "eat", {})
        observed = await self.adapter.observe_agent(None, 99)
        self.assertTrue(observed["ok"])
        self.assertEqual(["agent_observe"], calls)

    async def test_hot_reload_adopts_pre_shared_live_connection_manager(self):
        managers = ADAPTER._runtime_connection_managers()
        managers.clear()
        original = ADAPTER.MinecraftPlatformAdapter({}, {}, None)
        websocket = object()
        await original.connection_manager.register(websocket, {
            "server_id": "minecraft",
            "server_name": "MFMC",
            "mod_version": "0.9.0",
            "minecraft_version": "1.21.1",
            "query_capabilities": ["knowledge_manifest", "knowledge_page"],
        })

        # Simulate upgrading from 0.9.0, which had no process-wide registry.
        managers.clear()
        replacement = ADAPTER.MinecraftPlatformAdapter({}, {}, None)

        self.assertIs(original.connection_manager, replacement.connection_manager)
        status = await replacement.local_status()
        self.assertEqual(1, status["connected_count"])
        self.assertEqual("minecraft", status["servers"][0]["server_id"])
        self.assertTrue(status["shared_connection_state"])

        await replacement.connection_manager.unregister(websocket)
        ADAPTER._runtime_connection_managers().clear()

    async def test_plugin_reload_selects_adapter_with_live_connection(self):
        ADAPTER._runtime_connection_managers().clear()
        active = ADAPTER.MinecraftPlatformAdapter({}, {}, None)
        websocket = object()
        await active.connection_manager.register(websocket, {
            "server_id": "minecraft",
            "query_capabilities": ["knowledge_manifest", "knowledge_page"],
        })
        preferred = object.__new__(ADAPTER.MinecraftPlatformAdapter)
        preferred.host, preferred.port, preferred.path = active.host, active.port, active.path
        preferred.connection_manager = ADAPTER.MinecraftConnectionManager("AstrBot", 2000)

        selected = ADAPTER.select_live_platform_adapter(preferred)

        self.assertIs(active, selected)
        await active.connection_manager.unregister(websocket)
        ADAPTER._runtime_connection_managers().clear()


if __name__ == "__main__":
    unittest.main()
