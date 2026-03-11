# Release Process

## Overview

This is a reference project — it does not produce deployable artifacts. "Releases" are tagged commits that represent stable states of the reference patterns.

## Versioning

No formal versioning strategy is currently in place. The project evolves incrementally as new skills and patterns are added.

## When to tag a release

Consider tagging when:
- A significant new skill is added with working code and documentation
- A major refactoring stabilizes (e.g., simulator infrastructure overhaul)
- The project reaches a milestone (e.g., "all core testing patterns documented")

## How to tag

```bash
git tag -a v0.1.0 -m "Description of what this release represents"
git push origin v0.1.0
```

## CI/CD

The project does not currently have CI/CD. Tests are run locally:

```bash
docker-compose up -d
./gradlew test
```

Future consideration: GitHub Actions workflow for running tests on push and PRs, similar to the pattern documented in the dnsctl project's release process.
