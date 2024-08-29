(ns monkey.ci.protocols
  "Contains all (or most of) the protocols used in the app.  This is useful
   to avoid circular dependencies.")

(defprotocol Storage
  "Low level storage protocol, that basically allows to store and retrieve
   information by location (aka storage id or sid)."
  (read-obj [this sid] "Read object at given location")
  (write-obj [this sid obj] "Writes object to location")
  (delete-obj [this sid] "Deletes object at location")
  (obj-exists? [this sid] "Checks if object at location exists")
  (list-obj [this sid] "Lists objects at given location"))

(defprotocol JobResolvable
  "Able to resolve into jobs (zero or more)"
  (resolve-jobs [x rt]))

(defprotocol EventBuilder
  "Used to construct an event from an object"
  (->event [this event-type]))

(defprotocol EventPoster
  (post-events [poster evt]
    "Posts one or more events.  Returns a deferred that realizes when the events have been posted."))

(defprotocol EventReceiver
  (add-listener [recv ef l] "Add the given filter with a listener to the receiver")
  (remove-listener [recv ef l] "Removes the listener for the filter from the receiver"))

(defprotocol BlobStore
  "Protocol for blob store abstraction, used to save and compress files or directories
   to some blob store, possibly remote."
  (save-blob [store src dest] "Saves `src` file or directory to `dest` as a blob")
  (restore-blob [store src dest] "Restores `src` to local `dest`")
  (get-blob-stream [store src] "Gets a blob file as an `InputStream`")
  (put-blob-stream [store src dest] "Saves a raw stream to the blob store"))

(def blob-store? (partial satisfies? BlobStore))

(defprotocol ContainerRunner
  (run-container [this job]
    "Runs the given container job.  Returns a deferred that will hold the result."))

(defprotocol Workspace
  (restore-workspace [this]
    "Restores the workspace associated with the current build"))
