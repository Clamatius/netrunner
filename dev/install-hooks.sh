#!/bin/bash
# Install git hooks for Netrunner AI development

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$SCRIPT_DIR/.."
HOOKS_DIR="$REPO_ROOT/.git/hooks"

echo "📦 Installing git hooks..."

# Install pre-commit hook
if [ -f "$SCRIPT_DIR/hooks/pre-commit" ]; then
    cp "$SCRIPT_DIR/hooks/pre-commit" "$HOOKS_DIR/pre-commit"
    chmod +x "$HOOKS_DIR/pre-commit"
    echo "   ✓ Installed pre-commit hook"
else
    echo "   ⚠️  Warning: pre-commit hook template not found"
fi

echo ""
echo "✅ Git hooks installed successfully!"
echo ""
echo "The pre-commit hook will:"
echo "  - Check AI client code compilation before commits"
echo "  - Block commits with syntax errors"
echo "  - Skip check if no AI files are modified"
echo ""
echo "To bypass the hook (not recommended):"
echo "  git commit --no-verify"
