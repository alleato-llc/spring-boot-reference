# Contributing

This is a reference project — the goal is clarity and demonstrating best practices. Each pattern is backed by working code and a corresponding skill.

## Adding a New Skill

A skill codifies a repeatable pattern. Every skill must have both **working code** in the project and a **skill document** that teaches the pattern. Skills can cover production patterns (project structure, entity design) or testing patterns (integration tests, test doubles).

### 1. Build the code abstraction

Implement the pattern in the reference project first. Depending on the skill, this may include:

- Production code (interfaces, implementations, entities, configuration)
- Test doubles (in-memory implementations, SDK-level fakes)
- Bean wiring in `TestConfiguration`
- Fields and reset logic in `BaseIntegrationTest`
- Database migrations if applicable

Follow existing conventions:
- Packages named after domain concepts, not technologies
- `*Service` for orchestrators, `*Client` for external boundaries, descriptive names for standalone logic
- `with*` fluent mutators on entities, no public setters
- Interfaces are the contract boundary for external services
- AWS SDK implementations run in tests (no `@Profile`); non-SDK HTTP implementations use `@Profile("!test")`
- Test doubles record calls for assertion and support `throwWhen` for failure simulation

### 2. Write tests

Add tests that demonstrate the pattern:

- **Integration tests**: Exercise the full request lifecycle. Assert on observable side effects (HTTP response, DB state, SDK call recordings).
- **Unit tests**: For pure computation. Assert on outputs given inputs.
- **Failure paths**: Use `throwWhen` to simulate external service failures.
- **Negative assertions**: Verify that downstream side effects do NOT happen when upstream steps fail.
- **Test data isolation**: Use random IDs, create fresh data per test, initialize contextual domain in `@BeforeEach`.

Tests should follow the project conventions:
- Extend `BaseIntegrationTest` for integration tests
- Use `@Nested` classes with `@DisplayName` to group scenarios
- Use AssertJ for all assertions
- Use typed clients (e.g., `OrderClient`) for HTTP calls

### 3. Verify all tests pass

```bash
docker-compose up -d
./gradlew test
```

### 4. Write the skill document

Create `.claude/skills/<skill-name>/SKILL.md` with:

```markdown
---
name: skill-name
description: One-sentence description of when to use this skill.
---

# Skill Title

Brief explanation of the pattern and when to use it.

## Architecture / Principles

Diagram, decision framework, or key rules.

## Step-by-step (or examples)

Numbered steps with code templates, or before/after examples.
Each step should be copy-paste ready with placeholders a developer
can adapt to their project.

## Checklist (optional)

Review checklist for applying or auditing this pattern.

## Reference

Links to the working examples in this project (use relative paths like
`src/main/java/.../models/Order.java`).
```

Guidelines for skill documents:
- Templates should be **actionable** — copy-paste ready, not abstract principles
- Include both success and failure patterns where applicable
- Document `throwWhen` usage if the skill introduces a test double
- Reference sections must point to files that exist in the project
- Keep the skill self-contained — a reader shouldn't need to cross-reference other skills to follow the steps (linking to related skills for context is fine)

### 5. Update project documentation

- Add the new skill to the table in `README.md` under "Agent Skills > Available skills" (production or testing section)
- Add the new skill to the list in `CLAUDE.md` under "Skills"

### 6. Open a pull request

- Title: `Add skill: <skill-name>`
- Description: What pattern the skill codifies, why it's useful, and a summary of the code changes
- Ensure all tests pass in CI (Postgres runs as a service container — see `setting-up-docker-for-tests`)

## Development Setup

### Prerequisites

- Java 25+ (GraalVM CE recommended — install via SDKMAN: `sdk install java 25.0.2-graalce`)
- Docker (for Postgres)

### Running Tests

```bash
docker-compose up -d    # Start Postgres
./gradlew test          # Run all tests
docker-compose down     # Stop when done
```

### Formatting & Linting

A **pre-commit hook** is auto-installed on your first build (`./gradlew classes`). It runs `spotlessCheck` and `checkstyleMain checkstyleTest` before each commit.

To format all Java files:

```bash
./gradlew spotlessApply
```

To verify formatting and linting locally (same checks as CI):

```bash
./gradlew spotlessCheck checkstyleMain checkstyleTest
```

## Project Conventions

- **Package structure**: Domain-oriented — core domain layered, supporting subdomains flat; 5–8 files per package
- **Component design**: Controllers thin (no business logic), services orchestrate (one use case per method), repositories query only
- **Naming**: `*Service` (orchestrators), `*Client` (external), descriptive names (standalone logic)
- **Entities**: `with*` fluent mutators, no public setters, pure computation returns result records
- **Method sizing**: Most 20–30 lines; orchestration up to 100; evaluate over 100
- **Composition over inheritance**: Prefer injected collaborators over class hierarchies; overloads delegate to the fuller signature
- **Test naming**: `*Test` for unit tests (no Spring context), `*IntegrationTest` for integration tests
- **Test location**: Tests live in the same package as the class they test
- **Test isolation**: Random IDs, fresh data per test, contextual domain in `@BeforeEach`
- **Contract over implementation**: Assert on observable behavior, not internal details
- **Skills are Java/Spring Boot specific**: For other languages, create a separate reference project
