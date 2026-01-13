#!/bin/bash
# run-match.sh
# Orchestrates a Level 2 "Duelling Pistols" match between two agents.
# Usage: ./run-match.sh [config-file]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR/../.."
DEV_DIR="$ROOT_DIR/dev"
CONFIG_FILE="${1:-$SCRIPT_DIR/match-config.json}"
LOG_DIR="$ROOT_DIR/logs/matches"

mkdir -p "$LOG_DIR"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file not found: $CONFIG_FILE" >&2
    exit 1
fi

echo "🥊 Starting Match Orchestrator"
echo "📄 Config: $CONFIG_FILE"

# 0. Start Game Server (if not running)
echo "🔌 Checking Game Server..."
if ! lsof -i :1042 > /dev/null; then
    echo "   Starting Game Server (lein run)..."
    # Run lein from the project root
    (cd "$ROOT_DIR" && nohup lein run > /tmp/game-server.log 2>&1 &)
    
    # Wait for server
    echo "   Waiting for game server to listen on 1042..."
    MAX_WAIT_SERVER=120
    for i in $(seq 1 $MAX_WAIT_SERVER); do
        if lsof -i :1042 > /dev/null; then
            echo "   ✅ Game Server ready!"
            break
        fi
        if [ $i -eq $MAX_WAIT_SERVER ]; then
            echo "   ❌ Timeout waiting for game server"
            echo "   Check /tmp/game-server.log for details"
            exit 1
        fi
        sleep 1
    done
else
    echo "   ✅ Game Server already running"
fi

# 1. Start AI Clients (if not running)
echo "🔌 Checking AI Clients..."
if ! lsof -i :7889 > /dev/null; then
    echo "   Starting Runner Client..."
    "$DEV_DIR/start-ai-client-repl.sh" runner 7889 > /tmp/ai-client-runner.log 2>&1 &
fi
if ! lsof -i :7890 > /dev/null; then
    echo "   Starting Corp Client..."
    "$DEV_DIR/start-ai-client-repl.sh" corp 7890 > /tmp/ai-client-corp.log 2>&1 &
fi

# Wait for clients to be ready
echo "   Waiting for clients to listen..."
MAX_WAIT=60
for i in $(seq 1 $MAX_WAIT); do
    if lsof -i :7889 > /dev/null && lsof -i :7890 > /dev/null; then
        echo "   ✅ Clients ready!"
        break
    fi
    if [ $i -eq $MAX_WAIT ]; then
        echo "   ❌ Timeout waiting for clients"
        exit 1
    fi
    sleep 1
done
sleep 5 # Extra buffer for initialization

# 2. Parse Config (Simple grep/sed for now, JSON parsing later)
MATCH_ID=$(grep "match_id" "$CONFIG_FILE" | cut -d '"' -f 4)
ROUNDS=$(grep "rounds" "$CONFIG_FILE" | cut -d ':' -f 2 | tr -d ' ,')

# Extract agents block
AGENTS_BLOCK=$(grep -A 4 '"agents":' "$CONFIG_FILE")
AGENT_A=$(echo "$AGENTS_BLOCK" | grep '"agent_a":' | cut -d '"' -f 4)
AGENT_B=$(echo "$AGENTS_BLOCK" | grep '"agent_b":' | cut -d '"' -f 4)

# Fallback for old config format (corp/runner)
if [ -z "$AGENT_A" ]; then
    AGENT_A=$(echo "$AGENTS_BLOCK" | grep '"corp":' | cut -d '"' -f 4)
fi
if [ -z "$AGENT_B" ]; then
    AGENT_B=$(echo "$AGENTS_BLOCK" | grep '"runner":' | cut -d '"' -f 4)
fi

echo "🆔 Match ID: $MATCH_ID"
echo "🔄 Rounds: $ROUNDS"
echo "🤖 Agent A: $AGENT_A"
echo "🤖 Agent B: $AGENT_B"

