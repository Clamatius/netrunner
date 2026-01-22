#!/bin/bash

# Play a full game as Runner until completion
# Coordinates with Corp turns automatically

MAX_TURNS=30
turn=1

echo "Starting Runner automated play..."

while [ $turn -le $MAX_TURNS ]; do
  echo "============================================"
  echo "Turn $turn - Checking game state..."
  echo "============================================"

  # Check if game is over
  status=$(./dev/send_command runner status 2>&1)

  # Check for game end conditions
  if echo "$status" | grep -q "wins\|flatlined\|decked"; then
    echo "🎉 GAME ENDED!"
    echo "$status"
    exit 0
  fi

  # Check whose turn it is
  if echo "$status" | grep -q "Waiting to start runner turn"; then
    echo "Starting Runner turn $turn..."
    ./dev/send_command runner start-turn
    sleep 2

    # Runner strategy: simple aggressive play
    # 1. Draw cards if hand is small
    # 2. Install programs/resources
    # 3. Make runs on servers

    hand_size=$(./dev/send_command runner hand 2>&1 | grep -c "^  [0-9]")
    clicks=$(./dev/send_command runner status 2>&1 | grep "Clicks:" | grep "RUNNER" -A 1 | tail -1 | awk '{print $2}')

    echo "Hand size: $hand_size, Clicks: $clicks"

    # Simple turn actions - just draw and make a run
    if [ "$clicks" -ge 1 ]; then
      ./dev/send_command runner draw
      sleep 2
    fi

    if [ "$clicks" -ge 2 ]; then
      # Try to run on R&D
      ./dev/send_command runner run "R&D"
      sleep 3
    fi

    # Turn will auto-end when clicks reach 0
    sleep 5

  elif echo "$status" | grep -q "Waiting to start corp turn"; then
    echo "Starting Corp turn $turn..."
    ./dev/send_command corp start-turn
    sleep 15  # Give corp time to complete actions

  else
    echo "Waiting for current player to finish..."
    sleep 5
  fi

  turn=$((turn + 1))
done

echo "Maximum turns reached without game ending"
./dev/send_command runner status
