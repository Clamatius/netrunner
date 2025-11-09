#!/bin/bash
# Test sending messages from AI client

echo "=== Testing Message Sending ==="
echo ""

# Test 1: Request lobby list (known to work)
echo "Test 1: Requesting lobby list..."
./dev/ai-eval.sh '(do (require '"'"'[ai-websocket-client-v2 :as ws]) (ws/request-lobby-list!) (Thread/sleep 1000) (count (ws/get-lobby-list)))'

echo ""
echo "Test 2: Creating lobby..."
# Reload code first
./dev/ai-eval.sh '(load-file "dev/src/clj/ai_websocket_client_v2.clj")'
./dev/ai-eval.sh '(load-file "dev/src/clj/ai_actions.clj")'

# Create lobby
./dev/ai-eval.sh '(do (require '"'"'[ai-websocket-client-v2 :as ws]) (ws/create-lobby! {:title "AI Test Lobby via REPL"}) (Thread/sleep 2000) :sent)'

echo ""
echo "Test 3: Check for new lobby in list..."
./dev/ai-eval.sh '(do (require '"'"'[ai-websocket-client-v2 :as ws]) (ws/request-lobby-list!) (Thread/sleep 1000) (println "Lobby count:" (count (ws/get-lobby-list))) (doseq [g (ws/get-lobby-list)] (println "  -" (:title g))) :done)'

echo ""
echo "=== Tests Complete ==="
