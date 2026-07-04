"""Smoke tests for NAN tooling."""
import sys
import os

# Add parent dir to path for imports
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from log_parser import (
    simplify_action,
    detect_side_from_action,
    parse_log_lines,
    generate_dsl,
    extract_credit_delta,
)
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


class TestExtractCreditDelta:
    """Credit deltas / checkpoints parsed from raw log action text."""

    def test_basic_action_gain(self):
        action = "spends [Click] to use Corp Basic Action Card to gain 1 [Credits]."
        assert extract_credit_delta(action) == ("delta", 1)

    def test_pay_to_play(self):
        action = "spends [Click] and pays 5 [Credits] to play Sure Gamble."
        assert extract_credit_delta(action) == ("delta", -5)

    def test_use_to_gain(self):
        action = "uses Sure Gamble to gain 9 [Credits]."
        assert extract_credit_delta(action) == ("delta", 9)

    def test_pay_to_rez(self):
        action = "pays 4 [Credits] to rez Karunā protecting R&D at position 0."
        assert extract_credit_delta(action) == ("delta", -4)

    def test_lose_credits(self):
        action = "loses 2 [Credits]."
        assert extract_credit_delta(action) == ("delta", -2)

    def test_agenda_points_not_credits(self):
        action = "steals Offworld Office and gains 2 agenda points."
        assert extract_credit_delta(action) is None

    def test_no_credits(self):
        action = "spends [Click] to make a run on HQ."
        assert extract_credit_delta(action) is None

    def test_turn_start_checkpoint(self):
        action = "started their turn 3 with 10 [Credit] and 5 cards in their Grip."
        assert extract_credit_delta(action) == ("set", 10)

    def test_turn_end_checkpoint(self):
        action = "is ending their turn 5 with 3 [Credit] and 5 cards in their Grip."
        assert extract_credit_delta(action) == ("set", 3)

    def test_zero_delta_still_reported(self):
        action = "spends [Click] and pays 0 [Credits] to play Jailbreak."
        assert extract_credit_delta(action) == ("delta", 0)

    def test_quoted_subroutine_text_not_a_payment(self):
        # "resolves unbroken subroutines" lines quote card text verbatim;
        # nothing was actually paid or gained.
        action = ('resolves 2 unbroken subroutines on Funhouse '
                  '("[subroutine] Give the Runner 1 tag unless they pay '
                  '4 [Credits]" and "[subroutine] End the run").')
        assert extract_credit_delta(action) is None

    def test_quoted_text_not_a_beneficiary(self):
        from log_parser import credit_beneficiary
        action = ('resolves 1 unbroken subroutine on Lamplighter '
                  '("[subroutine] The Runner loses 2 [Credits]").')
        assert credit_beneficiary(action) is None


class TestCreditBeneficiary:
    """Lines where the actor and the credit recipient differ."""

    def test_forced_gain_names_the_runner(self):
        from log_parser import credit_beneficiary
        action = "uses Wildcat Strike to force the Runner to gain 6 [Credits]."
        assert credit_beneficiary(action) == "Runner"

    def test_forced_loss_names_the_corp(self):
        from log_parser import credit_beneficiary
        action = "uses Diversion of Funds to force the Corp to lose 5 [Credits]."
        assert credit_beneficiary(action) == "Corp"

    def test_plain_action_has_no_beneficiary(self):
        from log_parser import credit_beneficiary
        action = "spends [Click] and pays 5 [Credits] to play Sure Gamble."
        assert credit_beneficiary(action) is None


class TestOwnSideInstallPhrasing:
    """Own-seat logs name the installed card; NAN stays canonical."""

    def test_facedown_ice_install(self):
        action = "spends [Click] and pays 0 [Credits] to install facedown Diviner protecting R&D."
        assert simplify_action(action) == "ice R&D"

    def test_facedown_root_install(self):
        action = "spends [Click] to install facedown Superconducting Hub in the root of Server 2 (new remote)."
        assert simplify_action(action) == "install S2"

    def test_install_from_stack_suffix_stripped(self):
        action = "pays 3 [Credits] to install Carmen from the Stack."
        assert simplify_action(action) == "install Carmen"


def _triples(texts):
    """Build log_parser input lines (actor/timestamp/action) from action texts.

    Actor is inferred from the leading token of the text, mirroring
    json_replay_to_nan.extract_log.
    """
    import re as _re
    lines = []
    for text in texts:
        m = _re.match(r"([\w.-]+) ", text)
        actor = m.group(1) if m else "System"
        lines.append(actor)
        lines.append("[00:00:00]")
        lines.append(text)
    return lines


