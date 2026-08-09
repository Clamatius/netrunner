# Seat brief — you are the CORP

Paste this whole file as your first message into a **fresh** interactive
`claude` session (run from the netrunner repo root). A separate process has set
up the game: you take the **Corp** seat; the **Runner** is a *separate, isolated
model* in its own session (not a bot). Play a real, competitive game and try to
**win** — and expect your opponent to take its time thinking, just like you.

## The one hard rule: isolation contract

You are ONE player at the table. Your only window into the game is the **Corp**
client (REPL on port 7890). This is deliberate — it is what makes this a fair
test, so honour it strictly:

- **Only ever run `./dev/send_command corp …`.** Never run a `runner` command.
- **Never try to inspect the Runner's hidden information** — their grip, stack
  order, or facedown/hosted cards. You learn a card only when the game reveals it
  (they install it face-up, play it, or you see it on a run).
- Your **own** R&D order is fog-of-war too — use `:deck-count`, don't assume the
  top card.
- Do not read the Runner's source/strategy files to predict it. Play what's in
  front of you.

If you ever feel the urge to "just peek" — don't. That's the whole point.

## Orient (do this first, once)

- `CLAUDE.md` is already in your context. Skim it.
- Strategy heuristics: read **`dev/instructions/corp_play_structure.md`** if it
  exists; else `dev/corp-level1.md`. Core ideas: keep a credit cushion, never
  install an agenda naked (protect scoring remotes behind ICE), rez ICE only when
  the run actually threatens something worth the credits.
- Full command list: `./dev/send_command help --full`.
- System Gateway tutorial matchup. You win at **7 agenda points** (score agendas
  from a protected remote) or by flatlining / decking the Runner.

## Turn 0 — mulligan (you go FIRST)

Corp mulligans before the Runner. Make your call, then the Runner will mulligan:

```
./dev/send_command corp status      # see the game / your opening hand
./dev/send_command corp hand
./dev/send_command corp keep-hand    # or:  ./dev/send_command corp mulligan
```

**Mulligan heuristic — ICE first, economy second.** The single most important
property of an opening hand is **ICE**. A hand with **zero ICE should almost
always be mulliganed**, even if it is rich in economy: you cannot protect your
servers, and you have to top-deck several ICE *before* agendas start arriving or
you simply lose (a naked server = free agenda access for the Runner). Do **not**
talk yourself into keeping a 0-ICE hand on "money is threat" / "low agenda-flood"
reasoning — a great economy you can't defend behind still loses. Rough guide:
- **0 ICE → mulligan** (unless the hand is otherwise so degenerate that keeping is
  a specific known line — rare; default to mulligan).
- **1 ICE → lean mulligan** unless the rest is strong and you have draw to find more.
- **2+ ICE + some economy → keep.**
Agendas in the opener are fine to keep (hold them in HQ, install behind ICE later);
it's the *absence of ICE*, not the presence of an agenda, that makes a hand a throw.

## Each of your turns

You have 3 clicks. A rough loop (use `snapshot` to pull status + prompt + board +
hand + recent log + cursor in ONE call):

0. **Start your turn — it does NOT auto-start.** After you keep your hand (your
   first turn) and whenever a `wait` wakes you for your turn, you will show
   `0 clicks / awaiting-start` until you run `./dev/send_command corp start-turn`.
   Do this every turn before acting. A `wait` at a turn boundary wakes with reason
   `my-turn-start` (and prints a 👉 start-turn reminder) — that means start your
   turn, it is NOT a stall. Reason `my-turn` (with clicks) means act now.
   Reason `my-run-window` means **a run is stopped on YOU**: you owe the
   `continue` at the current run window. It fires on every run. At an ICE
   *approach* that continue is also your rez window (`continue --rez <ice>` to
   rez first, `--no-rez` to decline); at movement there is nothing to rez, just
   pass. Another `wait` cannot advance it, and an empty game log under it means
   the Runner is already waiting on you — do not read it as nothing-happened.
1. **See state:** `./dev/send_command corp snapshot`
   **Don't guess what a card does — look it up:** `card-text "<name>"` gives any
   card's type/cost/text; `abilities "<name>"` lists an installed card's numbered
   abilities. If a command name doesn't exist, the client suggests the right one.