# Helper to restart AI clients
restart_clients() {
    echo "♻️  Restarting AI Clients to clear previous brains..."
    "$DEV_DIR/stop-ai-client.sh" corp > /dev/null 2>&1
    "$DEV_DIR/stop-ai-client.sh" runner > /dev/null 2>&1
    sleep 2
    
    "$DEV_DIR/start-ai-client-repl.sh" corp 7890 > /tmp/ai-client-corp.log 2>&1 &
    "$DEV_DIR/start-ai-client-repl.sh" runner 7889 > /tmp/ai-client-runner.log 2>&1 &
    
    # Wait for clients
    echo "   Waiting for clients to listen..."
    MAX_WAIT=60
    for i in $(seq 1 $MAX_WAIT); do
        if lsof -i :7889 > /dev/null && lsof -i :7890 > /dev/null; then
            echo "   ✅ Clients ready!"
            break
        fi
        if [ $i -eq $MAX_WAIT ]; then
            echo "   ❌ Timeout waiting for clients"
            exit 1
        fi
        sleep 1
    done
    sleep 5 # Buffer for init
}

# Helper to load agent code
load_agent() {
    local side=$1
    local port=$2
    local agent_type=$3
    
    echo "      Injecting $side agent ($agent_type)..."
    
    if [ "$side" == "corp" ]; then
        case "$agent_type" in
            "heuristic")
                "$DEV_DIR/ai-eval.sh" corp "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_heuristic_corp.clj\") (require (quote [ai-heuristic-corp])) (future ((resolve (quote ai-heuristic-corp/start-autonomous!)))))" > /dev/null
                ;;
            "passive"|"goldfish")
                "$DEV_DIR/ai-eval.sh" corp "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_goldfish_corp.clj\") (require (quote [ai-goldfish-corp])) (future ((resolve (quote ai-goldfish-corp/start-autonomous!)))))" > /dev/null
                ;;
            *)
                echo "      ⚠️  Unknown Corp agent '$agent_type', defaulting to heuristic"
                "$DEV_DIR/ai-eval.sh" corp "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_heuristic_corp.clj\") (require (quote [ai-heuristic-corp])) (future ((resolve (quote ai-heuristic-corp/start-autonomous!)))))" > /dev/null
                ;;
        esac
    elif [ "$side" == "runner" ]; then
        case "$agent_type" in
            "heuristic")
                "$DEV_DIR/ai-eval.sh" runner "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_heuristic_runner.clj\") (require (quote [ai-heuristic-runner])) (future ((resolve (quote ai-heuristic-runner/loop!)))))" > /dev/null
                ;;
            "passive"|"goldfish")
                "$DEV_DIR/ai-eval.sh" runner "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_goldfish_runner.clj\") (require (quote [ai-goldfish-runner])) (future ((resolve (quote ai-goldfish-runner/loop!)))))" > /dev/null
                ;;
            *)
                echo "      ⚠️  Unknown Runner agent '$agent_type', defaulting to goldfish"
                "$DEV_DIR/ai-eval.sh" runner "$port" "(do (load-file \"$DEV_DIR/src/clj/ai_goldfish_runner.clj\") (require (quote [ai-goldfish-runner])) (future ((resolve (quote ai-goldfish-runner/loop!)))))" > /dev/null
                ;;
        esac
    fi
}

