<!-- Co-authored-by: Claude Code <noreply@anthropic.com> -->

# Contributing to oncompanion

Thanks for helping. This page shows how to get from an idea to a merged pull request. The rules behind each step are in [AGENTS.md](AGENTS.md). It is written for people and coding agents alike, and it is the single source: when this page and AGENTS.md disagree, AGENTS.md wins.

oncompanion is used by people in cancer therapy. It is an organizational tool, **not a medical device**: never add code that diagnoses, gives medical advice or changes a treatment ([Project context](AGENTS.md#project-context)).

## Set up

1. Follow [Getting started](README.md#getting-started) in the README: clone, build (Gradle downloads Java 25 itself), and start the Firebase emulators for the tests that touch Firestore or Auth.
2. Tests only use the emulators, never the production project.
3. `app/google-services.json` is committed on purpose. Never commit `local.properties`, service account keys or admin SDK credentials.

## From issue to merge

1. **Find or create an issue.** Use a template: a user story (`US-<n>: ...`) under its epic, a sprint task under its story, or a bug (`fix: ...`). Fill in its fields on the Scrum board. See [Issues and the Scrum board](AGENTS.md#issues-and-the-scrum-board).
2. **Move it to In Development** when you start, and read [docs/architecture.md](docs/architecture.md) before adding a feature, a collection or a use case.
3. **Branch** from `main`: `feature/<short-name>`, `fix/...`, `chore/...`, `ci/...` or `docs/...`. Never push to `main`.
4. **Write the code and its tests** following [Architecture](AGENTS.md#architecture), [Code style](AGENTS.md#code-style), [Firebase and data](AGENTS.md#firebase-and-data) and [Tests](AGENTS.md#tests). Aim for 95% line coverage on new code.
5. **Commit** with [Conventional Commits](AGENTS.md#commits) (`feat: add one-tap fatigue logging`): one logical change per commit, with its tests.
6. **Run the checks** before pushing ([Build, format, test](AGENTS.md#build-format-test)):

   ```bash
   ./gradlew ktfmtFormat
   ./gradlew check
   ./gradlew connectedCheck
   ```

7. **Open a pull request** with the [template](.github/PULL_REQUEST_TEMPLATE.md) and `Closes #<issue>`, and move the issue to In review. Keep it small: about 400 lines of production code. A UI change shows screenshots, and a new screen, use case or repository updates its diagram in `docs/`. See [Pull requests](AGENTS.md#pull-requests).
8. **Merge** once it has one approving review and a green CI, tests and SonarCloud included. Move the issue to Done and fill in its Actual Time.

If `strings.xml`, `C.kt`, `AppNavHost.kt` or `firestore.rules` conflict, keep both sides ([Avoiding merge conflicts](AGENTS.md#avoiding-merge-conflicts)).

## Reviews

- **As a reviewer:** use *Request changes* for bugs, missing tests and broken conventions, and *Comment* for suggestions. Check the PR's size and SonarCloud's new issues: a PR adds none.
- **As an author:** reply on each thread with the commit that fixes it ("Done in 3f6531b") and let the reviewer resolve it. Once a review has started, only add commits and merge `main` instead of rebasing. Re-request the review after pushing.

Details in [Reviews](AGENTS.md#reviews).

## Using AI

An AI that writes code is a contributor. Credit it at the top of every file it wrote or changed, above `package`:

```kotlin
// Co-authored-by: Claude Code <noreply@anthropic.com>
```

Use `#` in YAML and `<!-- ... -->` in Markdown. Coding agents read [AGENTS.md](AGENTS.md) directly.

## Where to read more

- [AGENTS.md](AGENTS.md): every rule, from architecture to releases.
- [docs/architecture.md](docs/architecture.md): the layers, the Firestore layout and one diagram per feature.
- [DESIGN.md](DESIGN.md): the theme's tokens and components.
- [PRODUCT.md](PRODUCT.md): who the app is for and what it does.
