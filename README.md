# README #

Monkey CI is intended to be a highly customizable and powerful continuous integration
tool.  The key features are:
- Modular and maintainable design.
- Highly testable (written using TDD), both for the codebase and for the build scripts.
- Powerful if necessary, but user friendly for the less-technical.
- Try to include the best features of currently existing CI/CD tools.
- Pay per use: every feature used will be paid for, but there is a "free budget".

It is (very remotely) inspired by [Lambda-CD](https://www.lambda.cd), but I did not
use any of their code.  Only the part where you use [Clojure](https://clojure.org) as
the build script language ;-).

### Why? ###

Why build another CI tool is so many already exist?  Well, throughout my career I've
used quite a few: Jenkins, Travis, GCP build, Bitbucket, Gitlab, Teamcity, Tekton...
All have their strong points, and all have their weak points.  But almost all have the
same issue: their scripting is not powerful enough for my needs.  And since my pipelines
are not _that_ special (I think), I can assume that many people experience the same
problem.
They are great for demo cases and very simple builds, but as soon as you go past the
elementary and want to set up pipelines with even moderate complexity, and want to
reduce copy/pasting as much as possible, you end up in trouble.  And since CI tools
should be the forerunners in "good coding practices", I should expect no less from the
build scripts they make us write, no?

The core problem, as I see it, is that they all work with Yaml files, probably to make
it more user-friendly.  This is great for novice users with simple builds, but as said
before, you quickly run into limitations.  Then you're forced to use Yaml anchors and
whatnot.  And those have limitations too.  And apart from that you're also often forced
to insert complicated bash scripting to solve some specific problems.  So after a while
it becomes a program of it's own.  And in my view, programs require programming
languages!

I'm a big fan of Lisp-like languages in general, and Clojure in particular.  Mostly
because of it's elegance and simple syntax.  And Clojure nicely bridges the gap with
the "real world": you can write in an elegant language _and_ your code is actually
usable!  And that's also why I think it could be a nice fit for some kind of scripting
(see also [Babashka](https://github.com/babashka/babashka)).  You don't need complicated
syntax and directory structures, a Clojure program can be as simple as a single file.

So that's the aim here: to provide the full power of Clojure to build pipelines, and
also introduce coding best-practices to the build process.  By that I mean things like
unit testing.  Wouldn't it be great if you could unit-test your build pipelines?  If
you could run it locally to see if everything works, that you don't need to do any
debugging in the CI environment?

### Things I like ###

I intend to incorporate as much features as possible that I like in other CI tools.
These include:

 - The speed of CircleCI
 - Easy to use caching support of Gitlab
 - Named pipelines of BitBucket
 - Nice test and coverage reporting of TeamCity
 - ...

Also, to make migration easier, it would be cool to add support for the build scripts
of other CI tools.  If that is even feasibly.  Or, alternatively, some way to convert
scripts into the MonkeyCI format.  In any case it should be avoided that users need
to rewrite all their build scripts if they want to migrate.

### Features I'm missing in other tools

Of course, I wouldn't be building this if I saw that the existing tooling provided me
with everything I need or want.  So what I want to add:

 - Ability to unit test your pipeline (simulate scenarios)
 - Run it locally (e.g. using a local dev server)
 - Compatibility with other tools (can read or generate scripts from existing tools)

### What is this repository for? ###

This repository contains the source code for the Monkey CI application.  It consists of
the build application that actually runs the script and  the infra project that holds the
Terraform configuration for the cloud configuration.

The application is written in Clojure and is set up in a modular way.  Communication
between the modules is done using events (probably using [Kafka](https://kafka.apache.org/),
or maybe [Artemis](https://activemq.apache.org/components/artemis/)).

### How do I get set up? ###

* Install [Clojure tools](https://clojure.org/guides/deps_and_cli)
* Running tests once: `clojure -M:test`
* Running tests continuously: `clojure -M:test:watch`
* Get coverage: `clojure -M:test:coverage`
* Running the app: `clojure -M:run`

The test runner used is [Kaocha](https://github.com/lambdaisland/kaocha), which
runs the `clojure.test` tests.  Depending on the aliases you include, plugins are
activated or not (see the [deps.edn](builder/deps.edn) file).

#### Building ####

Initially we're using [CircleCI](https://circleci.com) to build the app, but as Monkey-CI
matures, it will be built "by itself" as it were.

### More Details ###

* For the general design, see [the design page](docs/design.md).  As we go along, this will probably evolve.

### Contribution guidelines ###

* All code must be written in [TDD fashion](https://en.wikipedia.org/wiki/Test-driven_development).
* Allo code must reviewed by at least two other coworkers, assuming there are coworkers.

### Who do I talk to? ###

* wout.neirynck@monkey-projects.be is the (initial?) developer and designer

### License ###

Copyright (c) 2023 by Monkey Projects BV

[https://www.monkey-projects.be](https://www.monkey-projects.be)

[https://www.monkey-ci.com](https://www.monkey-ci.com)
