import hashlib
import json
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

    def test_rag_documents_group_registry_by_mod(self):
        documents = self.coordinator._rag_documents(self.coordinator._snapshots["server-a"])
        self.assertIn("example", documents)
        rendered = json.dumps(documents["example"], ensure_ascii=False)
        self.assertIn("example:crusher", rendered)
        self.assertIn("example:copper_gear", rendered)

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
        self.assertIn("server_site", documents)
        self.assertIn("server_regions", documents)
        rendered = json.dumps(documents, ensure_ascii=False)
        self.assertNotIn("contributor_key", rendered)
        self.assertNotIn("uuid-a", rendered)


if __name__ == "__main__":
    unittest.main()
