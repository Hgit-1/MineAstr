import asyncio
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest


def _identity_decorator(*_args, **_kwargs):
    return lambda value: value


def _load_main_module():
    package_name = "mineastr_testpkg"
    package = types.ModuleType(package_name)
    package.__path__ = []
    sys.modules[package_name] = package

    api = sys.modules.setdefault("astrbot.api", types.ModuleType("astrbot.api"))
    api.logger = types.SimpleNamespace(info=lambda *_a, **_k: None, warning=lambda *_a, **_k: None)
    event_module = types.ModuleType("astrbot.api.event")
    event_module.AstrMessageEvent = object
    event_module.filter = types.SimpleNamespace(
        on_llm_request=_identity_decorator,
        llm_tool=_identity_decorator,
    )
    star_module = types.ModuleType("astrbot.api.star")

    class Star:
        def __init__(self, context):
            self.context = context

    star_module.Context = object
    star_module.Star = Star
    star_module.register = _identity_decorator
    sys.modules["astrbot.api.event"] = event_module
    sys.modules["astrbot.api.star"] = star_module

    knowledge_module = types.ModuleType(f"{package_name}.knowledge")
    knowledge_module.KnowledgeCoordinator = object
    sys.modules[f"{package_name}.knowledge"] = knowledge_module

    minecraft_adapter_module = types.ModuleType(f"{package_name}.minecraft_adapter")
    minecraft_adapter_module.DEFAULT_CONFIG = {
        "host": "127.0.0.1",
        "port": 8765,
        "path": "/ws",
        "token": "change-me",
        "bot_display_name": "AstrBot",
    }
    minecraft_adapter_module.select_live_platform_adapter = lambda adapter: adapter
    sys.modules[f"{package_name}.minecraft_adapter"] = minecraft_adapter_module

    path = Path(__file__).resolve().parents[1] / "main.py"
    spec = importlib.util.spec_from_file_location(f"{package_name}.main", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


MAIN = _load_main_module()


class MainHelperTests(unittest.IsolatedAsyncioTestCase):
    async def test_cold_start_creates_platform_without_double_loading(self):
        class CoreConfig(dict):
            save_calls = 0

            def save_config(self):
                self.save_calls += 1

        core_config = CoreConfig(platform=[])
        manager = types.SimpleNamespace(platform_insts=[], _platform_tasks={})
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._config = {"host": "0.0.0.0", "token": "configured-token"}
        plugin.context = types.SimpleNamespace(
            get_platform_inst=lambda _name: None,
            get_config=lambda: core_config,
            platform_manager=manager,
        )

        result = await plugin._ensure_minecraft_platform()

        self.assertEqual("scheduled", result["status"])
        self.assertEqual(1, core_config.save_calls)
        self.assertEqual(1, len(core_config["platform"]))
        self.assertEqual("minecraft", core_config["platform"][0]["type"])
        self.assertTrue(core_config["platform"][0]["enable"])
        self.assertEqual("0.0.0.0", core_config["platform"][0]["host"])
        self.assertEqual("configured-token", core_config["platform"][0]["token"])

    async def test_hot_reload_enables_existing_platform_without_overwriting_connection(self):
        class CoreConfig(dict):
            save_calls = 0

            def save_config(self):
                self.save_calls += 1

        existing = {
            "type": "minecraft", "id": "minecraft", "enable": False,
            "host": "192.0.2.10", "token": "existing-secret",
        }
        core_config = CoreConfig(platform=[existing])
        state = {"loaded": False}

        class Manager:
            platform_insts = [object()]

            async def reload(self, selected):
                self.selected = selected
                state["loaded"] = True

        manager = Manager()
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._config = {"host": "127.0.0.1", "token": "change-me"}
        plugin.context = types.SimpleNamespace(
            get_platform_inst=lambda _name: None,
            get_config=lambda: core_config,
            platform_manager=manager,
        )
        plugin._minecraft_adapter = lambda: object() if state["loaded"] else None

        result = await plugin._ensure_minecraft_platform()

        self.assertEqual("loaded", result["status"])
        self.assertTrue(existing["enable"])
        self.assertEqual("192.0.2.10", existing["host"])
        self.assertEqual("existing-secret", existing["token"])
        self.assertIs(existing, manager.selected)

    async def test_auto_activation_can_be_disabled_for_manual_management(self):
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._config = {"auto_enable_platform": False}
        plugin.context = types.SimpleNamespace(get_platform_inst=lambda _name: None)

        result = await plugin._ensure_minecraft_platform()

        self.assertEqual("manual", result["status"])

    def test_plugin_config_is_applied_to_live_adapter(self):
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._config = {
            "knowledge_embedding_provider_id": "embedding-provider",
            "knowledge_sync_enabled": True,
            "agent_require_admin_approval": True,
        }
        adapter = types.SimpleNamespace(config={})

        plugin._apply_plugin_adapter_config(adapter)

        self.assertEqual("embedding-provider", adapter.knowledge_embedding_provider_id)
        self.assertTrue(adapter.knowledge_sync_enabled)
        self.assertTrue(adapter.agent_require_admin_approval)
        self.assertEqual("embedding-provider", adapter.config["knowledge_embedding_provider_id"])

    def test_missing_plugin_config_preserves_platform_values(self):
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._config = {}
        adapter = types.SimpleNamespace(
            config={"knowledge_embedding_provider_id": "platform-provider"},
            knowledge_embedding_provider_id="platform-provider",
        )

        plugin._apply_plugin_adapter_config(adapter)

        self.assertEqual("platform-provider", adapter.knowledge_embedding_provider_id)

    def test_only_active_request_tools_are_reported(self):
        tool_set = types.SimpleNamespace(tools=[
            types.SimpleNamespace(name="mineastr_get_recipes", active=True),
            types.SimpleNamespace(name="mineastr_run_server_command", active=False),
        ])
        request = types.SimpleNamespace(func_tool=tool_set, tools=None)
        self.assertEqual(
            {"mineastr_get_recipes"},
            MAIN.MineAstrPlugin._available_tool_names(request),
        )

    async def test_admin_write_tool_rejects_non_admin(self):
        calls = []
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._knowledge = types.SimpleNamespace(
            manage_source=lambda *args: calls.append(args) or {"ok": True}
        )
        event = types.SimpleNamespace(
            is_admin=lambda: False,
            message_obj=types.SimpleNamespace(raw_message={"server_id": "server-a"}),
        )
        result = await plugin.mineastr_manage_knowledge_source(event, "exclude", source_id="site:x")
        self.assertFalse(json.loads(result.split("\n", 1)[1])["ok"])
        self.assertEqual([], calls)

    async def test_admin_write_tool_allows_admin(self):
        calls = []
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._knowledge = types.SimpleNamespace(
            manage_source=lambda *args: calls.append(args) or {"ok": True}
        )
        async def ensure(_server_id):
            return None
        plugin._ensure_knowledge_snapshot = ensure
        event = types.SimpleNamespace(
            is_admin=lambda: True,
            message_obj=types.SimpleNamespace(raw_message={"server_id": "server-a"}),
        )
        result = await plugin.mineastr_manage_knowledge_source(event, "exclude", source_id="site:x")
        self.assertTrue(json.loads(result.split("\n", 1)[1])["ok"])
        self.assertEqual("server-a", calls[0][0])

    async def test_search_lazily_waits_for_snapshot_after_hot_reload(self):
        calls = []

        class Knowledge:
            async def ensure_snapshot(self, adapter, server_id):
                calls.append(("ensure", adapter, server_id))

            async def search(self, server_id, query, category, limit):
                calls.append(("search", server_id, query, category, limit))
                return {"ok": True, "results": []}

        adapter = object()
        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin.context = types.SimpleNamespace(get_platform_inst=lambda _name: adapter)
        plugin._knowledge = Knowledge()
        plugin._minecraft_adapter = lambda: adapter
        event = types.SimpleNamespace(
            message_obj=types.SimpleNamespace(raw_message={"server_id": "minecraft"}),
        )

        result = await plugin.mineastr_search_server_content(event, "track", category="items")

        self.assertIn('"ok": true', result)
        self.assertEqual("ensure", calls[0][0])
        self.assertEqual("minecraft", calls[0][2])
        self.assertEqual("search", calls[1][0])

    async def test_server_event_prompt_is_not_treated_as_player_quote(self):
        plugin = object.__new__(MAIN.MineAstrPlugin)
        event = types.SimpleNamespace(
            message_str="Alex 达成了进度：[Stone Age]",
            get_platform_id=lambda: "minecraft",
            message_obj=types.SimpleNamespace(raw_message={
                "message_kind": "server_event",
                "event_type": "player_advancement",
            }),
        )
        request = types.SimpleNamespace(system_prompt="", func_tool=None, tools=None)

        await plugin.mineastr_on_llm_request(event, request)

        self.assertIn("不是玩家发言", request.system_prompt)

    async def test_agent_admin_approval_blocks_non_admin_task(self):
        calls = []

        class Adapter:
            agent_require_admin_approval = True

            async def submit_agent_task(self, *args):
                calls.append(args)
                return {"ok": True}

        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._minecraft_adapter = lambda: Adapter()
        event = types.SimpleNamespace(
            is_admin=lambda: False,
            message_obj=types.SimpleNamespace(raw_message={"server_id": "server-a"}),
        )

        result = await plugin.mineastr_submit_agent_task(event, "eat")

        self.assertFalse(json.loads(result.split("\n", 1)[1])["ok"])
        self.assertEqual([], calls)

    async def test_agent_cancel_is_available_without_admin_approval(self):
        calls = []

        class Adapter:
            async def cancel_agent_task(self, server_id):
                calls.append(server_id)
                return {"ok": True, "canceled": True}

        plugin = object.__new__(MAIN.MineAstrPlugin)
        plugin._minecraft_adapter = lambda: Adapter()
        event = types.SimpleNamespace(
            is_admin=lambda: False,
            message_obj=types.SimpleNamespace(raw_message={"server_id": "server-a"}),
        )

        result = await plugin.mineastr_cancel_agent_task(event)

        self.assertTrue(json.loads(result.split("\n", 1)[1])["ok"])
        self.assertEqual(["server-a"], calls)


if __name__ == "__main__":
    unittest.main()
