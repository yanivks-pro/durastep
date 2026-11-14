# ADR-0005: Maven multi-module build

- **Status:** Accepted
- **Date:** 2026-11-14
- **Requirements:** C-04, Q-13, Q-15

## Context

Durastep ships a core engine plus optional pieces: a Spring Boot starter, a CLI, a dashboard, a test kit,
examples and benchmarks. Users should depend only on what they need, and the core must stay free of framework
code. Every contributor and CI run should build with the same tools and versions.

## Decision

Durastep is built with Maven, through the Maven Wrapper (`./mvnw`), as a multi-module project.

- **Parent POM** (`durastep-parent`): pins every library and plugin version, and configures the quality tools
  once for all modules: the enforcer (Java 25+, Maven 3.9+), Error Prone with `-Xlint:all -Werror`, Spotless
  (google-java-format), JaCoCo, Surefire for `*Test` and Failsafe for `*IT`.
- **Modules are added in the milestone that needs them.** The build starts with `durastep-core` only.
- `./mvnw verify` runs every check and test, locally and in CI.

## Alternatives considered

- **Gradle.** Faster incremental builds and a flexible build language, but the build becomes a program to
  maintain and review. Maven's fixed lifecycle is more predictable and familiar to most Java teams.
- **A single module.** Simpler, but the optional pieces would leak Spring, picocli and Javalin into every user's
  classpath.
- **All modules created up front.** Shows the full layout early, but empty modules produce empty JARs and add
  noise until there is code to put in them.

## Consequences

- **Positive:** a reproducible build on any machine with Java 25. Quality rules apply to every module
  automatically. Dependency updates touch one file.
- **Negative:** XML is verbose. Error Prone needs extra JVM flags for compiler internals, kept in
  `.mvn/jvm.config`.
- **Known pin:** google-java-format is held at 1.36.1 because 1.37.0 fails inside spotless-maven-plugin 3.10.4.
  Revisit on the next Spotless upgrade.
