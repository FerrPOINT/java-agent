# UI Shell Applicability

Java Agent currently exposes REST, CLI and Telegram interfaces. It does not ship
an authenticated operational web console, so it has no sidebar/header/right-work
area implementation to standardize today.

If a first-party web console is added, it must adopt the Base [UI Shell
Standard](https://github.com/FerrPOINT/services-base/blob/main/docs/platform/UI_SHELL_STANDARD.md)
before page implementation: the shared 264 px/72 px left navigation, 60 px
header and full-width right work area. It must use only `wide` for operational
lists, session timelines, logs and tool activity, `reading/form` with a 760 px
inner form column, or `detail-with-aside` with a 320 px contextual rail. The
new console must prove 375, 1440 and 2560 px behavior, active route,
keyboard drawer flow and absence of document-level overflow.

The browser automation tool is not a user-facing console and is outside this
contract.

## References

- [Scope](01-scope.md) — implemented channels and exclusions.
- [Application Design](07-application-design.md) — runtime interfaces.
- [Base UI Shell Standard](https://github.com/FerrPOINT/services-base/blob/main/docs/platform/UI_SHELL_STANDARD.md) — fleet contract.
