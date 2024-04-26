(ns monkey.ci.build.archive
  "Functions for working with downloaded archives"
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [monkey.ci.utils :as u])
  (:import [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(def stream-factory (ArchiveStreamFactory.))

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
  (log/trace "Extracting entry from archive:" (.getName e))
  (let [f (io/file dest (.getName e))]
    (cond
      (.isDirectory e)
      (u/mkdirs! f)
      
      (.isFile e)
      (let [p (u/mkdirs! (.getParentFile f))]
        (with-open [os (io/output-stream f)]
          (io/copy ai os)))

      :else
      (log/warn "Unsupported archive entry:" e))))

(defn- archive-stream [is]
  (.createArchiveInputStream stream-factory ArchiveStreamFactory/TAR is))

(defn extract
  "Allows extracting an archive input stream (like a downloaded artifact) 
   into a destination location.  If a regular expression is given as third
   argument, only the files that match the regex are extracted."
  [is dest & [re]]
  (log/debug "Extracting archive into" dest)
  (with-open [ai (archive-stream is)]
    (loop [e (next-entry ai)
           entries []]
      (if e
        (do
          (if (.canReadEntryData ai e)
            (extract-entry ai e dest)
            (log/warn "Unable to read entry data:" (.getName e)))
          ;; Go to next entry
          (recur (next-entry ai)
                 (conj entries e)))
        ;; Done
        {:dest dest
         :entries entries}))))

(defn list-files
  "Lists files in the archive at given path"
  [arch]
  (with-open [is (io/input-stream arch)
              ai (archive-stream is)]
    (loop [e (next-entry ai)
           entries []]
      (log/debug "Entry:" e)
      (if e
        ;; Go to next entry
        (recur (next-entry ai)
               (conj entries e))
        ;; Done
        entries))))

;; TODO Add functions to:
;;  - extract a subset of files
;;  - extract a single file to string