2. **Decide & act:** draw, gain credits (the verb is `take-credit`), install
   ICE on a server, install an
   agenda/asset into a remote. The server arg is one of: `new` (creates a fresh
   remote — `"new remote"` / `"new server"` also work, matching the game's own
   label), an existing `remote1`/`remote2`/…, or a central `HQ`/`R&D`/`Archives`
   (ICE only). Advance
   an agenda, score it, or rez. Examples:
   `./dev/send_command corp install "Funhouse" "R&D"`
   `./dev/send_command corp install "Offworld Office" new`
   `./dev/send_command corp advance "Offworld Office"`
   `./dev/send_command corp score "Offworld Office"`
3. End cleanly when out of clicks: `./dev/send_command corp smart-end-turn`
   (it handles discard-to-hand-size).

**Scoring tip:** an agenda needs advancement tokens equal to its requirement
(often over two turns) and must survive in a remote the Runner can't break into.
Install behind ICE, advance, and score before they get in.

## Defending the Runner's turn — the part that's unique to Corp

After you end your turn, you are NOT idle: you must defend against runs (decide
whether to rez ICE, and whether to fire unbroken subroutines). Loop like this
until it's your turn again:

```
C=$(./dev/send_command corp get-cursor)        # capture cursor first
./dev/send_command corp wait --since "$C"       # blocks until something relevant
```

As soon as you end your turn, **take your post** — do not wait for a run to start
first:

```
./dev/send_command corp monitor-run --persistent
```

`monitor-run` auto-passes the boring priority windows and **pauses, returning to
you, when you have a real decision** — typically a **rez** opportunity as the
Runner approaches one of your ICE, or an unbroken-subroutine **fire** decision.

**Always use `--persistent`, and issue it immediately after ending your turn.**
`--persistent` now **parks**: with no run yet active it waits at the post, and it
**owns the Runner's whole turn** — across every run they make — returning only for
a real rez/fire decision, when the Runner's turn ends (`my-turn`), or on game
over. You no longer re-arm it per run.

This matters more than it looks. A rez window is a *both-must-pass* window: if you
are not at your post when the Runner arrives at your ICE, the run **stalls with
nobody home**, and the Runner — who cannot act for you — eventually gives up. In
marquee `d6962df4` that happened on nearly every run (5 jack-outs, 1 rez in the
whole game) because the Corp's monitor kept exiting with "no active run" between
runs. Parking is what keeps you present.

**Pre-commit your rez policy** (`--rez "<ICE>"` / `--no-rez`) whenever you can: a
pre-committed monitor answers the window *instantly*, whereas a window that has to
wait for you to think is a window the Runner spends minutes staring at. Read the
decision:

```
./dev/send_command corp prompt
./dev/send_command corp board        # which ICE is approached (you know its identity)
```

Then commit your decision by re-entering the monitor with a strategy flag
(keep `--persistent` so it keeps owning the rest of the run):

- **Rez the approached ICE:** `./dev/send_command corp monitor-run --persistent --rez "<ICE name>"`
- **Decline to rez (this run):** `./dev/send_command corp monitor-run --persistent --no-rez`
- **Low-stakes run, just let it play out:** `./dev/send_command corp monitor-run --persistent --fire-if-asked`
  (auto-fires unbroken subs, auto-continues, wakes you only for rez decisions)

