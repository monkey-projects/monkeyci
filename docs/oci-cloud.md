# Running On Oracle Cloud

To run the application serverless on [Oracle Cloud](https://cloud.oracle.com),
we would need the following services:

 - [API gateway](https://docs.oracle.com/en-us/iaas/Content/APIGateway/home.htm), to receive the incoming webhooks.
 - [Cloud functions](https://docs.oracle.com/en-us/iaas/Content/Functions/home.htm), to handle the incoming webhook call and bootstrap the build.
 - [Container instances](https://docs.oracle.com/en-us/iaas/Content/container-instances/home.htm), to run the build job.

## Running the Build

The cloud function would have to return quickly in order to avoid a timeout on
the webhook caller's end.  For this, we could either run it in a new function,
or start a container instance.  It would depend on the price which one to use.

The build pipeline would then run each step in a new container instance.  The
issue to solve here is storage.  OCI container instances only support `EmptyDir`
volumes, so we can't mount the source repository in it.  If possible, we should
first fill it using an `initContainer`, but the current implementation does
not seem to support that, although it does support multiple containers.  A possible
workaround could be to run a script that waits for the repository to have filled
(along with any required artifacts from previous steps) using some sort of trigger
(e.g. a lock file).  This does mean that we would spend unnecessary ocpu cycles,
but we would have to investigate whether the billing is counted towards the entire
container instance, or each container separately.  I assume the former, if I read
the [pricing page](https://www.oracle.com/cloud/cloud-native/container-instances/pricing/?source=:ow:o:h:nav:092121OCISiteFooter) correctly.
Alternatively it's also possible to mount configfiles as a volume.  We could
prepare the source information as a tarball and then inject it as a config file.
Docs don't specify a max size, and it's not sure this will be cheaper though.  This
would have to be investigated.

We would also use the [events API](https://docs.oracle.com/en-us/iaas/Content/Events/Concepts/eventsoverview.htm#Overview_of_Events) to react to the completion of a job.  In order to capture any
artifacts, we would also require some sort of "finish container", that runs when
the main build step is finished.  This would possibly also require a similar
trigger mechanism.

## Events

We could use [OCI queue](https://docs.oracle.com/en-us/iaas/Content/queue/home.htm)
to dispatch events between the various parts of the application.  Note that this
is only useful if you have a process that continuously polls the queue, or
periodically pulls in any queued up messages.  We could also use [streaming](https://docs.oracle.com/en-us/iaas/Content/Streaming/home.htm),
which would allow to execute functions on incoming events.  [Pricing](https://www.oracle.com/cloud/price-list/?source=:ow:o:h:nav:092121OCISiteFooter#streaming)
for this is dependent on the amount of stored events and the number of operations.