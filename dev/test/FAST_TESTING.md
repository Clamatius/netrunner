# Fast Testing with Persistent REPL

The issue with `lein kaocha` is that it starts a new JVM every time, which takes ~30+ seconds. Here's how to use a persistent REPL for **much faster** test cycles:

## Option 1: Quick REPL Testing (Fastest!)

Start a REPL once and keep it running:

```bash
# Terminal 1: Start REPL
lein repl

# In REPL:
(require '[kaocha.repl :as k])
(require '[ai-actions-test] :reload)
(require '[ai-actions-sad-path-test] :reload)

# Run tests (< 1 second after first run!)
(k/run 'ai-actions-test)
(k/run 'ai-actions-sad-path-test)

# After making changes to code or tests, reload and re-run:
(require '[ai-actions] :reload)
(require '[ai-actions-test] :reload)
(k/run 'ai-actions-test)
```

## Option 2: Watch Mode

Even faster - auto-run tests when files change:

```bash
lein kaocha --watch
```

Leave this running and it will automatically re-run tests whenever you save a file.

## Option 3: Focus on Specific Tests

```clojure
; In REPL:
(k/run 'ai-actions-test/test-show-hand)  ; Single test
(k/run #".*sad-path.*")                   ; Regex pattern
```

## REPL-Driven Development Workflow

1. **Start REPL once** (30s startup cost, one time)
2. **Make code changes** in your editor
3. **Reload in REPL**: `(require '[ai-actions] :reload)` (< 1s)
4. **Run tests**: `(k/run 'ai-actions-test)` (< 1s)
5. **Repeat 2-4** as needed

This is **30x faster** than running `lein kaocha` each time!

## Quick Reference

```clojure
;; Setup (once per REPL session)
(require '[kaocha.repl :as k])

;; Reload changed code
(require '[ai-actions] :reload)
(require '[ai-actions-test] :reload)

;; Run tests
(k/run 'ai-actions-test)                    ; All tests in namespace
(k/run 'ai-actions-test/test-show-hand)     ; Single test
(k/run {:kaocha.filter/focus [:ai-actions-test]})  ; Multiple namespaces

;; Run with options
(k/run {:kaocha/fail-fast? true})           ; Stop on first failure
```

## Debugging in REPL

```clojure
;; Test individual functions directly
(require '[ai-actions :as ai])
(require '[test-helpers :refer [mock-client-state with-mock-state]])

;; Set up mock and test
(with-mock-state
  (mock-client-state :hand [{:title "Sure Gamble"}])
  (ai/show-hand))

;; Check state
@ai-websocket-client-v2/client-state
```

## Tips

- **Keep REPL running** between sessions
- Use **`:reload`** to pick up file changes
- Use **`:reload-all`** if dependencies changed
- Add `(clojure.pprint/pprint result)` to inspect data
- Use `(clojure.stacktrace/print-stack-trace *e)` for errors

---

This workflow is standard in Clojure development and makes testing **interactive and fast**!
