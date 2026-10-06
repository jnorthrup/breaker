# AGENTS.md — tools/

## Purpose

Build verification tests: structural checks of the Maven reactor and the
module cards (AGENTS.md), with no external registry — the root `pom.xml`
reactor is read directly. Replaces tests/contract/, tests/unit/,
tools/check_repo.py and tools/gate.py entirely; Kotlin test classes run as
part of the normal Maven build.

**Build phase:** no phase — not a product module. Runs with every build.

## Owns

- `PomContractTest.kt` — reactor == registry, boundary bijection, artifactId == key.
- `RepoContractTest.kt` — card sections present, README/AGENTS.md exist, no
  dependency cycles, no unregistered folders with a card, parent READMEs name
  their sub-modules, no NUL bytes outside intentional binary-content tests.

## Does Not Own

- The build itself (pom.xml files)
- Runtime code of any kind

## Public Interface

None — test-only.

## Depends On

- none

## Invariants

- Every check in PomContractTest must be proven to fail loudly with a seeded breach.

## Test Locations

- Unit: `tools/src/test/kotlin/dev/breaker/tools/`
- Structural contract: `tools/src/test/kotlin/dev/breaker/tools/RepoContractTest.kt`, run with every build (`./mvnw clean install`): card sections, README presence, reactor/registry agreement, no cycles.

## Test Requirement

Every test added or touched must be proven to fail loudly: break the protected
behavior on purpose, confirm the test fails and says why, then restore. A test
that only ever passes proves nothing.

## Known Gotchas

- Tests use JDK javax.xml and java.io — no Maven dependencies needed beyond
  what the root POM universally provides (kotlin-stdlib, junit).
