# Contributing to Libreon

Thanks for wanting to help! Libreon is a free, offline office suite for Android, and it gets better every time someone reports a problem, suggests something, or sends a fix. You don't need to be an Android developer to help.

## Report a problem

Found something broken? [Open a bug report](../../issues/new?template=bug_report.yml). The form asks for:

- what you did, what you expected, and what happened instead;
- your phone model and Android version (Settings → About phone);
- the Libreon version (Libreon → ⋮ → Settings → About);
- a screenshot if you can.

If the problem happens with one particular file, say what kind it is (Word, Excel, PDF…). Please **don't attach private documents**; if you can, recreate the problem with a harmless file.

## Suggest a feature

Have an idea? [Open a feature request](../../issues/new?template=feature_request.yml) and describe what you'd like to do and why. Check the [existing issues](../../issues) first: if someone already asked for it, add a 👍 to show it matters to you too.

## Send a fix or a feature (a pull request)

A *pull request* (PR) is how you propose a change to the code: you make the change in your own copy of the repository, and GitHub lets the maintainer review it and merge it in.

1. **Pick something to work on.** Issues labelled `good first issue` are a gentle start. For anything big, comment on the issue first so we can agree on the approach.
2. **Fork** the repository (the *Fork* button at the top of the page) and clone your fork.
3. **Create a branch** for your change: `git checkout -b fix-pdf-search`.
4. **Make the change**, following the rules below, and test it on a device or emulator.
5. **Run the tests**: `./gradlew :app:testDebugUnitTest :engine-office:testDebugUnitTest`.
6. **Push** the branch to your fork and **open a pull request**. The template asks what you changed and how you tested it.

The maintainer will review it, may ask for tweaks, and then merge it. Thank you!

## Ground rules

Libreon has a few promises that every change has to keep:

- **Offline, always.** The app must never use the internet, so it must never request the `INTERNET` permission (or any permission). The build fails if a dependency adds one. Links to web pages may only open in the browser when the user taps them.
- **No ads, trackers, analytics, accounts or AI features.** That's the point of the app.
- **Clean room.** Don't copy code, text, layouts, icons or other assets from proprietary apps, decompiled or otherwise. Write it yourself, or use open-source code whose license is compatible with the GPL-3.0, and add it to [docs/licenses.md](docs/licenses.md) and the in-app licenses screen.
- **Match the existing code.** Kotlin and Jetpack Compose, the same naming and comment style as the surrounding files, and a short SPDX license header on new files (`// SPDX-License-Identifier: GPL-3.0-or-later`).
- **Keep pull requests focused.** One fix or feature per PR is much easier to review.

By contributing, you agree that your contribution is licensed under the [GPL-3.0-or-later](LICENSE), like the rest of Libreon.

## Building

See [Building from source](README.md#building-from-source) in the README. The Office engine takes a few hours to build the first time; changes to the app itself (everything under `app/`) only need Gradle.
