# Webhook Handler Function

This directory contains the code for handling an incoming webhook.  This is installed
as a [Google Cloud Function](https://cloud.google.com/functions).  The code itself
is written in ClojureScript and should be build with [shadow-cljs](https://shadow-cljs.github.io/docs/).

## Compiling

To compile, first install the necessary dependencies:
```bash
$ npm install
```
Then compile using `npx shadow-cljs`:
```bash
$ npx shadow-cljs compile hook
```

## Running

You can run it either in the cloud, or locally (for testing):
```bash
$ npm run
```
This will run the `functions-framework` locally and set up a webserver at `http://localhost:8080`.

## Releasing

To build a release, run:
```bash
$ npx shadow-cljs release hook
```
This will create the compiled files in the `compiled` folder.  After that, run the Terraform
script from [infra](../infra).

## Testing

You can run the unit tests like this:
```bash
$ npx shadow-cljs watch tdd
```
This will compile and run the tests in a loop.  The tests are re-run on each code change.

Or you can run the tests once (for instance, when in the CI pipeline):
```bash
$ npx shadow-cljs compile test && node out/node-tests.js
```
This will compile the code, including the tests, and then run them using `node`.  The exit
code will be nonzero when there are failures.

## Deploying to GCP ##

In order to deploy the function to the Cloud, it depends on whether this is the first
time or not.  The first time, run the Terraform script in the [infra](../infra) dir:
```bash
$ terraform apply
```

For future deployments, use `gcloud`:
```bash
$ gcloud function deploy webhook-trigger --gen2
```

Note that this will assume the code to deploy is in the current directory.  You should create
a separate `deploy/` folder and copy the required files to it, and run the above command from
there.  Don't forget to do a release build first, otherwise you'll get errors about it not
finding the `.shadow-cljs` directory.

## License

Copyright (c) by Monkey Projects BV.