(ns monkey.ci.blob
  "Blob storage functionality, used to store and restore large files
   or entire directories."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clompress
             [archivers :as ca]
             [compression :as cc]]
            [monkey.ci.utils :as u])
  (:import [java.io BufferedInputStream PipedInputStream PipedOutputStream]
           [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(defprotocol BlobStore
  "Protocol for blob store abstraction, used to save and compress files or directories
   to some blob store, possibly remote."
  (save [store src dest] "Saves `src` file or directory to `dest` as a blob")
  (restore [store src dest] "Restores `src` to local `dest`"))

(defmulti make-blob-store (comp :type :blob))

(def compression-type "gz")
(def archive-type "tar")

(def stream-factory (ArchiveStreamFactory.))

(defn- mkdirs! [f]
  (if (fs/exists? f)
    (when-not (.isDirectory f)
      (throw (ex-info "Directory cannot be created, already exists as a file" {:dir f})))
    (when-not (.mkdirs f)
      (throw (ex-info "Unable to create directory" {:dir f}))))
  f)

(defn- next-entry
  "Gets the next entry from the stream.  Due to the nature of piped streams,
   this may throw an exception when the write end is closed.  In that case, 
   we return `nil`, indicating we're at EOF."
  [ai]
  (try
    (.getNextEntry ai)
    (catch java.io.IOException ex
      (when-not (= "Write end dead" (.getMessage ex))
        ;; Some other i/o exception, rethrow it
        (throw ex)))))

(defn- extract-entry [ai e dest]
  (log/debug "Extracting entry from archive:" (.getName e))
  (let [f (io/file dest (.getName e))]
    (cond
      (.isDirectory e)
      (mkdirs! f)
      
      (.isFile e)
      (let [p (mkdirs! (.getParentFile f))]
        (with-open [os (io/output-stream f)]
          (io/copy ai os)))

      :else
      (log/warn "Unsupported archive entry:" e))))

(defn- extract-archive
  "Extraction is not supported by the lib.  This takes an input stream
   (possibly from a decompression) and a destination directory.  Returns
   the destination directory."
  [is dest]
  (with-open [ai (.createArchiveInputStream stream-factory ArchiveStreamFactory/TAR is)]
    (loop [e (next-entry ai)]
      (if e
        (do
          (if (.canReadEntryData ai e)
            (extract-entry ai e dest)
            (log/warn "Unable to read entry data:" (.getName e)))
          ;; Go to next entry
          (recur (next-entry ai)))
        ;; Done
        dest))))

(defn- drop-prefix-resolver
  "The default entry name resolver includes the full path to the file.  
   We only want the file name without the base directory, so that's what
   this resolver does."
  [base-dir path]
  ;; Skip the /
  (subs path (inc (count base-dir))))

(deftype DiskBlobStore [dir]
  BlobStore
  (save [_ src dest]
    (let [f (io/file dir dest)
          prefix (fs/file
                  (if (fs/directory? src)
                    src
                    (fs/parent src)))]
      (mkdirs! (.getParentFile f))
      (ca/archive
       {:output-stream (io/output-stream f)
        :compression compression-type
        :archive-type archive-type
        :entry-name-resolver (partial drop-prefix-resolver prefix)}
       (u/abs-path src))
      ;; Return destination path
      (u/abs-path f)))

  (restore [_ src dest]
    (let [f (io/file dest)
          os (PipedOutputStream.)]
      (with-open [is (BufferedInputStream. (PipedInputStream. os))]
        (mkdirs! f)
        ;; Decompress to the output stream
        (doto (Thread. #(cc/decompress
                         (io/input-stream (io/file dir src))
                         os
                         compression-type))
          (.start))
        ;; Unarchive
        (extract-archive is f)))))

(defmethod make-blob-store :disk [conf]
  (->DiskBlobStore (get-in conf [:blob :dir])))
