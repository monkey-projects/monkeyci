(ns ^:no-doc monkey.ci.build.archive
  "Functions for working with downloaded archives"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clompress
             [compression :as cc]
             [unarchivers :as cu]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.utils :as u])
  (:import [java.nio.file.attribute PosixFilePermission]
           [org.apache.commons.compress.archivers ArchiveStreamFactory]))

(def compression-type "gz")
(def archive-type "tar")

(defn- decompress
  "Decompresses a source file.  Returns an input stream that will contain the
   decompressed archive."
  [src]
  (cc/with-decompression src compression-type))

(defn archive-stream [i]
  (cu/make-unarchiver i archive-type))

(defn extract
  "Allows extracting an archive input stream (like a downloaded artifact) 
   into a destination location.  If a regular expression is given as third
   argument, only the files that match the regex are extracted.  Closes the
   input stream."
  [src dest & [re]]
  ;; FIXME When the archive only contains one file, and it's name is the same name as
  ;; the archive file (without tgz extension), it should be extracted in the same
  ;; location, and not in a subdirectory.
  (with-open [in (io/input-stream src)]
    {:entries (->> (cu/unarchive (cond-> {:input-stream src
                                          :archive-type archive-type
                                          :compression compression-type}
                                   re (assoc :filter-fn (bc/->pred re)))
                                 dest)
                   (filter (comp #{:file :sym-link} :type))
                   (map (comp (memfn getName) :entry)))
     :dest dest}))

(defn list-files
  "Lists files in the archive at given path"
  [arch]
  (with-open [is (io/input-stream arch)
              ds (decompress is)
              ai (archive-stream ds)]
    (->> (cu/entry-seq ai)
         (filter (complement (memfn isDirectory)))
         (mapv (memfn getName)))))

(defn- read-entry [ai]
  (with-open [w (java.io.StringWriter.)]
    (io/copy ai w)
    (.toString w)))

(defn extract+read-all
  "Extracts the given source archive, and returns the contents of all files
   that match predicate `pred` as a sequence.  Since the stream needs to be
   closed afterwards, we can't be lazy about it."
  [src pred]
  (with-open [in (io/input-stream src)
              dc (decompress in)
              ai (archive-stream dc)]
    (let [p (bc/->pred pred)]
      (letfn [(matches? [e]
                (and (.isFile e) (p (.getName e))))]
        (->> ai
             (cu/entry-seq)
             (filter matches?)
             (mapv (fn [_] (read-entry ai))))))))

(defn extract+read
  "Extracts the given source archive, and returns the contents of the first
   file that matches predicate `pred`, or `nil` if there were no matches."
  [src pred]
  (with-open [in (io/input-stream src)
              dc (decompress in)
              ai (archive-stream dc)]
    (let [p (bc/->pred pred)]
      (letfn [(matches? [e]
                (and (.isFile e) (p (.getName e))))]
        (when-let [e (->> ai
                          (cu/entry-seq)
                          (filter matches?)
                          (first))]
          (read-entry ai))))))
