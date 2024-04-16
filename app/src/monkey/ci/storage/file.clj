(ns monkey.ci.storage.file
  "File storage implementation.  Useful for local or develop runs.  It stores all
   information in local .edn files."
  (:require [clojure
             [edn :as edn]
             [string :as cs]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as s]
             [utils :as u]])
  (:import [java.io File PushbackReader]))

(defn- ensure-dir-exists
  "Given a path, creates the parent directories if they do not exist already."
  [^File f]
  (let [p (.getParentFile f)]
    (if-not (.exists p)
      (.mkdirs p)
      true)))

(def ext ".edn")

(defn- ->file [dir sid]
  (apply io/file dir (concat (butlast sid) [(str (last sid) ext)])))

(defn- name-without-ext [f]
  (let [n (.getName f)]
    (if (.isFile f)
      (cond-> n
        (cs/ends-with? n ext) (subs 0 (- (count n) (count ext))))
      n)))

;; Must be a type, not a record, otherwise it gets lost in reitit data processing
(deftype FileStorage [dir]
  p/Storage
  (read-obj [_ loc]
    (let [f (->file dir loc)]
      (log/trace "Checking for file at" f)
      (when (.exists f)
        (with-open [r (PushbackReader. (io/reader f))]
          (edn/read r)))))

  (write-obj [_ loc obj]
    (let [f (->file dir loc)]
      (when (ensure-dir-exists f)
        (spit f (pr-str obj))
        loc)))

  (obj-exists? [_ loc]
    (.exists (->file dir loc)))

  (delete-obj [_ loc]
    (.delete (->file dir loc)))

  (list-obj [_ loc]
    (->> (.listFiles (apply io/file dir loc))
         (seq)
         (map name-without-ext))))

(defn make-file-storage [dir]
  (log/debug "File storage location:" dir)
  (->FileStorage dir))

(defmethod s/make-storage :file [{conf :storage}]
  (log/info "Using file storage with configuration:" conf)
  (make-file-storage (u/abs-path (:dir conf))))
