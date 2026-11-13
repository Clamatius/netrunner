# Feedback for the `review` tool — from one unattended night on netrunner (2026-11-12/13)

Written by the orchestrator (Claude Fable 5.2) because Michael asked for it: "Generate feedback for
the review tool if you use it." Seven review runs, all through `review` / `askmodel`:

| Run | Rounds | Seats | Confirmed | What it was |
|---|---|---|---|---|
| run-chain | 2 design + 2 code | opus, sol, astra | 1 CRITICAL, 14 MAJOR, 13 MINOR | run-automation priority (the only run with a plan) |
| hosted (#170/#171) | 2 code + advisor | opus, astra | 5 MAJOR, 6 MINOR | hosted programs |
| number prompts (#325) | 2 code | opus, astra | 1 CRITICAL, 4 MAJOR, 3 MINOR | trace / number / card-name prompts |
| false messages (#326) | 1 code + advisor | opus, astra | 2 MAJOR, 3 MINOR | `--help` hazard + six messages |
| ability zones (#329) | 1 code + advisor | opus, astra | 2 MAJOR, 4 MINOR | scored / identity abilities |
| babysit, lobby format, pending prompt | advisor only | sol, sol, opus | 2 + 3 + 3 should-fix | small changes |

Nothing was rejected in any round: every finding was confirmed against the code (some downgraded,
some documented rather than fixed). Small n; these are cases, not statistics.

## What worked, with the case

1. **Plan review before building.** Design r1 on the run-chain plan (opus + sol, about ten minutes)
   found a CRITICAL and six MAJORs before a line was written: my v1 made the whole run strategy
   run-scoped, which would have made a one-shot `--force` bypass every guard for the rest of the run.
   I had found one of the seven myself. This is the strongest single case from the night.
2. **Step 2, "measure the case first."** The run-chain analysis was six claims from reading code. Put
   on the real engine before the first round, two were wrong (one false, one true only as a
   consequence of another). The plan would have been built on them.
3. **"Fixes are sized to their finding; prefer removal."** Design r2 landed four of its five MAJORs on
   the two mechanisms I had ADDED in v2 to answer r1. Both seats independently said delete. v3 was v2
   minus both. The same shape recurred in code rounds three times (hosted r2: my own over-general fix
   instruction broke Ika; #325: an engine edit; #329: a replaced resolver). The procedure says this
   already; it could say it for DESIGN rounds explicitly: *a v2 that adds a mechanism per r1 MAJOR
   will fail r2.*
4. **The advisor pass after the round cap.** One seat, about eight minutes, six uses, real defects in
   five: a misleading pause line (hosted); a token logged unredacted on a CRLF file (babysit); a dead
   lobby from a typo'd matchup and a silently dropped card (lobby format); a scored copy shadowed by
   an installed one (#329); four correct successes turned into "pending" (pending prompt). The
   pending-prompt one would have shipped a regression in every game. For changes too small for a
   panel it was the right tool, and I leaned on it harder than the procedure suggests.
5. **Asking the panel a pointed question in `--context`.** "Is the `--help` fix proven for every
   command or a sample?" and "should this engine edit exist?" both got direct, checked answers. When
   the two seats disagreed on the engine edit (one keep, one drop), the disagreement itself was the
   useful output: it sent me to the source.

## Friction, most costly first

1. **Seats cannot run a JVM test suite without rediscovering the sandbox.** In every code round at
   least one seat reported that `lein` failed until it set `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMPDIR`
   and shimmed `mktemp`; some never got there and said "I ran no tests". `/bin/ps` and loopback
   sockets are denied too, so a shell test that opens a local socket reads as four failures in a
   seat's hands. Suggestion: `askmodel` exports a writable `TMPDIR` and `JAVA_TOOL_OPTIONS` for seats,
   or the pack carries a per-repo "how to run the tests in this sandbox" line the author can supply.
2. **The judge harness cannot run a seat's Clojure repro at all.** Every repro script a seat attached
   died in the repro sandbox (`mktemp` for lein's trampoline; `~/.m2` unreadable), so every ruling on
   a scripted finding was `not_reproduced` for a reason that had nothing to do with the finding. For
   a JVM repo the repro channel is closed. Either grant `~/.m2` read plus a temp dir, or have the pack
   say "repro unsupported here" so seats spend the effort on a failing test instead.
3. **`review judge --rulings` rejects a ruling for a missing `repro` field only after the fix brief
   has gone out.** I launched the fixer in the same command and the rulings bounced; nothing was lost,
   but the order of operations in step 6 could say "submit rulings, read the result, then brief".
4. **The omission question repeats the same out-of-scope gap every round.** My ask file carried the
   whole project ask (required: verbatim), so every seat in every round answered "what is
   unaddressed?" with the project-level bar and HITL. I invented a "What THIS change is for" section
   to narrow it; the seats mostly ignored the boundary. A first-class way to mark "context, not this
   change" would make O-answers worth reading again.
5. **Seats cannot read issues.** `gh issue view` is refused (no network). A pack flag to inline named
   issues would have saved several "I could not read #297" notes.
6. **`review judge RUN/rN --findings` errors until a bare `review judge RUN/rN` has run.** Step 6
   presents `--findings` as the way in. Either build the judge pack on demand or say so.
7. **`review judge <missing dir>` dies with a Python traceback** (FileNotFoundError on pack.json,
   review:2791) rather than a one-line error.
8. **`review panel` prints nothing until both seats finish.** When the harness backgrounds it, "still
   running" and "died at launch" look the same for ten minutes. One flushed line per seat at start
   (`seat X started, pid N`) would do.
9. **A codex seat with `--write` in a git WORKTREE cannot commit** (the worktree's git dir is outside
   its writable root). Not a review-tool defect, but every delegated fix came back uncommitted and
   had to be committed by the orchestrator; worth a line in the askmodel docs.

## One thing I got wrong that the tool could not have caught

Two delegated fixes "repaired" a case the reporter never saw, with tests that stubbed a state the real
function cannot return (false-message item D; and, in a different form, the first #329 pass). Seats
caught both by reading the stub against the real function. The lesson is on the author's side: for a
batch, step 2 applies to EACH item, and the brief should quote the reporter's own words per item.
