# Skill Naming Examples

## Good Names (Class-Level Umbrellas)

- `skill-maintenance` — governs how to review sessions and maintain the skill library
- `database-troubleshooting` — covers debugging, slow queries, connection issues across engines
- `api-integration-patterns` — reusable patterns for REST, GraphQL, gRPC integrations
- `frontend-performance` — bundle optimization, rendering, network tuning
- `security-hardening` — auth, secrets management, dependency scanning
- `deployment-pipelines` — CI/CD patterns, rollback strategies, blue-green deploys

## Bad Names (Session-Level Artifacts)

- `fix-pr-1234` — tied to a specific pull request
- `debug-nullpointer-in-checkout-service` — tied to a specific error string
- `nextjs-14-upgrade` — tied to a specific version event
- `aws-cli-missing-on-mac` — environment-dependent failure
- `jest-timeout-fix` — too narrow; belongs under `testing-patterns` or `frontend-testing`
- `react-19-beta-workaround` — tied to a specific feature codename

## Guideline

If the name contains a version number, PR ID, error message fragment, or "fix/debug/workaround for", it is probably too narrow. Ask: "Will this still be relevant in 6 months?" If the answer is no, fold it into a broader umbrella or capture it as a `references/` file instead.
