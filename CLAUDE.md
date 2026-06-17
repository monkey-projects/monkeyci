# MonkeyCI Project Conventions

MonkeyCI is a CI/CD pipeline tool that uses Clojure as a build script language.
Build scripts are preferred to be executed using [Babashka](https://github.com/babashka/babashka),
but it's also possible to run them in pure Clojure.  The application itself is
also written in Clojure.

The major differentiating features about MonkeyCI are:
- Uses Clojure, in addition to Yaml, Json and Edn for build scripts
- Allows users to write unit tests for build scripts
- Provides a CLI that can run build scripts on developers' computers.

## Structure

- app: webapp backend
- gui: webapp frontend
- cli: command-line interface, compiled to native image using GraalVM
- common: common code shared between all subsystems (cli, app and gui)
- script: library that's included in build scripts, must be Babashka compatible.
- core: shared code between app, cli and scripts.  Must be Babashka compatible.
- test-lib: library included in build script tests that provides helper functions to write unit tests.

## Stack

The frontend uses [Shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
and [Re-frame](https://day8.github.io/re-frame/re-frame/).

The backend uses [Reitit](https://github.com/metosin/reitit) for HTTP handling,
and [Aleph](https://aleph.io/) as HTTP server.  This means that a lot of async
processing is done using [Manifold](https://github.com/clj-commons/manifold).

The CLI needs to be GraalVM compatible, so some libraries used in the backend cannot
be used here.  Instead we use [HttpKit](https://github.com/http-kit/http-kit) as a http
client and [core.async](https://clojure.github.io/core.async/index.html) for async processing.

The script lib must be Babashka compatible.

All libs use the [Clojure CLI](https://clojure.org/reference/clojure_cli).  The script
lib also uses Babashka for unit tests.

## Rules

- We apply TDD, so write unit tests first, then make them pass by writing production code.
- Source is found in the `src` dir.
- Tests are put in the `test` dir.

## Commands

- Testing: `clj -X:test` (fail-fast)
- Run all tests: `clj -X:test:junit` (also outputs junit.xml).  For app, the command is `clj -M:test:junit.`
- Babashka tests: `bb run test`
- Frontend tests: `npm run test`
- Build jar: `clj -T:jar`
- Build uberjar (app and cli only): `clj -T:jar:uber`
- Build native CLI: `cli/build-native` (this also builds uberjar)
- Build frontend release: `npx run prod`
