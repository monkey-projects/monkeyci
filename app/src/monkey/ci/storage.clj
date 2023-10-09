(ns monkey.ci.storage
  "Data storage functionality"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io File PushbackReader]))

(defprotocol Storage
  "Low level storage protocol, that basically allows to store and retrieve
   information by location."
  (read-obj [this loc] "Read object at given location")
  (write-obj [this loc obj] "Writes object to location")
  (delete-obj [this loc] "Deletes object at location")
  (obj-exists? [this loc] "Checks if object at location exists"))

(defn- ensure-dir-exists
  "Given a path, creates the parent directories if they do not exist already."
  [^File f]
  (let [p (.getParentFile f)]
    (if-not (.exists p)
      (.mkdirs p)
      true)))

(defrecord FileStorage [dir]
  Storage
  (read-obj [_ loc]
    (with-open [r (PushbackReader. (io/reader (io/file dir loc)))]
      (edn/read r)))

  (write-obj [_ loc obj]
    (let [f (io/file dir loc)]
      (when (ensure-dir-exists f)
        (spit f (pr-str obj))
        (.getCanonicalPath f))))

  (obj-exists? [_ loc]
    (.exists (io/file dir loc)))

  (delete-obj [_ loc]
    (.delete (io/file dir loc))))

(defn make-file-storage [dir]
  (->FileStorage dir))

(defmulti make-storage :type)

(defmethod make-storage :file [conf]
  (make-file-storage (:dir conf)))