class TestCreditTrackingDsl:
    """generate_dsl emits credit checkpoints in headers and per-action totals."""

    def _nan(self, texts):
        return generate_dsl(parse_log_lines(_triples(texts)))

    def test_header_credit_checkpoint(self):
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] to use Corp Basic Action Card to gain 1 [Credits].",
        ])
        assert nan.startswith("Corp T1 [0-0] {C5 R5}:")
        assert "credit →C6" in nan

    def test_annotation_on_play_cost(self):
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-runner spends [Click] and pays 5 [Credits] to play Sure Gamble.",
            "ai-runner uses Sure Gamble to gain 9 [Credits].",
        ])
        # The dropped "uses Sure Gamble to gain 9" line folds its delta into
        # the rendered play action: 5 - 5 + 9 = 9.
        assert "Sure Gamble →R9" in nan
        assert "→R0" not in nan

    def test_checkpoint_resyncs_running_total(self):
        # Deliberately inconsistent: log says turn 2 starts with 20.
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] to use Corp Basic Action Card to gain 1 [Credits].",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-corp started their turn 2 with 20 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] to use Corp Basic Action Card to gain 1 [Credits].",
        ])
        rows = nan.splitlines()
        assert rows[-1].startswith("Corp T2 [0-0] {C20 R5}:")
        assert "credit →C21" in rows[-1]

    def test_standalone_token_for_dropped_credit_line(self):
        # Drip income at turn start: the "uses X to gain" line simplifies to
        # None and nothing rendered precedes it, so a bare total is emitted.
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-runner started their turn 1 with 6 [Credit] and 5 cards in their Grip.",
            "ai-runner uses Smartware Distributor to gain 1 [Credits].",
            "ai-runner spends [Click] to make a run on HQ.",
        ])
        row = nan.splitlines()[-1]
        assert "{C5 R6}" in row
        assert "→R7; run HQ" in row

    def test_zero_delta_not_annotated(self):
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-runner spends [Click] and pays 0 [Credits] to play Jailbreak.",
        ])
        assert "Jailbreak" in nan
        assert "Jailbreak →" not in nan

    def test_zero_cost_play_still_folds_its_payoff(self):
        # "pays 0 to play X" then "uses X to gain 3": the play is a rendered
        # credit-involving cause, so the payoff attaches to it.
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] and pays 0 [Credits] to play Predictive Planogram.",
            "ai-corp uses Predictive Planogram to gain 3 [Credits].",
        ])
        assert "Predictive Planogram →C8" in nan
        assert "; →C8" not in nan

    def test_forced_gain_credits_the_beneficiary(self):
        # ai-corp is the actor but the Runner receives the credits.
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-runner spends [Click] and pays 2 [Credits] to play Wildcat Strike.",
            "ai-corp uses Wildcat Strike to force the Runner to gain 6 [Credits].",
        ])
        row = nan.splitlines()[-1]
        # -2 to play, +6 forced gain folded in: net →R9, charged to the Runner.
        assert "Wildcat Strike →R9" in row
        assert "→C" not in row.split(":", 1)[1]

    def test_opponent_action_annotated_with_own_side(self):
        # Corp rezzes ice during the Runner's turn: annotation is Corp's total.
        nan = self._nan([
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] to use Corp Basic Action Card to gain 1 [Credits].",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-runner spends [Click] to make a run on HQ.",
            "ai-corp pays 1 [Credits] to rez Tithe protecting HQ at position 0.",
        ])
        row = nan.splitlines()[-1]
        assert "rez Tithe@0 HQ →C5" in row


class TestCreditAnnotationsRoundTrip:
    """Parser and renderer accept credit-annotated NAN."""

    def test_parse_header_with_credits(self):
        parser = NANParser()
        result = parser.parse_line("Corp T3 [0-2] {C14 R10}: credit →C15")
        assert result["player"] == "Corp"
        assert result["score"]["corp"] == 0
        assert result["credits"] == {"corp": 14, "runner": 10}

    def test_parse_action_strips_annotation(self):
        parser = NANParser()
        action = parser.parse_action("rez Palisade@0 S1 →C4")
        assert action["verb"] == "rez"
        assert action["target"] == "Palisade@0 S1"
        assert action["credits_after"] == ("C", 4)

    def test_parse_standalone_credit_token(self):
        parser = NANParser()
        action = parser.parse_action("→R7")
        assert action["verb"] is None
        assert action["credits_after"] == ("R", 7)

    def test_renderer_ignores_annotations(self):
        renderer = NANRenderer()
        renderer.parse_nan(
            "Corp T1 [0-0] {C5 R5}: ice S1; credit →C6\n"
            "Runner T1 [0-0] {C6 R5}: →R6; run S1; rez Palisade@0 S1 →C3"
        )
        assert renderer.servers["S1"][0]["name"] == "Palisade"
        assert renderer.servers["S1"][0]["rezzed"] == True


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
