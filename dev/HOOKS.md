# Git Hooks

## Pre-commit Hook

A pre-commit hook is installed at `.git/hooks/pre-commit` that automatically checks AI client code compilation before allowing commits.

### What it does

- Detects when any `.clj` files in `dev/src/clj/` are being committed
- Runs `lein check` to verify the code compiles
- Blocks the commit if syntax errors or compilation failures are detected
- Skips the check entirely if no AI client files are being committed

### Usage

The hook runs automatically on every `git commit`. No action needed!

**Output examples:**

Success:
```
🔍 Pre-commit: Checking AI client code...
   Checking staged AI files:
   - dev/src/clj/ai_runs.clj
   Running compilation check...
   ✓ AI client code compiles successfully
```

Failure:
```
🔍 Pre-commit: Checking AI client code...
   Checking staged AI files:
   - dev/src/clj/ai_runs.clj
   Running compilation check...

❌ Pre-commit check FAILED

Compilation errors detected:
----------------------------
Syntax error reading source at (ai_runs.clj:423:1).
EOF while reading, starting at line 12

Please fix compilation errors before committing.
To skip this check (not recommended): git commit --no-verify
```

### Bypassing the hook

If you need to commit broken code (not recommended):

```bash
git commit --no-verify
```

### Reinstalling

If the hook gets removed or modified, run the installation script:

```bash
./dev/install-hooks.sh
```

Or manually copy the template:

```bash
cp dev/hooks/pre-commit .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

## Future Enhancements

- [ ] Add test running to the pre-commit hook
- [ ] Add linting/formatting checks
- [ ] Create pre-push hook for running full test suite
- [ ] Make the hook installable via a script for team members
