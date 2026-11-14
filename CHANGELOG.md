# Changelog

All notable changes to Durastep are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Maven multi-module build on Java 25 with pinned versions, Error Prone, warnings as errors, Spotless
  formatting, JaCoCo coverage and ArchUnit architecture rules.
- Integration tests against a real Postgres through Testcontainers.
- GitHub Actions CI on Postgres 16 and the latest Postgres, with Dependabot updates after a 14-day cooldown.
- Dependencies are downloaded from Maven Central only.
- Database schema V1 with Flyway: `workflows`, `history_events`, `tasks`, `timers` and `inbox` in a
  dedicated `durastep` schema, with constraints for the core rules.
- Architecture Decision Records 0001 to 0007.
- README and the Apache 2.0 licence.
