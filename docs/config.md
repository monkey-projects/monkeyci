# Application Configuration

The application configuration can be quite extensive.  Because _MonkeyCI_ is meant
to be used locally as well as as a managed service, many parts of the application
should be made configurable.  The configuration can be passed in on several levels.
These are, where later levels have priority over earlier:

 1. Default (fallback) configuration, used when no other is available.
 2. Global configuration, found in `/etc/monkeyci/config.edn`.
 3. User configuration, read from `~/.monkeyci/config.edn`.
 4. Environment variables.
 5. Command-line arguments.

Not all properties can be configured on the command-line, but the environment
and files support all of them.

## Global and user config

On the system and user level, configuration is located in an [edn file](https://github.com/edn-format/edn).
`Json` could also be supported, but for now the default is `edn`.  This is because `edn`
is Clojure-based, so it is more in-line with the inner application workings.  But also
because `edn` is more concise as `json`, you don't have to type as many double-quotes.

The configuration is hierarchical, like this:

```clojure
{:http
 ;; Overwrite the http port for the web/api server
 {:port 3000}}
```

## Environment Variables

Environment variables are also used to specify configuration.  These are read from all
env vars that start with `MONKEYCI_` and are then internally mapped to the hierarchical
structure.  For example, to overwrite the http port in the environment, you'd do this:

```bash
$ export MONKEYCI_HTTP_PORT=3000
```

## Command-line

The final possible way to set configuration is on the command line.  This also has the
highest priority, so it will override any previous config.  Just run `monkeyci -h` to
see all configuration parameters.  They also depend on the command that's being issued.
To override the http port on command line, you would do this:

```bash
$ monkeyci server -p 3000
```
