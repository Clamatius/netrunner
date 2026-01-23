"""Smoke tests for NAN tooling."""
import sys
import os

# Add parent dir to path for imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from log_parser import simplify_action, detect_side_from_action, parse_log_lines, generate_dsl
from nan_parser import NANParser
from nan_renderer import NANRenderer


class TestSimplifyAction:
    """Test action text -> NAN conversion."""

    def test_credit_corp(self):
        action = "spends [Click] to use Corp Basic Action Card to gain 1 [Credits]."
        assert simplify_action(action) == "credit"

    def test_credit_runner(self):
        action = "spends [Click] to use Runner Basic Action Card to gain 1 [Credits]."
        assert simplify_action(action) == "credit"

    def test_draw_corp(self):
        action = "spends [Click] to use Corp Basic Action Card to draw 1 card."
        assert simplify_action(action) == "draw"

    def test_install_ice(self):
        action = "spends [Click] to install ice protecting Server 1 (new remote)."
        assert simplify_action(action) == "ice S1"

    def test_install_ice_hq(self):
        action = "spends [Click] to install ice protecting HQ."
        assert simplify_action(action) == "ice HQ"

    def test_run(self):
        action = "spends [Click] to make a run on R&D."
        assert simplify_action(action) == "run R&D"

    def test_run_remote(self):
        action = "spends [Click] to make a run on Server 2."
        assert simplify_action(action) == "run S2"

    def test_score(self):
        action = "scores Send a Message and gains 1 agenda point."
        assert simplify_action(action) == "score Send a Message"

    def test_steal(self):
        action = "steals Offworld Office and gains 2 agenda points."
        assert simplify_action(action) == "steal Offworld Office"

    def test_damage_net(self):
        action = "suffers 2 net damage."
        assert simplify_action(action) == "damage 2 net"

    def test_damage_meat(self):
        action = "suffers 3 meat damage."
        assert simplify_action(action) == "damage 3 meat"

    def test_discard(self):
        action = "discards Sure Gamble from the grip."
        assert simplify_action(action) == "discard Sure Gamble"

    def test_rez_ice_with_position(self):
        action = "rez Palisade protecting Server 1 at position 0."
        assert simplify_action(action) == "rez Palisade@0 S1"


class TestDetectSide:
    """Test side detection from action content."""

    def test_corp_basic_action(self):
        action = "spends [Click] to use Corp Basic Action Card to gain 1 [Credits]."
        assert detect_side_from_action(action) == "Corp"

    def test_runner_basic_action(self):
        action = "spends [Click] to use Runner Basic Action Card to draw 1 card."
        assert detect_side_from_action(action) == "Runner"

    def test_mandatory_draw(self):
        action = "makes their mandatory start of turn draw."
        assert detect_side_from_action(action) == "Corp"

    def test_run_is_runner(self):
        action = "spends [Click] to make a run on HQ."
        assert detect_side_from_action(action) == "Runner"

    def test_score_is_corp(self):
        action = "scores Send a Message and gains 1 agenda point."
        assert detect_side_from_action(action) == "Corp"

    def test_steal_is_runner(self):
        action = "steals Offworld Office and gains 2 agenda points."
        assert detect_side_from_action(action) == "Runner"


class TestNANParser:
    """Test NAN file parsing."""

    def test_parse_simple_turn(self):
        parser = NANParser()
        result = parser.parse_line("Corp T1: credit; credit; ice HQ")
        assert result["player"] == "Corp"
        assert result["turn"] == 1
        assert len(result["actions"]) == 3

    def test_parse_with_score_checkpoint(self):
        parser = NANParser()
        result = parser.parse_line("Runner T5 [2-3]: run HQ; steal Agenda")
        assert result["player"] == "Runner"
        assert result["turn"] == 5
        assert result["score"]["corp"] == 2
        assert result["score"]["runner"] == 3

    def test_parse_action(self):
        parser = NANParser()
        action = parser.parse_action("ice HQ")
        assert action["verb"] == "ice"
        assert action["target"] == "HQ"


class TestNANRenderer:
    """Test board state rendering."""

    def test_install_ice(self):
        renderer = NANRenderer()
        renderer.apply_action("Corp", "ice HQ")
        assert len(renderer.servers["HQ"]) == 1
        assert renderer.servers["HQ"][0]["rezzed"] == False

    def test_rez_ice_with_position(self):
        renderer = NANRenderer()
        renderer.apply_action("Corp", "ice S1")
        renderer.apply_action("Corp", "ice S1")
        renderer.apply_action("Corp", "rez Palisade@0 S1")
        assert renderer.servers["S1"][0]["name"] == "Palisade"
        assert renderer.servers["S1"][0]["rezzed"] == True

    def test_runner_install(self):
        renderer = NANRenderer()
        renderer.apply_action("Runner", "install Cleaver")
        assert "Cleaver" in renderer.runner_rig

    def test_score_checkpoint_sync(self):
        renderer = NANRenderer()
        renderer.process_turn("Corp T3 [2-1]: credit")
        assert renderer.corp_score == 2
        assert renderer.runner_score == 1


class TestIntegration:
    """Integration tests for full pipeline."""

    def test_round_trip_simple(self):
        """Parse NAN, render state, verify consistency."""
        nan_content = """Corp T1 [0-0]: ice HQ; ice S1; credit
Runner T1 [0-0]: credit; credit; install Cleaver
Corp T2 [0-0]: install S1; advance S1; advance S1"""

        renderer = NANRenderer()
        renderer.parse_nan(nan_content)

        assert len(renderer.servers["HQ"]) == 1
        assert len(renderer.servers["S1"]) == 1
        assert len(renderer.roots["S1"]) == 1
        assert renderer.roots["S1"][0]["advancement"] == 2
        assert "Cleaver" in renderer.runner_rig