run_game() {
    local round=$1
    local game_suffix=$2
    local corp_agent=$3
    local runner_agent=$4
    
    GAME_LOG="$LOG_DIR/${MATCH_ID}_r${round}_game${game_suffix}.log"
    echo "🎮 Starting Game ${game_suffix} (Corp=$corp_agent, Runner=$runner_agent)..."
    
    # Restart clients to ensure no zombie threads
    restart_clients
    
    # Create Game
    LOBBY_TITLE="Match ${MATCH_ID} R${round}${game_suffix}"
    echo "   Creating lobby: $LOBBY_TITLE..."
    "$DEV_DIR/send_command" corp create-game "$LOBBY_TITLE" "Corp" "gateway-beginner-corp" > /dev/null
    sleep 2
    
    # Retry loop for Game ID (Search by Title)
    MAX_RETRIES=10
    GAME_ID_RAW=""
    for ((r=1; r<=MAX_RETRIES; r++)); do
        # 1. Request lobby list refresh
        "$DEV_DIR/ai-eval.sh" corp 7890 '(ai-connection/request-lobby-list!)' > /dev/null
        sleep 1
        
        # 2. Search for game ID by title in lobby list
        GAME_ID_RAW=$("$DEV_DIR/ai-eval.sh" corp 7890 "(some #(when (= (:title %) \"$LOBBY_TITLE\") (str (:gameid %))) (:lobby-list @ai-state/client-state))" | tail -1 | tr -d '"' | tr -d '\n')
        
        if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "nil" ] && [ "$GAME_ID_RAW" != "" ]; then
            break
        fi
        
        # Fallback: check if we are already in the game
        GAME_ID_RAW=$("$DEV_DIR/ai-eval.sh" corp 7890 '(str (:gameid @ai-state/client-state))' | tail -1 | tr -d '"' | tr -d '\n')
        if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "nil" ] && [ "$GAME_ID_RAW" != "" ]; then
             echo "      (Found via client state)"
             break
        fi

        # Fallback 2: Server Log Scrape
        SERVER_LOG="/tmp/game-server.log"
        if [ -f "$SERVER_LOG" ]; then
            GAME_ID_RAW=$(grep -A 1 "$LOBBY_TITLE" "$SERVER_LOG" | tail -n 1 | grep "gameid:" | sed 's/.*gameid: #uuid "\([^"]*\)".*/\1/')
            if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "" ] && [[ "$GAME_ID_RAW" != *"DEBUG"* ]]; then
                 echo "      ⚠️  (Found via server log scrape)"
                 break
            fi
        fi

        echo "      Waiting for Game ID... ($r/$MAX_RETRIES)"
        sleep 1
    done

    if [ -z "$GAME_ID_RAW" ] || [ "$GAME_ID_RAW" == "nil" ]; then
        echo "❌ Failed to get Game ID after creation"
        return 1
    fi

    echo "   Game ID: $GAME_ID_RAW"
    
    # Join Game
    echo "   Runner joining..."
    "$DEV_DIR/send_command" runner join "$GAME_ID_RAW" "Runner" "gateway-beginner-runner" > /dev/null
    sleep 2
    
    # Start Game
    echo "   Starting..."
    "$DEV_DIR/send_command" corp start-game > /dev/null

    # Inject Brains
    echo "   🧠 Injecting brains..."
    load_agent "corp" 7890 "$corp_agent"
    load_agent "runner" 7889 "$runner_agent"
    
    # Monitor Loop
    echo "   Monitoring..."
    FINISHED=false
    START_TIME=$(date +%s)
    
    while [ "$FINISHED" = false ]; do
        LOG_OUTPUT=$("$DEV_DIR/send_command" corp log 10 2>/dev/null) 
        
        if echo "$LOG_OUTPUT" | grep -qE "GAME OVER|wins the game"; then
            FINISHED=true
            echo "   ✅ Game Over detected!"
        fi
        
        CURRENT_TIME=$(date +%s)
        if [ $((CURRENT_TIME - START_TIME)) -gt 600 ]; then
            echo "   ❌ Timeout!"
            FINISHED=true
        fi
        
        sleep 5
    done
    
    # Save Log
    echo "   Saving log to $GAME_LOG"
    "$DEV_DIR/send_command" corp log 1000 > "$GAME_LOG"
    
    # Extract Result
    "$SCRIPT_DIR/extract-results.sh" "$GAME_LOG"
    
    # Leave Game
    "$DEV_DIR/send_command" corp leave-game > /dev/null
    "$DEV_DIR/send_command" runner leave-game > /dev/null
    sleep 2
}

# 3. Match Loop
for ((i=1; i<=ROUNDS; i++)); do
    echo "=== Round $i of $ROUNDS ==="
    
    # Game A: Agent A (Corp) vs Agent B (Runner)
    run_game "$i" "A" "$AGENT_A" "$AGENT_B"
    
    # Game B: Agent B (Corp) vs Agent A (Runner)
    echo "🔄 Swapping sides..."
    run_game "$i" "B" "$AGENT_B" "$AGENT_A"
done

echo "🏁 Match Complete"
