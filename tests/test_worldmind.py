import importlib.util
from pathlib import Path
import sys
import tempfile
import types
import unittest


api = sys.modules.setdefault("astrbot.api", types.ModuleType("astrbot.api"))
api.logger = types.SimpleNamespace(
    debug=lambda *_a, **_k: None,
    info=lambda *_a, **_k: None,
    warning=lambda *_a, **_k: None,
)
package_name = "mineastr_worldmind_testpkg"
package = types.ModuleType(package_name)
package.__path__ = []
sys.modules[package_name] = package
knowledge = types.ModuleType(f"{package_name}.knowledge")
knowledge.get_knowledge_coordinator = lambda: None
sys.modules[knowledge.__name__] = knowledge
path = Path(__file__).resolve().parents[1] / "worldmind.py"
spec = importlib.util.spec_from_file_location(f"{package_name}.worldmind", path)
WORLDMIND = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = WORLDMIND
spec.loader.exec_module(WORLDMIND)


class FakeAdapter:
    def __init__(self):
        self.tasks = []

    async def query_worldmind_manifest(self, _server_id):
        return {"ok": True, "data": {"snapshot_id": "snap-1", "server_fingerprint": "fp-1",
                                      "categories": {"nodes": 1, "demonstrations": 1}}}

    async def query_worldmind_page(self, _server_id, _snapshot_id, category, cursor, _page_size):
        if category == "nodes":
            entries = [{"node_id": "device-1", "node_type": "device", "name": "Machine", "state": "candidate",
                        "x": 123, "y": 67, "z": -456}]
        else:
            entries = [{
                "trace_id": "trace-1", "name": "开机器", "sandbox_eligible": True,
                "events": [
                    {"event_type": "block_interact", "dimension": "minecraft:overworld", "x": 10, "y": 64, "z": 20, "offset_ms": 0},
                    {"event_type": "block_interact", "dimension": "minecraft:overworld", "x": 11, "y": 64, "z": 20, "offset_ms": 800},
                ],
            }]
        return {"ok": True, "data": {"snapshot_id": "snap-1", "entries": entries if cursor == 0 else [], "next_cursor": -1}}

    async def query_activity_regions_manifest(self, _server_id):
        return {"ok": False}

    async def manage_agent_waypoint(self, _server_id, _action):
        return {"ok": True, "data": {"waypoints": [], "links": []}}

    async def submit_agent_task(self, server_id, action, args, *_rest, **kwargs):
        self.tasks.append((server_id, action, args, kwargs))
        return {"ok": True, "data": {"ok": True, "completed": True}}


class WorldMindTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock_value = 1000.0
        self.coordinator = WORLDMIND.WorldMindCoordinator(
            types.SimpleNamespace(), {}, data_root=Path(self.temp.name), clock=lambda: self.clock_value
        )
        self.adapter = FakeAdapter()

    async def asyncTearDown(self):
        await self.coordinator.close()
        self.temp.cleanup()

    def test_skill_validator_rejects_commands_l3_and_non_list_conditions(self):
        base = {
            "skill_id": "safe", "anchor": {"x": 0, "y": 64, "z": 0},
            "steps": [{"action": "interact_block", "args": {}}],
        }
        with self.assertRaises(ValueError):
            WORLDMIND.validate_skill({**base, "steps": [{"action": "chat", "args": {"message": "/op me"}}]})
        with self.assertRaises(ValueError):
            WORLDMIND.validate_skill({**base, "risk_level": 3})
        with self.assertRaises(ValueError):
            WORLDMIND.validate_skill({**base, "preconditions": "not-a-list"})

    async def test_sync_and_rule_compile_use_relative_steps(self):
        result = await self.coordinator.sync(self.adapter, "minecraft")
        self.assertEqual(1, result["demonstration_count"])
        skill = await self.coordinator.compile_trace(self.adapter, "minecraft", "trace-1")
        interactions = [step for step in skill["steps"] if step["action"] == "interact_block"]
        self.assertEqual({"x": 0, "y": 0, "z": 0}, interactions[0]["args"]["relative"])
        self.assertEqual({"x": 1, "y": 0, "z": 0}, interactions[1]["args"]["relative"])
        self.assertEqual("candidate", skill["state"])
        results = self.coordinator.search("minecraft", "Machine", 5)["results"]
        self.assertNotIn("x", results[0])
        self.assertEqual({"x": 128, "y": 64, "z": -448, "precision": "x/z≈16格,y≈8格"},
                         results[0]["position_approx"])
        skill_results = self.coordinator.search("minecraft", "开机器", 5)["results"]
        self.assertEqual("skill", skill_results[0]["kind"])
        self.assertNotIn("anchor", skill_results[0])
        self.assertNotIn("steps", skill_results[0])

    async def test_model_coordinates_outside_demonstration_fall_back_to_bound_rule(self):
        async def llm_generate(**_kwargs):
            return types.SimpleNamespace(completion_text='''{
              "skill_id":"escaped","anchor":{"x":10,"y":64,"z":20},"risk_level":2,
              "steps":[{"action":"dig_block","args":{"relative":{"x":100,"y":0,"z":0}}}]
            }''')

        self.coordinator.context = types.SimpleNamespace(
            llm_generate=llm_generate,
            get_using_provider=lambda: types.SimpleNamespace(meta=lambda: types.SimpleNamespace(id="provider")),
        )
        await self.coordinator.sync(self.adapter, "minecraft")
        skill = await self.coordinator.compile_trace(self.adapter, "minecraft", "trace-1")
        self.assertNotEqual("escaped", skill["skill_id"])
        self.assertTrue(all(step["action"] in {"wait", "interact_block"} for step in skill["steps"]))

    async def test_three_sandbox_successes_then_requester_bound_one_use_confirmation(self):
        await self.coordinator.sync(self.adapter, "minecraft")
        skill = await self.coordinator.compile_trace(self.adapter, "minecraft", "trace-1")
        skill_id = skill["skill_id"]
        self.coordinator.manage_skill("minecraft", "confirm", skill_id)
        for expected in (1, 2, 3):
            result = await self.coordinator.run_skill(
                self.adapter, "minecraft", skill_id, validation=True, approved_by_admin=True
            )
            self.assertEqual(expected, result["validation_successes"])
        prepared = self.coordinator.manage_skill("minecraft", "prepare", skill_id, requester="alice")
        with self.assertRaises(PermissionError):
            await self.coordinator.run_skill(
                self.adapter, "minecraft", skill_id,
                confirmation_id=prepared["confirmation_id"], requester="bob",
            )
        prepared = self.coordinator.manage_skill("minecraft", "prepare", skill_id, requester="alice")
        result = await self.coordinator.run_skill(
            self.adapter, "minecraft", skill_id,
            confirmation_id=prepared["confirmation_id"], requester="alice",
        )
        self.assertTrue(result["ok"])
        with self.assertRaises(PermissionError):
            await self.coordinator.run_skill(
                self.adapter, "minecraft", skill_id,
                confirmation_id=prepared["confirmation_id"], requester="alice",
            )
        self.assertTrue(all(call[3].get("confirmed_irreversible") for call in self.adapter.tasks))

    async def test_registry_fingerprint_change_marks_skill_stale_before_execution(self):
        await self.coordinator.sync(self.adapter, "minecraft")
        skill = await self.coordinator.compile_trace(self.adapter, "minecraft", "trace-1")
        self.coordinator.manage_skill("minecraft", "confirm", skill["skill_id"])
        self.coordinator.snapshots["minecraft"]["status"]["server_fingerprint"] = "fp-2"
        with self.assertRaisesRegex(ValueError, "stale"):
            await self.coordinator.run_skill(
                self.adapter, "minecraft", skill["skill_id"], validation=True, approved_by_admin=True
            )
        self.assertEqual("stale", self.coordinator.skills["minecraft"][skill["skill_id"]]["state"])


if __name__ == "__main__":
    unittest.main()
