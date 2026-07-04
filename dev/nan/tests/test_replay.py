"""Tests for the JSON-replay -> NAN converter (json_replay_to_nan.py).

These pin the three bugs that made the converter emit nothing on real
jinteki replays:
  1. Clojure 'differ' sequence diffs (["+", v, "+", v, ...]) were applied as a
     literal list replacement, so the append-only `log` vector kept only the
     last diff's payload.
  2. extract_log dropped the actor for "__system__" entries (which is every
     game event in a replay), misaligning log_parser's actor/ts/action triples.
  3. detect_side_from_action used leading-space guards that never matched action
     text beginning with the verb (covered in test_nan.py).
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from json_replay_to_nan import patch, extract_log
from log_parser import parse_log_lines, generate_dsl


def _entry(text):
    return {"user": "__system__", "text": text, "timestamp": "2026-01-01T00:00:00Z"}


class TestSeqDiff:
    # Imported lazily so the behavioral tests below still collect (and fail
    # cleanly) against a build that predates this helper.
    def test_append_only(self):
        from json_replay_to_nan import _apply_seq_changes
        assert _apply_seq_changes(["a", "b"], ["+", "c", "+", "d"]) == ["a", "b", "c", "d"]

    def test_index_replace(self):
        from json_replay_to_nan import _apply_seq_changes
        # marker 1 => replace at index 1
        assert _apply_seq_changes(["a", "b", "c"], [1, "B"]) == ["a", "B", "c"]

    def test_mixed_index_and_append(self):
        from json_replay_to_nan import _apply_seq_changes
        assert _apply_seq_changes(["a", "b"], [0, "A", "+", "c"]) == ["A", "b", "c"]


class TestPatchGrowsLog:
    def test_log_accumulates_across_diffs(self):
        # Mirrors a real replay: history[0] is full state, the rest are
        # [changes, removals] differ diffs that only append to `log`.
        state = {"log": [_entry("ai-corp has created the game.")]}
        diffs = [
            [{"log": ["+", _entry("ai-corp keeps their hand.")]}, None],
            [{"log": ["+", _entry("ai-runner keeps their hand.")]}, None],
            [{"log": ["+", _entry("ai-corp started their turn 1 with 5 [Credit].")]}, None],
        ]
        for d in diffs:
            state = patch(state, d)
        # Before the fix this was length 1 (only the last diff's payload).
        assert len(state["log"]) == 4
        assert state["log"][-1]["text"].startswith("ai-corp started their turn 1")


class TestExtractLog:
    def test_actor_from_system_text_prefix(self):
        log = [_entry("ai-corp scores Offworld Office and gains 2 agenda points.")]
        lines = extract_log({"log": log})
        assert lines[0] == "ai-corp"        # actor recovered from text prefix
        assert lines[1].startswith("[")     # synthetic timestamp
        assert "scores Offworld Office" in lines[2]

    def test_skips_empty_text(self):
        assert extract_log({"log": [_entry("")]}) == []


class TestEndToEnd:
    def test_replay_log_to_nan(self):
        """Full path: differ-patched log -> events -> NAN with score checkpoints."""
        state = {"log": [_entry("ai-corp has created the game.")]}
        events_text = [
            "ai-corp started their turn 1 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp spends [Click] to use Corp Basic Action Card to gain 1 [Credits].",
            "ai-corp spends [Click] to install ice protecting Server 1 (new remote).",
            "ai-runner started their turn 1 with 5 [Credit] and 5 cards in their Grip.",
            "ai-runner spends [Click] to make a run on R&D.",
            "ai-runner steals Hostile Takeover and gains 1 agenda point.",
            "ai-corp started their turn 2 with 5 [Credit] and 5 cards in HQ.",
            "ai-corp scores Offworld Office and gains 2 agenda points.",
        ]
        for t in events_text:
            state = patch(state, [{"log": ["+", _entry(t)]}, None])

        lines = extract_log(state)
        nan = generate_dsl(parse_log_lines(lines))
        rows = nan.splitlines()

        assert rows[0].startswith("Corp T1 [0-0] {C5 R5}:")
        assert "ice S1" in rows[0]
        # Corp clicked for a credit on T1, reflected in the Runner T1 header.
        assert rows[1].startswith("Runner T1 [0-0] {C6 R5}:")
        assert "run R&D" in rows[1] and "steal Hostile Takeover" in rows[1]
        # Runner stole 1 point on T1, so Corp's T2 checkpoint reflects 0-1.
        assert rows[2].startswith("Corp T2 [0-1] {C5 R5}:")
        assert "score Offworld Office" in rows[2]
