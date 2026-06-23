# launchd: Netrunner game server daemon

Keeps the jinteki game server (web server on **1042**, hosted by a headless lein
REPL on **7888**) running across reboots and crashes, so you don't have to
hand-start it. Mongo (27017) is assumed to already be a service
(`brew services` installs `homebrew.mxcl.mongodb-community`); the daemon waits
for mongo before launching so `(go)` connects on the first try.

This daemon manages **only the game server**. The AI client REPLs (7889 runner /
7890 corp) are still hand-managed via `make reset` / `make resume`.

## Install

```bash
cp dev/launchd/com.clamatius.netrunner-gameserver.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.clamatius.netrunner-gameserver.plist
```

`RunAtLoad` brings it up immediately (and at every login). Confirm:

```bash
launchctl print gui/$(id -u)/com.clamatius.netrunner-gameserver   # state / pid / last exit
lsof -nP -iTCP:1042 -sTCP:LISTEN                                   # server listening
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:1042/   # expect 200
tail -f ~/Library/Logs/netrunner-gameserver.log                   # boot log
```

## Manage

```bash
launchctl kickstart -k gui/$(id -u)/com.clamatius.netrunner-gameserver   # restart (bounce)
launchctl print     gui/$(id -u)/com.clamatius.netrunner-gameserver       # status
launchctl bootout   gui/$(id -u) ~/Library/LaunchAgents/com.clamatius.netrunner-gameserver.plist  # stop + unload
```

`KeepAlive` means launchd respawns the server within ~15s if it dies (verified by
`kill -9` of the lein pid). To stop it for real, use `bootout` — a bare `kill`
just gets it restarted.

## Run multiple servers on different ports

Everything is env/arg driven — no code edits. To add a second instance, copy the
plist to a new Label and change **four** things:

1. **`Label`** — must be unique, e.g. `com.clamatius.netrunner-gameserver-b`.
2. **nREPL port** — the `:port 7888` in `ProgramArguments` → e.g. `:port 7891`.
3. **`WEB_SERVER_PORT`** — in `EnvironmentVariables` → e.g. `1043`. This is the
   port clients connect to (`ws://localhost:<port>/chsk`); it's read from env via
   `resources/dev.edn` (`:web/server {:port #long #or [#env WEB_SERVER_PORT 1042]}`).
4. **`StandardOutPath`/`StandardErrorPath`** — a distinct log file.

Mongo (27017) is shared across instances — fine, games are keyed by game id. Point
the relevant AI client / `send_command` config at the new web port to use it
(see `dev/load-env.sh` `WEB_SERVER_PORT`).

## Machine-specific paths

The committed plist hard-codes this machine's paths (homebrew lein at
`/opt/homebrew/bin/lein`, the repo under `~/workspace/...`, logs under
`~/Library/Logs`). On a different machine or layout, adjust `WorkingDirectory`,
the `lein` path in `ProgramArguments`, `PATH`, and the log paths.
