# MonkeyCI Core

This lib contains all "core" functionality for MonkeyCI.  It is referred by the
main [application codebase](../app) (in `/app`) but also by extensions (in `/ext`).  It
is *not* referred by the frontend code.  This means the code here is all pure
Clojure code, but we do take care to avoid reflection so we can also include
it in a native image using GraalVM (for the [CLI](../cli)).

Similar to the [common](../common) lib, we don't publish this as a standalone
lib, instead we refer to it using `:local/root`.
