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

## Each of your turns

You have 3 clicks. A rough loop (use `snapshot` to pull status + prompt + board +
hand + recent log + cursor in ONE call):

1. **See state:** `./dev/send_command corp snapshot`
   **Don't guess what a card does — look it up:** `card-text "<name>"` gives any
   card's type/cost/text; `abilities "<name>"` lists an installed card's numbered
   abilities. If a command name doesn't exist, the client suggests the right one.
2. **Decide & act:** draw, gain credits, install ICE on a server, install an
   agenda/asset into a remote. The server arg is one of: `new` (creates a fresh
   remote — use the bare token `new`, NOT `"new remote"`), an existing
   `remote1`/`remote2`/…, or a central `HQ`/`R&D`/`Archives` (ICE only). Advance
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

When `wait` returns because a **run started**, participate:

```
./dev/send_command corp monitor-run
```

`monitor-run` auto-passes the boring priority windows and **pauses, returning to
you, when you have a real decision** — typically a **rez** opportunity as the
Runner approaches one of your ICE, or an unbroken-subroutine **fire** decision.
Read it:

```
./dev/send_command corp prompt
./dev/send_command corp board        # which ICE is approached (you know its identity)
```

Then commit your decision by re-entering the monitor with a strategy flag:

- **Rez the approached ICE:** `./dev/send_command corp monitor-run --rez "<ICE name>"`
- **Decline to rez (this run):** `./dev/send_command corp monitor-run --no-rez`
- **Low-stakes run, just let it play out:** `./dev/send_command corp monitor-run --fire-if-asked`
  (auto-fires unbroken subs, auto-continues, wakes you only for rez decisions)

`monitor-run` returns again at the next decision or when the run ends (it prints
`run ended` / `no active run`). When the run is over, go back to the `wait` loop
above. Several runs can happen in one Runner turn — keep looping.

**Rez judgement:** rez when the ICE actually stops or taxes a run you care about
(protecting an agenda/centrals you can't afford to lose), not reflexively — rezzing
burns credits and reveals the card. A cheap ICE on a server with nothing worth
stealing is often a decline.

### ⚠️ If the game won't advance after you end your turn — re-send end-turn

Known rough edge: ending your turn right after a **last-click action whose
resolution is still settling** can get **rolled back** on a resync. Symptom:
`smart-end-turn` reports success and `game-over-status` shows
`AWAITING-START next-player=runner`, **but the Runner never starts**. Do NOT
conclude "Runner stalled" from this alone. **Recovery:** if you've ended and the
Runner hasn't started after a `wait` (~10s+), simply re-run
`./dev/send_command corp smart-end-turn`. Retry 2–3 times, ~3s apart, before
deciding it's a genuine Runner-side stall.

## Knowing when to stop

After each of your turns (and after a Runner turn where they may have scored),
check:

```
./dev/send_command corp game-over-status
```

- `GAME-OVER winner=… turn=…` → done. **Stop** and give a short report: who won,
  how (agenda/flatline/deck), final score, turn count, and a couple of sentences
  on how your game went + any moment you'd replay.
- `IN-PROGRESS …` / `AWAITING-START …` → keep playing / keep defending.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Runner seat or its REPL.
- If you get genuinely stuck on tooling (a command errors repeatedly, a prompt
  won't clear, a run won't resolve), say so plainly in your report rather than
  thrashing — that's a harness bug worth surfacing, not your fault.
