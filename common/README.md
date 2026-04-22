# MonkeyCI Common

This lib provides common functionality that is shared between the backend and frontend.
So it's all ClojureC, so we can also refer to it from ClojureScript code.  It's not meant
to be used independently, we only refer to it using `:local/root` from other `deps.edn`
files.  This means it's never published as a standalone lib (even though we could).

## Contents

What does this lib provide?

 - Constants
 - Schemas
 - Specs
 - ...
