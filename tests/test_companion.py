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
path = Path(__file__).resolve().parents[1] / "companion.py"
spec = importlib.util.spec_from_file_location("mineastr_companion_test", path)
COMPANION = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = COMPANION
spec.loader.exec_module(COMPANION)


class CompanionCoordinatorTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.clock_value = 1000.0
        self.sent = []
        self.queries = []
        self.tasks = []

        class Context:
            async def llm_generate(inner, **kwargs):
                self.queries.append(kwargs)
                return types.SimpleNamespace(completion_text="这条路还挺有意思的，我跟着你。")

        class Adapter:
            bot_display_name = "Aria"
            knowledge_chat_provider_id = "provider-a"

            async def manage_agent_companion(inner, server_id, action, **values):
                return {"ok": True, "data": {"enabled": True, "companion": {
                    "session_id": values.get("session_id"), "focus_player": values.get("focus_player")
                }}}

            async def query_agent_status(inner, _server_id):
                return {"ok": True, "data": {"agent": {
                    "human_player_count": 1, "position": {"x": 1, "y": 64, "z": 2},
                    "active_task": {"task_type": "goto", "state": "running"},
                }}}

            async def query_agent_events(inner, _server_id, _sequence, _limit):
                return {"ok": True, "data": {"last_sequence": 3, "events": [
                    {"sequence": 3, "type": "navigation_route_planned"}
                ]}}

            async def send_server_chat(inner, server_id, content):
                self.sent.append((server_id, content))

            async def observe_agent(inner, _server_id, _distance):
                return {"ok": True, "data": {"position": {"x": 1, "y": 64, "z": 2}}}

            async def submit_agent_task(inner, server_id, task_type, args, *_rest):
                self.tasks.append((server_id, task_type, args))
                return {"ok": True, "data": {"completed": True, "task": {"state": "completed"}}}

        self.adapter = Adapter()
        self.coordinator = COMPANION.CompanionCoordinator(
            Context(), lambda: self.adapter,
            {"agent_companion_enabled": True, "agent_companion_autonomous_planning_enabled": False,
             "agent_companion_chat_min_seconds": 20,
             "agent_companion_chat_max_seconds": 60},
            state_path=Path(self.temporary.name) / "sessions.json",
            clock=lambda: self.clock_value,
            random_uniform=lambda low, high: low,
        )

    def tearDown(self):
        self.temporary.cleanup()

    async def test_manage_persists_session_and_proactive_chat_uses_events(self):
        await self.coordinator.manage(
            self.adapter, "server-a", "start", focus_player="Alex", goal="一起探索山路"
        )
        self.clock_value = 1020.0
        await self.coordinator.tick()

        self.assertEqual([("server-a", "这条路还挺有意思的，我跟着你。")], self.sent)
        self.assertIn("重点陪伴玩家：Alex", self.queries[0]["prompt"])
        self.assertEqual(3, self.coordinator.sessions["server-a"]["event_sequence"])
        restored = COMPANION.CompanionCoordinator(
            types.SimpleNamespace(), lambda: None, {"agent_companion_enabled": True},
            state_path=Path(self.temporary.name) / "sessions.json",
        )
        self.assertEqual("Alex", restored.sessions["server-a"]["focus_player"])

    async def test_no_human_player_means_no_model_call_or_broadcast(self):
        await self.coordinator.manage(self.adapter, "server-a", "start", focus_player="Alex")

        async def no_humans(_server_id):
            return {"ok": True, "data": {"agent": {"human_player_count": 0}}}

        self.adapter.query_agent_status = no_humans
        self.clock_value = 1020.0
        await self.coordinator.tick()
        self.assertEqual([], self.queries)
        self.assertEqual([], self.sent)

    async def test_model_failure_is_silent_and_uses_exponential_backoff(self):
        await self.coordinator.manage(self.adapter, "server-a", "start", focus_player="Alex")

        async def fail(**_kwargs):
            raise RuntimeError("provider down")

        self.coordinator.context.llm_generate = fail
        self.clock_value = 1020.0
        await self.coordinator.tick()
        session = self.coordinator.sessions["server-a"]
        self.assertEqual(1, session["failure_count"])
        self.assertEqual(1080.0, session["next_dialogue_at"])
        self.assertEqual([], self.sent)

    async def test_explicit_enable_is_required(self):
        disabled = COMPANION.CompanionCoordinator(
            types.SimpleNamespace(), lambda: self.adapter, {},
            state_path=Path(self.temporary.name) / "disabled.json",
        )
        with self.assertRaises(RuntimeError):
            await disabled.manage(self.adapter, "server-a", "start", focus_player="Alex")

    async def test_high_level_planner_executes_one_validated_reversible_action(self):
        await self.coordinator.manage(
            self.adapter, "server-a", "start", focus_player="Alex", goal="跟着 Alex 逛逛"
        )

        async def plan(**kwargs):
            self.queries.append(kwargs)
            return '{"action":"follow_player","args":{"seconds":9,"distance":3},"summary":"跟上 Alex"}'

        async def idle_status(_server_id):
            return {"ok": True, "data": {"agent": {
                "human_player_count": 1, "human_players": ["Alex"], "active_task": None,
            }}}

        self.coordinator.context.llm_generate = plan
        self.adapter.query_agent_status = idle_status
        await self.coordinator._action_turn(
            self.adapter, "server-a", self.coordinator.sessions["server-a"]
        )

        self.assertEqual([("server-a", "follow_player", {
            "player_name": "Alex", "seconds": 9, "distance": 3,
        })], self.tasks)
        self.assertEqual("跟上 Alex", self.coordinator.sessions["server-a"]["last_action_summary"])

    def test_planner_rejects_irreversible_or_unstructured_actions(self):
        self.assertEqual(("", {}), self.coordinator._validated_action(
            {"action": "furnace_process", "args": {"x": 1, "y": 2, "z": 3}},
            {"focus_player": "Alex"},
        ))
        self.assertEqual(("", {}), self.coordinator._validated_action(
            {"action": "goto", "args": {"x": "bad"}}, {"focus_player": "Alex"},
        ))


if __name__ == "__main__":
    unittest.main()
