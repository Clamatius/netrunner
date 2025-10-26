# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jinteki.net is a browser-based implementation of the Netrunner card game. The project uses:
- **Backend**: Clojure (server-side game engine and web server)
- **Frontend**: ClojureScript with Reagent (React wrapper)
- **Database**: MongoDB for persistence
- **Build Tools**: Leiningen (Clojure), npm (frontend assets), shadow-cljs (ClojureScript compilation)
- **Java Version**: Java 21 (required)

## Essential Commands

### Initial Setup
```bash
# Install dependencies
npm ci

# Populate database and create indexes
lein fetch [--no-card-images]  # --no-card-images skips image download
lein create-indexes

# Compile CSS
npm run css:build

# Compile ClojureScript
npm run cljs:build
```

### Development Workflow
```bash
# Start REPL (automatically starts web server on port 1042)
lein repl
# In REPL: (go) to start, (halt) to stop, (restart) to reload

# Watch and recompile CSS on changes
npm run css:watch

# Watch and recompile ClojureScript on changes
npm run cljs:watch

# Production release builds
npm run release  # Builds both CSS and ClojureScript optimized
```

### Testing
```bash
# Run all tests
lein kaocha

# Run specific test file
lein kaocha --focus game.cards.agendas-test

# Run single test
lein kaocha --focus game.cards.agendas-test/fifteen-minutes

# In REPL (after requiring kaocha.repl)
(run)  # Available via dev/src/clj/user.clj
```

### Database Management
```bash
# Create sample data for testing (50k users, 550k decks, etc.)
lein create-sample-data

# Drop all indexes (useful for profiling queries)
lein drop-indexes

# Update card data from sources
lein fetch

# Other database tasks
lein delete-duplicate-users
lein update-all-decks
```

### Translation Management
```bash
# Find missing translations
lein missing-translations [language]

# Find undefined translations in code
lein undefined-translations

# Find unused translation entries
lein unused-translations
```

### Other Utilities
```bash
# Card coverage analysis
lein card-coverage

# Game and user statistics
lein get-game-stats
lein get-user-stats
```

## Architecture

### Directory Structure

**Backend (Clojure):**
- `src/clj/web/` - Web server, API endpoints, WebSocket handlers
  - `core.clj` - Application entry point
  - `system.clj` - Integrant system configuration and component lifecycle
  - `lobby.clj` - Game lobby management
  - `game.clj` - Game session handling
  - `auth.clj` - Authentication and authorization
  - `api.clj` - HTTP API routes
  - `ws.clj` - WebSocket (Sente) configuration
- `src/clj/game/` - Game engine implementation
  - `core/` - Core game mechanics (69 files organized by function)
    - `engine.clj` - Event resolution and game state engine
    - `state.clj` - Game state management
    - `card_defs.clj` - Card definition system
    - `actions.clj`, `runs.clj`, `damage.clj`, etc. - Specific game mechanics
  - `cards/` - Individual card implementations by type
    - `agendas.clj`, `ice.clj`, `operations.clj`, `resources.clj`, etc.
- `src/clj/tasks/` - CLI tasks and database operations

**Frontend (ClojureScript):**
- `src/cljs/nr/` - Frontend application components
  - `gameboard/` - Main game UI
  - `account.cljs` - User account management
  - `ajax.cljs` - HTTP client utilities

**Shared (CLJC):**
- `src/cljc/jinteki/` - Shared code between client and server
  - `cards.cljc` - Card data structures
  - `utils.cljc` - Shared utilities
  - `validator.cljc` - Deck validation
  - `i18n.cljc` - Internationalization
- `src/cljc/game/core/` - Shared game logic

**Tests:**
- `test/clj/game/cards/` - Card implementation tests
- `test/clj/game/core/` - Core game mechanic tests
- Test configuration in `tests.edn` (Kaocha)

**Assets:**
- `src/css/` - Stylus source files
- `resources/public/` - Static assets (compiled JS, CSS, images)

### Key Architectural Patterns

**System Initialization:**
- Uses Integrant for component lifecycle management
- Configuration in `resources/dev.edn` and `resources/prod.edn`
- System starts via `web.system/start` with component dependencies

**Game Engine:**
- Event-driven architecture using `game.core.engine`
- Game state stored in immutable data structures
- Cards implemented as maps with ability definitions
- Card abilities resolve through the engine's event system

**Card Implementation:**
- Cards defined using `defcard` macro in `src/clj/game/cards/*.clj`
- Card definitions in `game.core.card-defs` namespace
- Heavy use of helper functions from `game.core.def-helpers`
- Each card type (agenda, ice, operation, etc.) in separate file

**Frontend-Backend Communication:**
- WebSocket (Sente) for real-time game state updates
- HTTP API for REST operations (authentication, deck management)
- Shared data structures defined in `src/cljc/`

**Database:**
- MongoDB accessed via Monger library
- Collections: users, decks, cards, games, messages, etc.
- Index definitions in `src/clj/tasks/db.clj`
- See `DEVELOPMENT.md` for query profiling guidance

### REPL Development

The dev REPL loads `dev.user` namespace with helpful functions:
- `(go)` - Start system
- `(halt)` - Stop system
- `(restart)` - Reload and restart system
- `(reset)` - Full reset (via integrant.repl)
- `(fetch-cards)` - Fetch card data and reload
- `(run)` - Run tests (via kaocha.repl)

Shadow-cljs provides ClojureScript REPL via `npm run cljs:repl`

## Development Guidelines

**Code Style:**
- Follow [Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide) loosely
- Card implementations should use helper functions from `game.core.def-helpers`
- Keep card logic clear and well-commented

**Adding New Cards:**
1. Add card definition to appropriate file in `src/clj/game/cards/`
2. Write tests in corresponding file in `test/clj/game/cards/`
3. Use existing card implementations as templates
4. Test thoroughly with `lein kaocha --focus your-test`

**Database Indexes:**
- Profile slow queries per `DEVELOPMENT.md` instructions
- Add new indexes to `src/clj/tasks/db.clj`
- Prefer compound indexes over multiple single-field indexes

**Translations:**
- Translation files in `resources/public/i18n/`
- Use `(tr [...])` in code for translatable strings
- Check with `lein missing-translations` before committing

## Testing Strategy

- Write unit tests for all card implementations
- Tests use helper macros from test utilities
- Run focused tests during development: `lein kaocha --focus namespace-test`
- Full test suite should pass before committing
- Test configuration supports randomization, profiling, and filtering
