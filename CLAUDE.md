# algorith-drills

## Conventions

### `jdk` package: unit tests only

Everything under `src/java/jdk` is implementation. Anything you would demonstrate or verify goes in
`test/java/jdk/<Subject>Test.java` as plain JUnit tests:

- no example classes carrying a `main` that prints PASS/FAIL self-checks;
- no benchmark, stress or measurement harnesses shipped as production source;
- tests are self-contained — a test must not depend on a demo class for its fixtures.

Numbers that are worth recording but are not assertions (throughput, wall-clock) belong in the notes
under `docs/jdk/`, marked as measured ad hoc rather than as something the build reproduces.

The older `unclassified` package predates this rule and does carry `main`-based examples; leave it
as it is.

## Build

Requires **JDK 25** — `maven.compiler.release` is 25, and `mvn test` on an older JDK fails with
`release version 25 not supported`. Sources live in `src/java`, tests in `test/java` (the pom
overrides Maven's defaults to match the IntelliJ layout).
