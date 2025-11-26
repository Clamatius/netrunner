# Debugging (go) Initialization

The REPL now starts successfully, but `(go)` (Integrant system startup) is currently failing.

## Current Status

✅ **FIXED**:
- REPL starts on port 7888
- No port conflicts
- nREPL server properly configured  
- WEB_SERVER_PORT parsed as integer

❌ **ISSUE**: `(go)` fails with MongoDB connection state error

## How to Debug

Start the REPL:
```bash
./dev/repl-start.sh
```

Once in the REPL, run the debug scripts:

```clojure
;; Test MongoDB connection directly
(load-file "dev/test-mongo.clj")

;; Full (go) debug with error handling
(load-file "dev/debug-go.clj")
```

## Known Error

When calling `(go)`, you see:
```
Error on key :web/server when building system
Execution error (IllegalStateException) at com.mongodb.assertions.Assertions/isTrue
state should be: open
```

This suggests that when the web server component tries to initialize, it depends on MongoDB but the connection is in a closed state.

## Next Steps

1. Check if MongoDB is running: `lsof -i :27017`
2. Test MongoDB connection independently (use `dev/test-mongo.clj`)
3. Look at Integrant component initialization order
4. Check for timing issues or race conditions between components

## Workaround

For now, you can:
1. Start the REPL: `./dev/repl-start.sh`
2. DON'T call `(go)` if you only need the REPL for other work
3. If you need the web server, debug the `(go)` failure interactively

