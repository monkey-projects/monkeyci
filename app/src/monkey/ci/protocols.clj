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

(defprotocol Transactable
  "Storage implementations that support transactions, should implement this"
  (transact [this f] "Executes `f` within a transaction, which is passed to `f`"))

(defprotocol JobResolvable
  "Able to resolve into jobs (zero or more)"
  (resolve-jobs [x rt]))

(defprotocol BlobStore
  "Protocol for blob store abstraction, used to save and compress files or directories
   to some blob store, possibly remote."
  (save-blob [store src dest md] "Saves `src` file or directory to `dest` as a blob")
  (restore-blob [store src dest] "Restores `src` to local `dest`")
  (get-blob-stream [store src] "Gets a blob file as an `InputStream`")
  (put-blob-stream [store src dest] "Saves a raw stream to the blob store")
  (get-blob-info [store src] "Gets details about a stored blob"))

(def blob-store? (partial satisfies? BlobStore))

(defprotocol Workspace
  (restore-workspace [this sid]
    "Restores the workspace associated with the given build sid"))

(def workspace? (partial satisfies? Workspace))

(defprotocol BuildParams
  (get-build-params [this build]
    "Retrieves build parameters for this build"))

(defprotocol Vault
  (encrypt [this iv txt] "Encrypts given text using initialization vector, returns base64 string")
  (decrypt [this iv obj] "Decrypts given data using initialization vector"))

(def vault? (partial satisfies? Vault))

(defprotocol ArtifactRepository
  (restore-artifact [this sid id dest]
    "Downloads and extracts artifact with given build sid and artifact id to 
     the specified destination directory.  Returns the destination.")
  (save-artifact [this sid id src]
    "Creates an archive and uploads the artifact with given id from `src`, which 
     can be a directory or file."))

(defn repo? [x]
  (satisfies? ArtifactRepository x))

(defprotocol AddRouter
  (add-router [broker routes opts] "Registers a listener for given routes in the broker"))
