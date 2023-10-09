# Monkey CI Design #

The application consists initially only out of the build module.  It is responsible
for checking for modifications in source repositories and running the build scripts
on changes.

In a nutshell:

  1. Get triggered by an event.
  2. Check out the related code.
  3. Execute the script, located in `.monkeyci/` directory.
  4. Report the results (publish an event).

## Events ##

Communication of the runner with the outside world is done using events as much
as possible.  Incoming webhook endpoints should be served by a separate module,
which in turn publishes an event.  This allows for a very modular and scalable
design.

So triggering a build can be done by a poller that checks if there were any changes
in a source repo, or a webhook, or a timer, or a manual trigger.

In the code, we could model these events using
[core.async channels](https://clojure.github.io/core.async/index.html).  These
can then either communicate internally, when the modules are running inside the
same process.  Or they could use a JMS implementation like [Artemis](https://activemq.apache.org/components/artemis/documentation/),
or use some form of [cloud queues](https://docs.oracle.com/en-us/iaas/Content/queue/home.htm).
An extra advantage of this approach would be that the code could be made a lot more
flexible, and testable.  Instead of having to mock out entire parts, we would just
inspect the messages that are posted on certain channels.  We could also log the
messages that pass through channels to improve monitoring.
A drawback is the possible complexity, so it's important to keep this to a minimum.
After some experimenting, it became clear that although events have their merits,
they should not be overused.  The increased flexibility does not always offset the
added complexity.  So for most parts of the application, we're using higher-order
functions that use the app configuration to spawn other functions that do the real
work.  This allows us to "inject" testing functions where wanted.  But events will
still be used for the async operations, like waiting for a child process to terminate
and receiving any messages from it.

## Scripts ##

The build scripts should be powerful.  All tools I've used up until now used either
a UI (TeamCity) or some sort of Yaml file (CircleCI, Bitbucket, Jenkins, TravisCI,
Gitlab, Google Build,...) as their build configuration.  This is great for very
simple builds, but you quickly need more.  I've had to do some weird bash-scripting-
in-yaml many times, with lots of headaches about escaping etc. just to do something
that seemed very basic to me.

The intention is to provide the full power of Clojure to those that want to use it.
But for those that don't need it, it should be possible to use Yaml or Json (or Edn)
configuration instead.  This would then be converted into a Clojure script under the
hood.  Ideally it should even be possible to process scripts of other CI tools!

How should scripts be run?  I would like to reuse the Clojure tools `deps.edn` as
much as possible.  So essentially it would be no more than running that build in
a separate container or process.

## Plugins ##

The Clojure-as-script concept should also allow builders to easily create plugins that
can then be used in their scripts (or even by other people).  It should be as simple
as declaring a dependency in your script configuration and invoking the necessary
functions.

## Execution Environments ##

Most CI tools use some sort of containers to run their scripts.  Monkey-CI would so the
same, because it is the safest way and it avoids all sorts of issues with conflicting
libraries.  So we could have agents that in turn run the build scripts, depending
on their configuration.  The following execution environments could exist:

- Local (i.e. just executing on the same machine)
- Docker/Podman (running the script in a separate image)
- Kubernetes/Nomad (creating a job in an orchestrator that runs the script)
- Cloud functions/cloud runs

The modular nature of the design should allow for more environments should the need
arise.

### Cloud Functions

To save money (initially), we can use cloud functions (provided by [GCP](https://console.cloud.google.com/run),
[OCI](https://docs.oracle.com/en-us/iaas/Content/Functions/Concepts/functionsoverview.htm), etc...).
They can be triggered by a webhook (for example from [Bitbucket](https://support.atlassian.com/bitbucket-cloud/docs/manage-webhooks/))
which are invoked on each push to a repository.  The function will then start a container
that runs the MonkeyCI application that in turn checks out the changes and runs
the build script.  A possible problem here could be timeout: if the function performs
the build directly, it may cause a timeout on the side of the webhook caller.  In this case
we should find a way to let the function react to an event (that is fired by the webhook)
instead.  Similarly, functions themselves have a limited execution time.  Currently this
is 5 minutes on OCI, which will probably be too short for many build jobs.  In that case,
we'd have to resort to cloud runs (see below).

### Cloud Run

A variation of the above would be to use [Cloud Run](https://cloud.google.com/run/docs/overview/what-is-cloud-run).
The core app could run as a Cloud run service, with an HTTP endpoint that receives the
webhook requests.  When a push to a repository occurs, it will create another Cloud run
job to check out the code and run the script.  This script run will in turn create other
jobs to execute each step.  For the initial receiving of the webhook we could also use
a cloud function.  The difference is that with Cloud run you start a container, which could
be slower, so a function is probably a better choice.  In short, this is the sequence of events:

1. Function receives incoming webhook request.
2. A cloud run job is created that checks out the code.
3. The script in the repo is executed.
4. Each step could lead to another job being started.
5. A job publishes events.
6. Another event is published when the script is completed (or fails).

Since steps could be dependent on each other we should be able to react to job events.
Either if it fails, or if it succeeds.  This is probably possible using Pub/Sub.

Should the number of build requests increase, we can move to a regular (long-running) HTTP server.

Most public clouds provide the same functionality.  See [OCI cloud run](oci-cloud.md)
for more details on how to run this on [Oracle Cloud](https://cloud.oracle.com).

## Storage ##

See the [storage subsection](storage.md) for details.

## Testability ##

One of the big pains of most build tools is that it's difficult to resolve any problems
with the build itself.  Some tools are better than others, for instance CircleCI allows
ssh-ing into the build when it runs.  But mostly you have no option but to add lots
of `echo` statements in your script (thereby often exposing sensitive information).
Monkey-CI aims to provide some sort of testability.  Since build scripts are small Clojure
programs, it should be possible to unit-test them, or to run some sort of integration
test on the script code.  This should allow developers to easily figure out any issues,
or even better, be sure that their script will work on the first run!

The build script runner could auto-run the build script tests when they are found, and
abort the build if they fail.  In order to test the script locally, a library could be
provided as a drop-in replacement for the "real" implementation, but it returns mock
data.

## Interface ##

Interfacing with the app should be possible with a CLI, HTTP API and a user interface.
Ideally, these are all event-driven.  Well, only the API needs to be, the other parts
could talk to the API in turn.  It could also have a [Terraform](https://terraform.io)
integration.  A Slack app would also be nice.