**Non-rez trigger decisions.** Some agendas fire a Corp ability when the Runner
*steals* them (or when you score them) — e.g. Send a Message ("you may rez a
piece of ice, ignoring all costs"). The monitor returns with `🛑 You have a
pending decision to resolve (agenda trigger / choice)`. This is NOT a rez window,
so don't answer it with `--rez`: read it with `./dev/send_command corp prompt`,
then resolve it directly — `choose-value "Done"` to decline (e.g. nothing worth a
free rez / all your ICE already rezzed), or `choose-card "<ICE>"` to pick a
target. Then, if the run is still live, re-enter `monitor-run --persistent`.

`monitor-run` returns at the next real decision or when the run ends (it prints
`run ended` / `no active run`). When the run is over, go back to the `wait` loop
above. Several runs can happen in one Runner turn — keep looping.

**If `monitor-run --persistent` returns because of a TIMEOUT** (it says so) **and
a run is still active, just re-issue `monitor-run --persistent`.** A slow opponent
that thinks for many minutes mid-run can outlast one monitor window — a timeout
return is normal pacing, NOT a stall and NOT a decision. Re-entering simply
re-arms the defender loop where it left off. (Only treat repeated timeouts with
*zero* board movement across several re-issues as a possible genuine wedge.)

To tell a slow-but-alive opponent from a dead one, don't guess — a `wait` /
`monitor-run` return ends with a peer-liveness line, and you can check anytime
with `./dev/send_command corp peer-status`. `opponent (runner): active Ns ago`
→ still thinking, keep re-issuing. `opponent (runner): SILENT … likely
disconnected` → their process has died; confirm `game-over-status` and, if still
IN-PROGRESS, report the dead peer and stop rather than looping forever.

**Rez judgement:** rez when the ICE actually stops or taxes a run you care about
(protecting an agenda/centrals you can't afford to lose), not reflexively — rezzing
burns credits and reveals the card. A cheap ICE on a server with nothing worth
stealing is often a decline.

### ⛔ NEVER re-send end-turn. If the game won't advance, call the umpire.

**Do not re-send `end-turn`/`smart-end-turn` to unstick anything, ever.** An
end-turn that lands when it isn't your turn ends your OPPONENT's turn and is
logged under your name. No game has ever been recovered from it. This destroyed
game 02995207 at turn 8 — the seat was following earlier advice in this very
document to "retry 2–3 times", which is why that advice is gone.

The client now refuses off-turn end-turns outright (`⛔ Refusing end-turn: it is
not your turn`). If you see that message, you are about to break the game:
**stop and escalate.** Do not look for a way around it.

Symptom you may still hit: `smart-end-turn` reports success and
`game-over-status` shows `AWAITING-START next-player=runner`, **but the Runner
never starts**. Do NOT conclude "Runner stalled", and do NOT re-send. Wait once,
then escalate to the umpire.

## If you suspect a wedge — raise your hand to the umpire (don't spin)

You cannot tell "slow opponent" from "the harness is wedged" from your own seat —
it is genuinely undecidable from one side. So don't silently burn 10 minutes
re-sending a command that isn't advancing. There is an **umpire**: a supervisor who
can legitimately see BOTH seats' public status, there for exactly this.

**DEFAULT POSTURE: if something weird happened, or the game might be broken, or
the opponent might be stuck — ESCALATE. Do not try to fix it by re-sending.**
Re-sending is how a recoverable oddity becomes an unrecoverable one. The umpire
is cheap; a broken game costs the whole match.

**Escalate when ANY of these holds:**
- anything looks wrong, out of order, or contradictory — including a command whose
  output disagrees with `game-over-status`, `prompt`, or the log; or
- you've issued the same advancing command (`start-turn`, `continue`,
  `monitor-run --persistent`) **twice** with **no state change** — do not go to a
  third; or
- you've waited **> ~5 min** with no progress AND `peer-status` says the opponent is
  still **alive** (so it's not a dead peer — smells like a boundary wedge); or
- a run window won't advance and re-issuing `monitor-run` hasn't moved it.

**How — ping, then poll for the reply (bounded), then follow it:**
```
./dev/umpire-ping corp "what I tried + what I see"
for i in $(seq 1 20); do ./dev/umpire-check corp && break; sleep 15; done
```
If ~5 min pass with **no** reply (umpire may be away), re-ping **once** with
`--wake` (this pages the human): `./dev/umpire-ping corp --wake "still stuck, no umpire reply"`.
Then fall back to the **safe default: keep waiting at your post — do nothing
destructive.** Never end the game, never abandon your defender loop to "unstick" it.

**HARD RULE — harness state ONLY, and assume the opponent can read your ping.** Say
only what command you ran, how many times, and the **shape** of what you see
(clicks, phase, prompt *type*, `peer-status`) — e.g. "select prompt, 3 choices,
unchanged 5 min." **NEVER** state the prompt's *contents*, your hand, your R&D, an
unrezzed card, or your plan. The mailbox is shared, so a contents leak reaches the
Runner and blows the fog-of-war premise. Ask "am I wedged?", never "what should I
do?" — the umpire will refuse any strategy question. Do **not** read the other
seat's files. Escalating when stuck is the correct move, not a failure.

## Knowing when to stop

After each of your turns (and after a Runner turn where they may have scored),
check:

```
./dev/send_command corp game-over-status
```

- `GAME-OVER winner=… turn=…` → done. **Stop** and give a short report: who won,
  how (agenda/flatline/deck), final score, turn count, and a couple of sentences
  on how your game went + any moment you'd replay.
- `GAME-GONE turn=…` → the server closed the lobby without a result (game
  abandoned/torn down). Also a **stop** condition: there is no game left to
  play. Report what you saw and stand down — do not keep issuing commands.
- `IN-PROGRESS …` / `AWAITING-START …` → keep playing / keep defending.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Runner seat or its REPL.
- If you get genuinely stuck on tooling (a command errors repeatedly, a prompt
  won't clear, a run won't resolve), say so plainly in your report rather than
  thrashing — that's a harness bug worth surfacing, not your fault.
