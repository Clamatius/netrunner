# Game Server Startup Guide

## Quick Start

```bash
./dev/repl-start.sh
```

The web server will start automatically! Verify it's running:
```bash
lsof -i :7888  # nREPL
lsof -i :1042  # Web server
```

If auto-start fails, you can start manually in the REPL:
```clojure
(go)  ; Start the web server and game systems
```

## What Was Fixed

### Port Configuration Issues
- Fixed `lein repl` port syntax in `repl-start.sh`
- Removed duplicate nREPL server startup in `user.clj`
- Fixed `WEB_SERVER_PORT` type (string → integer) with `#long` reader

### Multi-Workspace Support
- All ports now configurable via `.env` file
- See `.env.example` for template
- Supports running multiple instances on different ports

## Current Behavior

✅ **Fully Working:**
- REPL starts on port 7888 (configurable via `GAME_SERVER_PORT`)
- Web server auto-starts on port 1042 during REPL initialization
- MongoDB connection successful
- Full Integrant system initialization
- Graceful error handling if auto-start fails

## Debugging

If `(go)` fails:
```clojure
(load-file "dev/debug-go.clj")   ; Full diagnostics
(load-file "dev/test-mongo.clj")  ; Test MongoDB only
```

See `dev/README-GO-DEBUGGING.md` for details.

## Environment Variables

Copy `.env.example` to `.env` and customize:

```bash
GAME_SERVER_PORT=7888    # nREPL port
CLIENT_1_PORT=7889       # Runner AI client
CLIENT_2_PORT=7890       # Corp AI client  
WEB_SERVER_PORT=1042     # HTTP server
```

## Commits

All fixes committed on branch `feat/ai_player_experiment`:
- Port configuration fixes
- Duplicate nREPL removal
- Type fixes for environment variables
- Debugging tools

