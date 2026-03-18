# MonkeyCI Example: Artifacts

This build contains 3 jobs:

 - `create-artifact`: an action job that creates a new artifact and saves it (using `save-artifacts`)
 - `use-artifact`: a job depending on `create-artifact` that restores and uses the artifact.
 - `cleanup`: deletes the artifact file if it exists.  It shouldn't because it has not been restored, but this may be true when running the build locally.

## LICENSE

Copyright (c) 2024-2026 by Monkey Projects
