# Security

Security is of course a very important aspect of the application.  It will
be necessary to identify users for billing purposes, but also to avoid
exposing sensitive information to parties that should not see it.

Ideally, we could use existing infrastructure so that we would not have
to re-invent the wheel, but also because then we could use tried-and-true
software.  This reduces the risk of vulnerabilities.

## Accounts

For now, we do not manage account information of our own.  Instead, we
delegate this functionality to existing platforms.  In this case this is
Github and Bitbucket, which already offer user management and provide Oauth2
authentication.  MonkeyCI only keeps track of the user type (which provider)
and the type-specific ID.  In case of Github, the type is `github` and the
type id is the numerical Github user id.  For Bitbucket, the type is `bitbucket`
and the id is the Bitbucket user uuid.

## Sensitive Data

Sensitive data includes ssh keys and build parameters.  We keep these encrypted
in the database, but at one point they will need to be decrypted to be passed
to the jobs, either as parameters (in action jobs) or environment variables
(for container jobs).

We do not want to transmit sensitive information unencrypted, e.g. in events.
Therefore, we need to encrypt this data but we also have to allow the event
receivers to decrypt it when necessary.  To this end we use Data Encryption
Keys (DEKs).  The cloud provider offers a key service, which both allows us
to generate new DEKs as needed, but also encrypts them.  The original encryption
key is not known to MonkeyCI, it remains in the key service.

### Data in Motion

Whenever a new build is started, a build-specific DEK is generated, and
added to the `build/initialized` event in encrypted form.  The build controller
is then able to ask the API to decrypt the key.  This way, we have a double
protection layer: should the event be intercepted, the key is still useless
because it's encrypted.  The attacker would also need to have a token to call
the API to decrypt the key.

Whenever a build adds sensitive data to events, e.g. when starting a container
job that has environment variables, the build DEK is used to encrypt this
information.  The job controller in turn is also aware of the build DEK and
can also contact the API to decrypt it.  It will then use the DEK to decrypt
environment variables before passing them to the container itself.

When using ssh keys to check out a private repository, the ssh keys are fetched
directly from the database using the API, which is using TLS so it's already
secure.  Similarly, the build script retrieves the parameters using an API
call.  But when a container job is started, the environment variables are
included in the event, so they should be re-encrypted at this point, only
to be decrypted when they are passed to the container.

### Data at Rest

Similarly, we do not store sensitive data unencrypted in the database.  Here
we also have a double security layer.  For each organization, MonkeyCI generates
a new DEK, which is encrypted using the cloud key platform.  The encrypted DEK
is stored along with other organization information in the database.  This DEK
is in turn used to encrypt any newly generated build-specific DEKs.  Whenever
a client uses the API to decrypt a DEK, it needs to specify the organization id,
which is then used to retrieve the correct DEK for decryption.

In order to store ssh keys and parameters in the database, each parameter or ssh
key set has its own DEK, which is again encrypted using the organization DEK.
This is an additional security layer: it becomes impossible to compare encrypted
values because they are all encrypted using their own DEK.

## Conclusion

All the above security measures combined should be a pretty secure defense
against any attackers and should guarantee that the user's sensitive information
is not exposed.  It is of course up to the user to limit this information as
much as possible and not expose it him/herself, e.g. by printing it in the logs
or something like that.
