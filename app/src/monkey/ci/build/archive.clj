(ns monkey.ci.build.archive
  "Functions for working with downloaded archives"
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clompress.compression :as cc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.utils :as u])
  (:import [java.io BufferedInputStream PipedInputStream PipedOutputStream]
           [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(def stream-factory (ArchiveStreamFactory.))
(def compression-type "gz")

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

(defn- decompress
  "Decompresses a source file.  Returns an input stream that will contain the
   decompressed archive."
  [src]
  (let [os (PipedOutputStream.)
        is (BufferedInputStream. (PipedInputStream. os))]
    ;; Decompress to the output stream
    (doto (Thread. (fn []
                     (try 
                       (cc/decompress
                        (io/input-stream src)
                        os
                        compression-type)
                       (catch Exception ex
                         (log/error "Unable to decompress archive" ex))
                       (finally
                         (.close os)))))
      (.start))
    is))

(defn- extract-loop [ai pred f]
  (loop [e (next-entry ai)
         entries []]
    (if e
      (let [p? (pred (.getName e))]
        (when p?
          (f e))
        ;; Go to next entry
        (recur (next-entry ai)
               (cond-> entries
                 (and p? (not (.isDirectory e)))
                 (conj (.getName e)))))
      ;; Done
      entries)))

(defn- unarchive
  "Unarchives the given (uncompressed) input stream to the given output location.
   `dest` is supposed to be a directory where the files can be written to.  Only
   files matching the given predicate will be unarchived.  Returns a map that
   contains the destination directory and the names of the extracted entries."
  [is dest pred]
  (log/debug "Extracting archive into" dest)
  (.mkdirs dest)
  (with-open [ai (archive-stream is)]
    {:entries (extract-loop ai
                            pred
                            (fn [e]
                              (if (.canReadEntryData ai e)
                                (extract-entry ai e dest)
                                (log/warn "Unable to read entry data:" (.getName e)))))
     :dest dest}))

(defn extract
  "Allows extracting an archive input stream (like a downloaded artifact) 
   into a destination location.  If a regular expression is given as third
   argument, only the files that match the regex are extracted."
  [is dest & [re]]
  (with-open [ds (decompress is)]
    (unarchive ds dest (if re
                         (bc/->pred re)
                         (constantly true)))))

(defn list-files
  "Lists files in the archive at given path"
  [arch]
  (with-open [is (io/input-stream arch)
              ds (decompress is)
              ai (archive-stream ds)]
    (extract-loop ai (constantly true) (constantly nil))))

(defn extract+read
  "Extracts the given source archive, and returns the contents of the first
   file that matches predicate `pred`, or `nil` if there were no matches."
  [src pred]
  (with-open [dc (decompress src)
              ai (archive-stream dc)]
    (let [p (bc/->pred pred)]
      (loop [e (next-entry ai)]
        (if e
          (if (and (.isFile e) (p (.getName e)))
            ;; Found match
            (slurp ai)
            ;; Go to next entry
            (recur (next-entry ai)))
          ;; Done without match
          nil)))))
