# TODO — ALL CLOSED (2026-08-26, 0.1.140)

All 8 items closed as of 0.1.35. Historical details moved to CHANGELOG.md.

| # | Was | Fixed |
|---|-----|-------|
| 1 | background result unavailable | POST /agent/background → {jobId,status}; GET /agent/background/{id} (migration V32) |
| 2 | reasoning levels CLI≠backend | unified set + GET /agent/reasoning-levels; CLI synced |
| 3 | health 6-30s | /actuator/health/readiness (3ms); heavy checks only in infrastructure group |
| 4 | heartbeat delivery no traces | HEARTBEAT_DELIVERED/_FAILED/_TOTAL in bot journalctl |
| 5 | /editor without TTY spawned vim | System.console()==null → error message |
| 6 | "no /restore in CLI" | false alarm: /rollback <id> already existed |
| 7 | /usage without cost | UsageDto + cost + models; CLI prints Cost $X.XXXXX |
| 8 | /restart wiped history | Hermes parity: drain + reload, history NEVER touched |