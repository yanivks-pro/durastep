# Contributing to Durastep

Thank you for your interest. Durastep is small on purpose: a few things done to a high standard. The best
contributions keep it that way.

## Before you start

- **Bugs:** open an issue with what you did, what you expected and what happened, ideally with a failing test
  or the output of `durastep show <id>`.
- **Ideas:** open an issue to discuss them first. Durastep's [non-goals](README.md#non-goals) and the
  [Architecture Decision Records](docs/adr/README.md) explain what it deliberately does not do.
- **Security problems:** do not open an issue; follow [SECURITY.md](SECURITY.md).

## Build and test

You need Java 25 and Docker: the tests run against real Postgres in containers.

```bash
./mvnw verify              # format check, Error Prone, unit, architecture and Postgres tests, coverage
./mvnw spotless:apply      # format the code with google-java-format
./mvnw verify -Pchaos      # the chaos test, a few minutes
docker compose up -d --wait && demo/chaos.sh   # the demo, end to end
```

`./mvnw verify` must pass, and keeps line coverage at 85% or more in every module.

## How changes are made

- **Tests come with the change.** Unit tests for the logic, and integration tests against Postgres for anything
  that touches the database. For anything that can fail, a test of the failure: a crash, a lost lease, an outage.
- **Significant decisions get an ADR** in `docs/adr`: the context, the decision, the alternatives and the
  consequences.
- **Code explains itself through names.** There are no comments in code or configuration; the reasons go into the
  ADRs, the docs or the pull request. Javadoc documents the public API, and the build fails if any of it is
  missing.
- **The public API is stable from 1.0** ([what that covers](README.md#api-stability)). A change to it needs a good
  reason and, if it breaks anything, a major version.
- **Performance changes come with numbers** from `make bench`, before and after.

## Pull requests

Work on a short branch and open a pull request against `main`. Its title follows
[Conventional Commits](https://www.conventionalcommits.org) (`feat:`, `fix:`, `docs:` …), and its description
says in plain words what changed and why. CI must be green: the build on two Postgres versions, the one-command
demo and the README's quickstart. Pull requests are squash-merged.

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
