# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

Jinteki.net is a browser-based implementation of the Netrunner card game. The project uses:
- **Backend**: Clojure (server-side game engine and web server)
- **Frontend**: ClojureScript with Reagent (React wrapper)
- **Database**: MongoDB for persistence
- **Build Tools**: Leiningen (Clojure), npm (frontend assets), shadow-cljs (ClojureScript compilation), bb (repl client)
- **Java Version**: Java 21 (required)

Our project: implementing an AI player.

Key entry point: ./dev/send_command.
WIP reference doc: ./dev/GAME_REFERENCE.md
Iterative test/dev plan: ./dev/ITERATIVE_TESTING_PLAN.md
Client business logic: ./dev/src/clj/ai_actions.clj

send_command help has a lot of useful information about how to play.
