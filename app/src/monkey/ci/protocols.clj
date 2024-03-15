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
  (post-events [poster evt] "Posts one or more events"))

(defprotocol EventReceiver
  (add-listener [recv l] "Add the given listener to the receiver")
  (remove-listener [recv l] "Removes the listener from the receiver"))

