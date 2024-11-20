# Running a Build

In order to run a single build, the following steps must be executed:

  1. Trigger the build, either via API or CLI
  2. Start the build runner
  3. Check out the related code
  4. Load the script in child process
  5. Execute script jobs
  6. Report the result

The child process that runs the script and any jobs that are the result
of it should be considered "untrusted".  To this end, the current implementation
of running it in a child process is useful because it allows us to shield any
sensitive data from the build script.  In the future, it may be possible to
directly run the build script in a container instance without the need to a
parent "controller" process.  But for the time being, we will stick with the
controller/child system.

## API Access

The controller will start an API HTTP server that is meant only to be accessed
by the build script.  The build script will receive the url to access this API
along with a token it can use for authentication purposes.  The token is signed
using a randomly generated key so it can only be used for that controller.  As
an additional security measure, we can set up a network security group for that
specific build, so the build script can only access the controller and the
internet, and no other internal addresses.  The build script can only access
services from *MonkeyCI* through this API, apart from the data it receives at
startup.  This means the following actions:

  - Sending (and receiving) events
  - Fetching build parameters
  - Retrieving and storing artifacts and caches
  - Starting container jobs

Details about the build itself, like git details,  will be passed on via a
configuration file on the command line.  We could consider passing in the
parameters this way too, but since we don't know in advance if they will be
needed, it's more efficient to wait until they are requested.  Action jobs
are invoked in the process itself.  Build progress is reported through events.

For cloud runs, the API will be accessed over TCP, so the build will need to
know which port it can use.  For local runs, a Unix domain socket (UDS) will
be used.  Both URI's will be passed on to the child process in the same fashion.

## Container Jobs

Container jobs are run by the controller, normally using container instances.
A limitation here is that we need to run a sidecar to prepare the workspace
and any caches and artifacts.  And since we can't run one container before the
other (similar to init containers in Kubernetes), we use scripting to let the
job "wait" until everything is ready for it.  This means the job container must
have a shell available.  If there is no shell, the container either cannot
access the workspace, or we would have to run it in another way.

So the sidecar also needs to access certain *MonkeyCI* services, similar to the
build script.  In addition to those specified above, it will also need to be
able to access the following:

  - Retrieve the workspace
  - Upload logs

This means the API access information will also have to passed to the sidecar
by the controller.

Additional services required for testing (e.g. a database server) can also be
started this way.  Since we don't have to capture any information for these
services, we can just start them as container instances without a sidecar.
