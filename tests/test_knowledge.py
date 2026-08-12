import hashlib
import json
import asyncio
import sys
import types
import unittest


try:
    import aiohttp  # noqa: F401
except ImportError:
    aiohttp_module = types.ModuleType("aiohttp")

    class _AbstractResolver:
        pass

    class _DefaultResolver:
        async def resolve(self, *_args, **_kwargs):
            return []

        async def close(self):
            return None

    aiohttp_module.abc = types.SimpleNamespace(AbstractResolver=_AbstractResolver)
    aiohttp_module.resolver = types.SimpleNamespace(DefaultResolver=_DefaultResolver)
    aiohttp_module.ClientSession = object
    aiohttp_module.TCPConnector = object
    aiohttp_module.ClientTimeout = object
    sys.modules["aiohttp"] = aiohttp_module


if "astrbot.api" not in sys.modules:
    astrbot_module = types.ModuleType("astrbot")
    api_module = types.ModuleType("astrbot.api")

    class _Logger:
        def __getattr__(self, _name):
            return lambda *_args, **_kwargs: None

    api_module.logger = _Logger()
    astrbot_module.api = api_module
    sys.modules["astrbot"] = astrbot_module
    sys.modules["astrbot.api"] = api_module

import knowledge


class _FakeAdapter:
    def __init__(self):
        self.pages = {
            0: [{"id": "example:a"}, {"id": "example:b"}],
            2: [{"id": "example:c"}],
        }

    async def query_knowledge_page(self, _server_id, snapshot_id, category, cursor, _page_size):
        page = self.pages[cursor]
        return {
            "ok": True,
            "data": {
                "snapshot_id": snapshot_id,
                "category": category,
                "entries": page,
                "total": 3,
                "next_cursor": 2 if cursor == 0 else -1,
            },
        }


class _RedirectResponse:
    status = 302
    headers = {"Location": "https://other.example/secret"}
    url = "https://example.com/start"

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None


class _RedirectSession:
    def get(self, *_args, **_kwargs):
        return _RedirectResponse()


class KnowledgeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.coordinator = object.__new__(knowledge.KnowledgeCoordinator)
        self.coordinator.context = types.SimpleNamespace(kb_manager=None)
        self.coordinator._snapshots = {
            "server-a": {
                "snapshot_id": "abc",
                "categories": {
                    "mods": [{"id": "example", "name": "Example Machines", "version": "1.0"}],
                    "items": [{"id": "example:copper_gear", "name": "Copper Gear", "tags": []}],
                    "blocks": [{"id": "example:crusher", "name": "Crusher", "tags": []}],
                    "entities": [],
                    "fluids": [],
                    "recipes": [
                        {
                            "id": "example:crusher",
                            "namespace": "example",
                            "type": "minecraft:crafting",
                            "ingredients": [
                                {"alternatives": [{"id": "minecraft:iron_ingot", "name": "Iron Ingot"}]}
                            ],
                            "result": {"id": "example:crusher", "name": "Crusher", "count": 1},
                        }
                    ],
                },
                "enrichment": {},
                "activity_regions": {
                    "snapshot_id": "regions-1",
                    "regions": [{
                        "region_id": "region-demo",
                        "dimension": "minecraft:overworld",
                        "center_x_approx": 64,
                        "center_z_approx": -64,
                        "activity_minutes": 120,
                        "biomes": ["minecraft:plains"],
                        "surface_blocks": ["minecraft:grass_block"],
                        "contributors_private": [{
                            "contributor_key": hashlib.sha256(
                                b"mineastr:server-a:uuid-a"
                            ).hexdigest(),
                        }],
                    }],
                },
            }
        }
        self.coordinator._save_snapshot = lambda *_args: None
        self.coordinator._health = {}
        self.coordinator._server_info = {}
        self.coordinator._rescan_jobs = {}
        self.coordinator._locks = {}
        self.coordinator._tasks = {}
        self.coordinator._warned_missing_embedding = set()

    async def test_list_and_search_structured_content(self):
        mods = await self.coordinator.list_mods(None, "machine", 10)
        self.assertEqual(mods["mods"][0]["id"], "example")

        result = await self.coordinator.search(None, "copper_gear", "all", 10)
        self.assertEqual(result["results"][0]["id"], "example:copper_gear")
        self.assertIsNone(result["rag_context"])

    async def test_recipe_queries_both_directions(self):
        produces = await self.coordinator.recipes(None, "example:crusher", "produces", "", 10)
        self.assertEqual(produces["recipes"][0]["match"], "produces")

        uses = await self.coordinator.recipes(None, "minecraft:iron_ingot", "uses", "crafting", 10)
        self.assertEqual(uses["recipes"][0]["id"], "example:crusher")

    async def test_paginated_pull_requires_complete_snapshot(self):
        result = await self.coordinator._pull_category(
            _FakeAdapter(), "server-a", "abc", "items", 2
        )
        self.assertEqual([item["id"] for item in result], ["example:a", "example:b", "example:c"])

    def test_remote_url_guards_and_github_readme(self):
        with self.assertRaises(ValueError):
            self.coordinator._validate_public_url("http://example.com/wiki")
        with self.assertRaises(ValueError):
            self.coordinator._validate_public_url("https://127.0.0.1/private")
        self.assertEqual(
            self.coordinator._github_readme_url("https://github.com/owner/repository.git"),
            "https://api.github.com/repos/owner/repository/readme",
        )

    def test_rag_documents_are_fine_grained_and_stable(self):
        documents = self.coordinator._rag_documents(self.coordinator._snapshots["server-a"])
        self.assertIn("mod:example:overview", documents)
        self.assertIn("registry:example:items:0", documents)
        self.assertIn("registry:example:blocks:0", documents)
        self.assertIn("registry:example:recipes:0", documents)
        first = json.dumps(documents, sort_keys=True)
        self.coordinator._snapshots["server-a"]["categories"]["items"][0]["updated_at_ms"] = 999
        second = json.dumps(self.coordinator._rag_documents(self.coordinator._snapshots["server-a"]), sort_keys=True)
        self.assertEqual(first, second)

    def test_site_candidates_are_same_origin_and_drop_assets(self):
        candidates = []
        self.coordinator._add_same_origin_candidate(
            candidates, "https://example.com/", "/wiki", "https://example.com"
        )
        self.coordinator._add_same_origin_candidate(
            candidates, "https://example.com/", "https://evil.example/wiki", "https://example.com"
        )
        self.coordinator._add_same_origin_candidate(
            candidates, "https://example.com/", "/image.png", "https://example.com"
        )
        self.assertEqual(candidates, ["https://example.com/wiki"])

    def test_site_path_rules_run_before_ai_selection(self):
        allowed = self.coordinator._site_path_allowed
        self.assertTrue(allowed("/wiki/start", "/wiki/*", "/admin*"))
        self.assertFalse(allowed("/news", "/wiki/*", "/admin*"))
        self.assertFalse(allowed("/admin/users", "", "/admin*\n/api/*"))

    async def test_site_request_rejects_cross_origin_redirect_before_fetch(self):
        async def session():
            return _RedirectSession()

        self.coordinator._session_for_remote = session
        with self.assertRaisesRegex(RuntimeError, "不同来源"):
            await self.coordinator._request_limited(
                "https://example.com/start", allowed_origin="https://example.com"
            )

    async def test_llm_uses_active_chat_provider_when_not_configured(self):
        calls = []

        class _Provider:
            @staticmethod
            def meta():
                return types.SimpleNamespace(id="active-provider")

        async def llm_generate(**kwargs):
            calls.append(kwargs)
            return types.SimpleNamespace(completion_text="analysis result")

        self.coordinator.context = types.SimpleNamespace(
            kb_manager=None,
            get_using_provider=lambda: _Provider(),
            llm_generate=llm_generate,
        )
        result = await self.coordinator._llm_text(
            types.SimpleNamespace(knowledge_chat_provider_id=""), "prompt"
        )
        self.assertEqual(result, "analysis result")
        self.assertEqual(calls, [{"chat_provider_id": "active-provider", "prompt": "prompt"}])

    async def test_contributor_priority_uses_hash_and_public_region_hides_it(self):
        result = await self.coordinator.submit_region_description(
            "server-a", "region-demo", "这是我们的铁路枢纽", "uuid-a", "Alice", False
        )
        self.assertTrue(result["priority"])
        listed = self.coordinator.list_regions("server-a")
        rendered = json.dumps(listed, ensure_ascii=False)
        self.assertNotIn("uuid-a", rendered)
        self.assertNotIn("contributor_key", rendered)

    async def test_region_without_submission_becomes_marked_ai_draft(self):
        snapshot = self.coordinator._snapshots["server-a"]
        survey = {"status": "open", "submissions": []}
        await self.coordinator._finalize_region(types.SimpleNamespace(), snapshot, "region-demo", survey)
        region = snapshot["activity_regions"]["regions"][0]
        self.assertEqual(region["description"]["status"], "ai_unconfirmed")
        self.assertIn("AI 自动草稿", region["description"]["text"])

    def test_rag_includes_site_and_region_without_private_contributors(self):
        snapshot = self.coordinator._snapshots["server-a"]
        snapshot["server_site"] = {"pages": [{"title": "欢迎", "url": "https://example.com/", "text": "这是服务器介绍"}]}
        documents = self.coordinator._rag_documents(snapshot)
        self.assertIn("site:cad6bb88012ec4690af7", documents)
        self.assertIn("region:region-demo", documents)
        rendered = json.dumps(documents, ensure_ascii=False)
        self.assertNotIn("contributor_key", rendered)
        self.assertNotIn("uuid-a", rendered)

    def test_schema_v3_migration_adds_trust_and_sources(self):
        migrated = self.coordinator._migrate_snapshot(self.coordinator._snapshots["server-a"], "server-a")
        item = migrated["categories"]["items"][0]
        self.assertEqual(3, migrated["schema_version"])
        self.assertEqual("authoritative", item["source_trust"])
        self.assertEqual("observed", item["confirmation_status"])
        self.assertEqual("minecraft_runtime", item["sources"][0]["source_id"])

    async def test_search_resolves_conflicts_by_trust(self):
        entries = self.coordinator._snapshots["server-a"]["categories"]["items"]
        entries[:] = [
            {"id": "example:gear", "name": "remote", "source_trust": "reference"},
            {"id": "example:gear", "name": "runtime", "source_trust": "authoritative"},
        ]
        result = await self.coordinator.search("server-a", "example:gear", "items", 10)
        self.assertEqual(1, result["total"])
        self.assertEqual("runtime", result["results"][0]["name"])
        self.assertEqual("remote", result["results"][0]["supplemental_conflicts"][0]["name"])

    def test_source_management_is_atomic_override_data(self):
        snapshot = self.coordinator._migrate_snapshot(self.coordinator._snapshots["server-a"], "server-a")
        self.coordinator._snapshots["server-a"] = snapshot
        override = {"schema_version": 1, "sources": {}, "aliases": {}}
        self.coordinator._load_overrides = lambda _server_id: override
        saved = []
        self.coordinator._save_overrides = lambda server_id, data: saved.append((server_id, data))
        result = self.coordinator.manage_source("server-a", "set_alias", resource_id="example:copper_gear", alias="铜齿轮")
        self.assertTrue(result["ok"])
        self.assertEqual(["铜齿轮"], saved[-1][1]["aliases"]["example:copper_gear"])

    async def test_custom_recipe_codec_finds_multiple_outputs(self):
        recipe = self.coordinator._snapshots["server-a"]["categories"]["recipes"][0]
        recipe["serializer_data"] = {
            "ingredients": [{"item": "create:andesite_alloy"}],
            "results": [{"id": "create:track"}, {"id": "create:shaft"}],
        }
        result = await self.coordinator.recipes("server-a", "create:track", "produces", "", 10)
        self.assertEqual("example:crusher", result["recipes"][0]["id"])

    async def test_topic_context_exposes_current_names_but_no_sensitive_history(self):
        class Adapter:
            async def query_players(self, _server_id):
                return {"ok": True, "data": {"players": [{"name": "Alice", "uuid": "secret", "position": [1, 2, 3]}]}}

        snapshot = self.coordinator._snapshots["server-a"]
        snapshot["topic_events"] = [self.coordinator._event("region_discovered", "region-demo", 123, "new")]
        result = await self.coordinator.topic_context(Adapter(), "server-a", since_minutes=43200, limit=10)
        rendered = json.dumps(result, ensure_ascii=False)
        self.assertEqual(["Alice"], result["online"]["player_names"])
        self.assertNotIn("secret", rendered)
        self.assertNotIn("position", rendered)
        self.assertNotIn("online_history", rendered)

    async def test_server_events_enter_topic_history_without_player_uuid(self):
        accepted = await self.coordinator.receive_server_event({
            "server_id": "server-a",
            "event_type": "player_advancement",
            "time_ms": 123456,
            "player_uuid": "private-player-uuid",
            "player_name": "Alex",
            "advancement_id": "minecraft:story/mine_stone",
            "advancement_title": "Stone Age",
            "advancement_type": "task",
        }, "Alex 达成了进度：[Stone Age]")

        self.assertTrue(accepted)
        event = self.coordinator._snapshots["server-a"]["topic_events"][-1]
        self.assertEqual("player_advancement", event["type"])
        self.assertEqual("Alex", event["player_name"])
        self.assertEqual("minecraft:story/mine_stone", event["advancement_id"])
        self.assertNotIn("player_uuid", event)
        self.assertNotIn("private-player-uuid", json.dumps(event))

    def test_topic_event_id_is_stable(self):
        first = self.coordinator._event("region_discovered", "region-a", 1234, "one")
        second = self.coordinator._event("region_discovered", "region-a", 1234, "two")
        self.assertEqual(first["event_id"], second["event_id"])

    async def test_rescan_rejects_duplicate_job(self):
        gate = asyncio.Event()

        async def sync_server(_adapter, _server_id):
            await gate.wait()

        self.coordinator._sync_server = sync_server
        self.coordinator._server_info["server-a"] = {"capabilities": set()}
        first = await self.coordinator.rescan(types.SimpleNamespace(), "server-a", "rag")
        second = await self.coordinator.rescan(types.SimpleNamespace(), "server-a", "rag")
        self.assertTrue(first["accepted"])
        self.assertFalse(second["accepted"])
        gate.set()
        await self.coordinator._rescan_jobs["server-a"]

    async def test_restores_knowledge_sync_from_connection_after_plugin_reload(self):
        self.coordinator._snapshots = {}
        restored_calls = []
        self.coordinator.server_connected = lambda *args, **kwargs: restored_calls.append((args, kwargs))

        class Adapter:
            async def local_status(self):
                return {
                    "ok": True,
                    "servers": [{
                        "server_id": "minecraft", "mod_version": "0.7.0",
                        "minecraft_version": "1.21.1",
                        "query_capabilities": ["knowledge_manifest", "knowledge_page"],
                        "server_introduction_url": "https://example.com/",
                    }],
                }

        restored = await self.coordinator.restore_connected_servers(Adapter())

        self.assertEqual(["minecraft"], restored)
        self.assertEqual("minecraft", restored_calls[0][0][1])
        self.assertIn("knowledge_manifest", restored_calls[0][0][2])
        self.assertEqual("0.7.0", restored_calls[0][0][4]["mod_version"])

    async def test_lazy_ensure_pulls_snapshot_from_existing_connection(self):
        self.coordinator._snapshots = {}

        class Adapter:
            knowledge_sync_enabled = True
            modrinth_enrichment_enabled = False
            server_site_sync_enabled = False
            activity_region_sync_enabled = False
            knowledge_embedding_provider_id = ""

            async def local_status(self):
                return {
                    "ok": True,
                    "servers": [{
                        "server_id": "minecraft", "mod_version": "0.7.0",
                        "minecraft_version": "1.21.1",
                        "query_capabilities": ["knowledge_manifest", "knowledge_page"],
                    }],
                }

            async def query_knowledge_manifest(self, _server_id):
                return {
                    "ok": True, "server_name": "Test",
                    "data": {"ready": True, "snapshot_id": "live", "page_size": 100, "generated_at_ms": 1},
                }

            async def query_knowledge_page(self, _server_id, snapshot_id, category, _cursor, _page_size):
                entries = [{"id": "create:track", "namespace": "create", "name": "Train Track"}] if category == "items" else []
                return {
                    "ok": True,
                    "data": {
                        "snapshot_id": snapshot_id, "category": category, "entries": entries,
                        "total": len(entries), "next_cursor": -1,
                    },
                }

        await self.coordinator.ensure_snapshot(Adapter(), "minecraft", timeout_seconds=2)

        self.assertEqual("live", self.coordinator._snapshots["minecraft"]["snapshot_id"])
        self.assertEqual("create:track", self.coordinator._snapshots["minecraft"]["categories"]["items"][0]["id"])
        task = self.coordinator._tasks["minecraft"]
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    async def test_cached_snapshot_still_restores_background_sync(self):
        restored = []
        def record_connection(*args, **kwargs):
            server_id = args[1]
            restored.append(server_id)
            self.coordinator._server_info[server_id] = {"capabilities": set(args[2])}
        self.coordinator.server_connected = record_connection

        class Adapter:
            async def local_status(self):
                return {
                    "ok": True,
                    "servers": [{
                        "server_id": "server-a",
                        "query_capabilities": ["knowledge_manifest", "knowledge_page"],
                    }],
                }

        await self.coordinator.ensure_snapshot(Adapter(), "server-a")

        self.assertEqual(["server-a"], restored)

    async def test_legacy_adapter_instance_uses_generic_query_after_hot_upgrade(self):
        self.coordinator._snapshots = {}

        class ConnectionManager:
            async def query(self, query_type, server_id, params=None, timeout=0):
                self.last_call = (query_type, server_id, params, timeout)
                if query_type == "knowledge_manifest":
                    return {
                        "ok": True,
                        "server_name": "Legacy Adapter Test",
                        "data": {
                            "ready": True,
                            "snapshot_id": "legacy-live",
                            "page_size": 100,
                            "generated_at_ms": 1,
                        },
                    }
                if query_type == "knowledge_page":
                    entries = (
                        [{"id": "create:track", "namespace": "create", "name": "Train Track"}]
                        if params["category"] == "items" else []
                    )
                    return {
                        "ok": True,
                        "data": {
                            "snapshot_id": params["snapshot_id"],
                            "category": params["category"],
                            "entries": entries,
                            "total": len(entries),
                            "next_cursor": -1,
                        },
                    }
                raise AssertionError(query_type)

        class LegacyAdapter:
            knowledge_sync_enabled = True
            modrinth_enrichment_enabled = False
            server_site_sync_enabled = False
            activity_region_sync_enabled = False
            knowledge_embedding_provider_id = ""

            def __init__(self):
                self.connection_manager = ConnectionManager()

            async def local_status(self):
                return {
                    "ok": True,
                    "servers": [{
                        "server_id": "minecraft",
                        "mod_version": "0.7.0",
                    }],
                }

        await self.coordinator.ensure_snapshot(LegacyAdapter(), "minecraft", timeout_seconds=2)

        info = self.coordinator._server_info["minecraft"]
        self.assertIn("knowledge_manifest", info["capabilities"])
        self.assertEqual(
            "create:track",
            self.coordinator._snapshots["minecraft"]["categories"]["items"][0]["id"],
        )
        task = self.coordinator._tasks["minecraft"]
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    async def test_status_degrades_and_sanitizes_remote_error(self):
        class Adapter:
            async def local_status(self):
                return {"ok": True, "connected_count": 1, "servers": [{"server_id": "server-a", "last_seen_at": 99}]}

            async def query_knowledge_status(self, _server_id):
                return {"ok": False, "error": "token=abc\nfailed"}

        self.coordinator._server_info["server-a"] = {"capabilities": {"knowledge_status"}, "meta": {"mod_version": "0.7.0"}}
        result = await self.coordinator.knowledge_status(Adapter(), "server-a")
        rendered = json.dumps(result, ensure_ascii=False)
        self.assertEqual("error", result["overall"])
        self.assertNotIn("token=abc", rendered)
        self.assertIn("[redacted]", rendered)

    async def test_status_reports_cached_remote_source_failure_as_degraded(self):
        class Adapter:
            async def local_status(self):
                return {"ok": True, "connected_count": 1, "servers": [{"server_id": "server-a"}]}

        self.coordinator._server_info["server-a"] = {"capabilities": set(), "meta": {}}
        self.coordinator._health["server-a"] = {
            "state": "ok", "remote_sources": {"state": "degraded", "last_error": "timeout"},
            "rag": {"state": "ok"},
        }
        result = await self.coordinator.knowledge_status(Adapter(), "server-a")
        self.assertEqual("degraded", result["overall"])


if __name__ == "__main__":
    unittest.main()
