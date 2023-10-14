# Security

Security is of course a very important aspect of the application.  It will
be necessary to identify users for billing purposes, but also to avoid
exposing sensitive information to parties that should not see it.

Ideally, we could use existing infrastructure so that we would not have
to re-invent the wheel, but also because then we could use tried-and-true
software.  This reduces the risk of vulnerabilities.

[Oracle IDCS](https://docs.oracle.com/en/cloud/paas/identity-cloud/index.html)
is offered by OCI for this.  We could manage users with this platform.
It is also integrated with the [API gateway](https://docs.oracle.com/en-us/iaas/Content/APIGateway/home.htm),
so we could avoid having to implement security in our own application (or
at least a large part of it).