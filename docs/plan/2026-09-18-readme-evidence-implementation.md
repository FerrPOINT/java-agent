# Java Agent README Evidence Implementation Plan

**Goal:** Make the Java Agent README an evidence-first product entry point without inventing a browser dashboard or exposing credentials.

**Architecture:** The README keeps Java Agent's graphite/red/amber identity, adds a repository-local accessible banner, and distinguishes its API runtime, Telegram gateway, CLI, and management plane. Readme validation is a small dependency-free Python script with unit tests and a CI job.

**Scope:** Current source and live Base runtime are the only facts. Public screenshots use unauthenticated health responses only; they must not show credentials, session content, or private configuration.

## Tasks

1. Add failing tests for README placeholders, local paths, missing local images, missing explicit navigation anchors, and non-existent workflow badges.
2. Add `scripts/verify_readme.py`, make the tests green, and add a CI README job.
3. Capture safe desktop/mobile runtime evidence from `GET /health` and the Telegram bot health endpoint; verify no sensitive values are present.
4. Replace the root README with the factual Java Agent product narrative, local banner, real health evidence, runtime topology, safety boundaries, current Compose quick starts, and quality gates.
5. Correct nearby E2E documentation where its scenario count is stale, then run markdown, validator, Gradle, Compose, runtime, browser and hosted-CI gates.

## Verification

```bash
python3 -m unittest scripts.tests.test_verify_readme -v
python3 scripts/verify_readme.py
python3 scripts/check-compose-backend-auth.py
./gradlew :backend:test :telegram-bot:test :cli:test --no-daemon
```

The hosted workflow remains the final authoritative Gradle, Docker E2E, Markdown/YAML and README-validation gate.
