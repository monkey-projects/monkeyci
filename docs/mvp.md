# MonkeyCI MVP

Initially we would need to build a _minimal viable product_ (MVP).  Here we
define what would have to be in that MVP.

1. Run a basic build script.  Basic means a single flow, no branching.
2. Support artifacts.
3. Able to pull code from Github or Bitbucket (whichever proves to be easiest).
4. Report the results somewhere.
5. Run the application in OCI.

As a rough estimate I think this can be done in 3 man-months.

## Future Steps

After that, we would need a basic interface.  A CLI seems most appropriate.
But it could be useful to allow some kind of events.  Or maybe a compatibility
with existing CI reporting tools (e.g. to display in the application bar).

Next up would be a basic web user interface.  We would need some kind of
design for this.  Preferably this is done by a professional designer.

## Approach

Instead of taking one step (or "module") at a time and fleshing it out completely,
I will do smaller steps on each front.  This is because I want to reach a full
end-to-end functionality as soon as possible, and also because building one
thing completely very often results in building the wrong thing.