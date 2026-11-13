# Playing a model yourself (HITL)

You sit at the web UI; `dev/match` seats and babysits the model. Verified
2026-11-13 with a PROXY human (the spare AI client driven by the heuristic bot),
not a browser: `logs/match/hitl-proof-1` (you=Corp, default decks, Luna won
9-4) and `hitl-proof-2` (you=Runner, the Constructed lists below, Luna won 8-0).
Anything marked UNVERIFIED was not exercised.

## Start a game (from the main checkout, stack up: 1042/7888/7889/7890)

    # you are the Corp, Luna is the Runner, default System Gateway decks
    dev/match --corp human --runner luna --effort medium
    # you are the Runner against Opus, Constructed decks (both or neither)
    dev/match --corp opus --runner human \
      --corp-deck dev/decks/sg-weyland-quick-and-dirty.edn \
      --runner-deck dev/decks/sg-criminal-stolen-goods.edn

The model is any askmodel seat (`askmodel --list`). `--effort` defaults to
high; `--tag NAME` names the run dir. Foreground by default; `nohup dev/match
... > /dev/null 2>&1 &` survives the terminal (`tail -f logs/match/<tag>/launcher.log`).
It bounces the AI clients, has the model side create lobby `dev/match <tag>`,
prints a HUMAN SEAT block, waits up to 30 min (`MATCH_HUMAN_JOIN_SECS`) for you
to sit with a deck, starts the game, and launches only the model seat + babysitter.

## Join in the browser

1. http://localhost:1042, log in as **Clamatius** (your account on this dev
   server's mongo; the only other accounts are ai-corp / ai-runner, which the
   AI clients use: never log in as those). A new account: the site's sign-up
   form, no email check on this server (read in `src/clj/web/auth.clj`;
   UNVERIFIED in a browser).
2. Play, join `dev/match <tag>`; you get the side the launcher printed.
3. Default decks: the lobby assigns them. Constructed: the deck must be in
   YOUR account first. The HUMAN SEAT block prints the exact line, e.g.
   `dev/seed-decks.sh Clamatius /abs/path/sg-criminal-stolen-goods.edn`
   (prints legality, then the deck id; re-seeding is idempotent). Then pick it
   in the lobby. Verified with username ai-runner; UNVERIFIED for Clamatius.
4. The model side starts the game as soon as both seats have decks.

## Notifications (ntfy, on by default; `--no-notify` turns them off)

Each poll (30 s) the launcher asks the model's client who owes the next
decision. One ntfy per hand-over to you, then none until the decision has
gone back to the model (i.e. you acted):

- `Netrunner: keep or mulligan your opening hand (Runner, turn 0).`
- `Netrunner: your turn (Corp, turn 5).` (turn = round number)
- `Netrunner: your call in the run (Corp, turn 17).` (a run window you owe)
- `Netrunner: game over. Runner wins (Agenda), 4-9, turn 19.`
- `Netrunner: the game stopped without a result (<outcome>): <why>`

Every message ends `[dev/match <tag>]`. They go to dev/umpire-ping's topic
(`https://ntfy.sh/ai-netrunner-qualified-animal`); `MATCH_NTFY_URL` overrides.

**`logs/NO-WAKE` silences them** (same switch as `umpire-ping --wake`): it
exists now, so **delete it to be notified**. While it exists each would-be
send is logged as `notification NOT sent (logs/NO-WAKE exists)` and the HUMAN
SEAT block says SILENCED. `MATCH_NTFY_IGNORE_NO_WAKE=1` lifts it only for a
loopback URL (that is how the proofs fired into a local stub).

## Waking the model, stopping

- Model looks asleep: type anything containing `ping` in game chat.
- Stop: Ctrl-C the launcher (or `kill -TERM <pid>`): it stops the seat and
  babysitter it started, writes result.json (`interrupted`), leaves the lobby
  open; `dev/save-replay.sh <gameid>` (or the next dev/match) saves the replay.
  Stub-tested; UNVERIFIED live.

## Where things land

- `logs/match/<tag>/`: launcher.log, result.json (your side is recorded as
  `{"seat": "human", "backend": "web"}`), brief, seat script, seat output.
- `dev/replays/<gameid>.json` (browser: http://localhost:1042/replay/<gameid>)
- `dev/replays/<gameid8>-<side>-<seat>-report.md`: the model's own report.

## Known limits

- Stall detection cannot see you: a human side always counts as alive, so a
  slow human never ends the game as "stalled" (a 3m49s pause on turn 5 of
  hitl-proof-1 sent one notification and nothing else). Only `--max-hours`
  (default 6) bounds a game you abandon.
- A decision you take within one poll may never be noticed, so not every turn
  pages you; each run window you owe can page once. No mulligan page when you
  are the Corp (the launcher is still in its keep gate; you have just joined).
- The model's `peer-status` cannot see you; its brief says to ignore that.
- Issue #327: the web UI's counter selector offers only 0 on cards like
  Sealed Vault (not in System Gateway).
- UNVERIFIED with a real browser: login, lobby join, deck pick, play, and
  chat `ping` from the web client (the proxy joined via send_command).
