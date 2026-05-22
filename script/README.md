# MonkeyCI Script Lib

This library is a part of [MonkeyCI](https://monkeyci.com).  It is a Clojure library
that is used by the build agents and the [CLI](../cli) to load build scripts.  A
build script can consist of multiple files, depending on the format.  MonkeyCI
supports these file types:

 - YAML
 - JSON
 - EDN
 - Clojure
 
Only the Clojure type supports the "higher level" abilities like conditions, build
parameters, action jobs, etc...  The others are similar to each other, they only
allow to define container jobs, but they can be useful for basic jobs.

## Usage

Build agents are long-running processes, but the CLI is short-lived and users tend
to expect fast loading times, even if this is not really relevant.  This is especially
important for a first impression.  That's why build scripts should also be able to
load quickly.  This means that using JVM Clojure is not well suited, and we try to
use Babashka as much as possible.  This does limit the range of possibilities, but it
should be enough for 95% of the build scripts.  Only when users want to include very
specific libraries that are not Babashka-compatible should we have to fall back to
JVM Clojure.

This also means that the code in this library must be compatible with Babashka and
pull in as few dependencies as possible.  Most of this used to live in the main
[app](../app) project, but it has been extracted just for this reason.

## LICENSE

[GPL v3](../LICENSE)

Copyright (c) 2023-2026 by Monkey Projects BV

[https://www.monkey-projects.be](https://www.monkey-projects.be)

[https://www.monkeyci.com](https://www.monkeyci.com)

