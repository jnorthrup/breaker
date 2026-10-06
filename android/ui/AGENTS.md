# AGENTS.md — android/ui/

## Purpose
Screens: dictation, history, settings, auth, training.

**Build phase:** Phase 1 (the app shell's first screens). Each screen arrives with its feature's phase, and the Trucking theme with Phase 22. Needs first: `core` and `shared/ui-tokens` (both on `main`).

## Owns
screen components; consumes `shared/ui-tokens` (Trucking theme).

## Public Interface
Screen components

## Depends On
- android
- android_core
- shared_ui_tokens

## Invariants
screens render with ui-tokens; light/dark toggle persists (F25).

## Does Not Own
- Business logic (core)
- Platform adapters (modules/*)

## Test Locations
- Unit (Kotlin): `android/ui/src/test/kotlin/`, created with the module's first code. Run: `./mvnw -pl android/ui test`
- Structural contract: `tools/src/test/kotlin/dev/breaker/tools/RepoContractTest.kt`, run with every build (`./mvnw clean install`): card sections, README presence, reactor/registry agreement, no cycles.
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

## Known Gotchas
- Consumes shared/ui-tokens — do not hardcode colors in screens.
