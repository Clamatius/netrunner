# Instructions Packager
# Assembles side-specific instruction bundles for AI players

SCRIPT_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
TARGET_DIR := $(SCRIPT_DIR)target
PACKAGE := $(SCRIPT_DIR)package.py

.PHONY: all clean stats corp runner extras help

# Default: build both core bundles
all: $(TARGET_DIR)/corp_instructions.md $(TARGET_DIR)/runner_instructions.md

$(TARGET_DIR):
	mkdir -p $(TARGET_DIR)

$(TARGET_DIR)/corp_instructions.md: $(TARGET_DIR) $(SCRIPT_DIR)player_instructions.md $(SCRIPT_DIR)corp_play_structure.md $(SCRIPT_DIR)tutorial_deck_corp.md
	$(PACKAGE) corp -o $@
	@echo "Built: $@ ($$(wc -c < $@ | tr -d ' ') bytes)"

$(TARGET_DIR)/runner_instructions.md: $(TARGET_DIR) $(SCRIPT_DIR)player_instructions.md $(SCRIPT_DIR)runner_play_structure.md $(SCRIPT_DIR)tutorial_deck_runner.md
	$(PACKAGE) runner -o $@
	@echo "Built: $@ ($$(wc -c < $@ | tr -d ' ') bytes)"

# Individual targets
corp: $(TARGET_DIR)/corp_instructions.md
runner: $(TARGET_DIR)/runner_instructions.md

# With extras (playbooks + timing reference)
extras: $(TARGET_DIR)/corp_instructions_full.md $(TARGET_DIR)/runner_instructions_full.md

$(TARGET_DIR)/corp_instructions_full.md: $(TARGET_DIR)
	$(PACKAGE) corp --extras -o $@
	@echo "Built: $@ ($$(wc -c < $@ | tr -d ' ') bytes)"

$(TARGET_DIR)/runner_instructions_full.md: $(TARGET_DIR)
	$(PACKAGE) runner --extras -o $@
	@echo "Built: $@ ($$(wc -c < $@ | tr -d ' ') bytes)"

# Show file statistics
stats:
	@echo "=== Corp ===" && $(PACKAGE) corp --stats
	@echo ""
	@echo "=== Runner ===" && $(PACKAGE) runner --stats

# Clean generated files
clean:
	rm -rf $(TARGET_DIR)

help:
	@echo "Instructions Packager"
	@echo ""
	@echo "Targets:"
	@echo "  all     - Build corp + runner bundles (default)"
	@echo "  corp    - Build corp bundle only"
	@echo "  runner  - Build runner bundle only"
	@echo "  extras  - Build bundles with playbooks + timing reference"
	@echo "  stats   - Show file size breakdown"
	@echo "  clean   - Remove generated files"
	@echo ""
	@echo "Output: target/corp_instructions.md, target/runner_instructions.md"
