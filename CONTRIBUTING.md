# Contributing

Keep changes within the active milestone. Describe the behavior, limitations and tests in
each change. Run the checks in [README.md](README.md) before submitting work. Do not claim
cross-platform or microphone validation based only on synthetic tests.

Use Java 25, Gradle Kotlin DSL, strict Angular/TypeScript, immutable domain values and
`org.arpeon` packages. Domain code must not import infrastructure types. Apply Java
formatting with `./gradlew formatJava`; use `npm run format` in `frontend` for web files.

Dependencies, fixtures and assets require a verified license and provenance. Follow
[dependency policy](docs/legal/dependency-policy.md). Never add private scores, recordings,
tokens or personal paths. Contributions are offered under AGPL-3.0-or-later; retain
third-party notices. Do not add author identities on someone else's behalf.

Use a focused diff and concise Conventional Commit messages when committing. Changes to
cross-module, security, licensing or persistence contracts need an ADR. See
[module boundaries](docs/architecture/module-boundaries.md).
