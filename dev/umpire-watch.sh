#!/bin/bash
# umpire-watch.sh — the umpire's Monitor target for seat escalations (issue #20).
#
# Streams NEW seat pings from the shared mailbox, one line per ping. Wired for
# Claude Code's Monitor tool but works standalone.
#
#   Monitor({command:"./dev/umpire-watch.sh", description:"seat escalations", persistent:true})
#
# Only PING lines surface (the umpire wakes on seat pings, not on its own replies).
# `tail -F -n0` means only pings from NOW forward fire — pre-existing lines from a
# prior game are ignored, so no need to clear the mailbox between games.
#
# On a ping: read BOTH sides' PUBLIC status (game-over-status / peer-status / prompt),
# decide wedged-or-not, then:  ./dev/umpire-reply <side> "<harness-state answer>"
# HARD CONSTRAINT: reply about harness/tooling state ONLY. You can see both hands —
# never leak hidden info, never give strategy advice. Refuse strategy questions.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAILBOX_DIR="$SCRIPT_DIR/.umpire"
MAILBOX="$MAILBOX_DIR/mailbox.log"

mkdir -p "$MAILBOX_DIR"
touch "$MAILBOX"

echo "👁️  Umpire watching seat escalations → $MAILBOX"
echo "    reply with: ./dev/umpire-reply <corp|runner> \"<harness-state answer>\""
echo "    HARD RULE: harness state only — never leak hidden info or give strategy;"
echo "    keep recoveries command-only and card-name-free (the mailbox is opponent-readable)."
echo "─────────────────────────────────────────────────────────────────────────"

# tail -F -n0 only shows lines from NOW forward, so a ping fired BEFORE this watcher
# (re)started would be invisible. Surface any still-UNANSWERED ping first: a per-side
# PING with no later REPLY. (Cheap catch-up; then stream live.)
awk '
  /  PING   / { split($0,a,"PING"); s=a[2]; sub(/^ +/,"",s); split(s,b," "); ping[b[1]]=$0 }
  /  REPLY  / { split($0,a,"REPLY"); s=a[2]; sub(/^ +/,"",s); split(s,b," "); delete ping[b[1]] }
  END { for (k in ping) print "⧗ UNANSWERED (from before watch started): " ping[k] }
' "$MAILBOX" 2>/dev/null

# Then stream new PING lines live (grep --line-buffered so the Monitor wakes at once).
tail -F -n0 "$MAILBOX" 2>/dev/null | grep --line-buffered -F '  PING   '
