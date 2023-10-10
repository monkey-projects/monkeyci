(ns monkey.ci.storage
  "Data storage functionality"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.utils :as u])
  (:import [java.io File PushbackReader]))

(defprotocol Storage
  "Low level storage protocol, that basically allows to store and retrieve
   information by location (aka storage id or sid)."
  (read-obj [this sid] "Read object at given location")
  (write-obj [this sid obj] "Writes object to location")
  (delete-obj [this sid] "Deletes object at location")
  (obj-exists? [this sid] "Checks if object at location exists"))

(def sid? vector?)

(defn- ensure-dir-exists
  "Given a path, creates the parent directories if they do not exist already."
  [^File f]
  (let [p (.getParentFile f)]
    (if-not (.exists p)
      (.mkdirs p)
      true)))

(def ext ".edn")

(defn- ->file [{:keys [dir]} sid]
  (apply io/file dir (concat (butlast sid) [(str (last sid) ext)])))

(defrecord FileStorage [dir]
  Storage
  (read-obj [fs loc]
    (let [f (->file fs loc)]
      (log/trace "Checking for file at" f)
      (when (.exists f)
        (with-open [r (PushbackReader. (io/reader f))]
          (edn/read r)))))

  (write-obj [fs loc obj]
    (let [f (->file fs loc)]
      (when (ensure-dir-exists f)
        (spit f (pr-str obj))
        loc)))

  (obj-exists? [fs loc]
    (.exists (->file fs loc)))

  (delete-obj [fs loc]
    (.delete (->file fs loc))))

(defn make-file-storage [dir]
  (log/debug "File storage location:" dir)
  (->FileStorage dir))

;; In-memory implementation, provider for testing/development purposes
(defrecord MemoryStorage [store]
  Storage
  (read-obj [_ loc]
    (get @store loc))
  
  (write-obj [_ loc obj]
    (swap! store assoc loc obj)
    loc)

  (obj-exists? [_ loc]
    (contains? @store loc))

  (delete-obj [_ loc]
    (swap! store dissoc loc)))

(defn make-memory-storage []
  (->MemoryStorage (atom {})))

(defmulti make-storage :type)

(defmethod make-storage :file [conf]
  (log/info "Using file storage with configuration:" conf)
  (make-file-storage (u/abs-path (:dir conf))))

(defmethod make-storage :memory [conf]
  (log/info "Using memory storage (only for dev purposes!)")
  (make-memory-storage))

;;; Higher level functions

(defn global-sid [type id]
  ["global" (name type) id])

(def webhook-sid (partial global-sid :webhooks))

(def build-sid-keys [:customer-id :project-id :repo-id :build-id])
(def build-sid (apply juxt build-sid-keys))

(defn create-webhook-details [s details]
  (write-obj s (webhook-sid (:id details)) details))

(defn find-details-for-webhook [s id]
  (read-obj s (webhook-sid id)))

(defn- build-sub-sid [obj p]
  (conj (build-sid obj) p))

(defn- sub-sid-builder [f]
  (fn [c]
    (if (sid? c)
      (conj c f)
      (build-sub-sid c f))))

(def build-metadata-sid (sub-sid-builder "metadata"))
(def build-results-sid  (sub-sid-builder "results"))

(defn create-build-metadata
  ([s sid md]
   (write-obj s (build-metadata-sid sid) md))
  ([s md]
   (create-build-metadata s md md)))

(defn find-build-metadata
  "Reads the build metadata given the build coordinates (required to build the path)"
  [s sid]
  (read-obj s (build-metadata-sid sid)))

(defn create-build-results [s sid r]
  (write-obj s (build-results-sid sid) r))

(defn find-build-results
  "Reads the build results given the build coordinates"
  [s sid]
  (read-obj s (build-results-sid sid)))

;;; Listeners

(defn save-build-result
  "Handles a `build/completed` event to store the result."
  [ctx evt]
  (let [r (select-keys evt [:exit :result])]
    (log/debug "Saving build result:" r)
    (create-build-results (:storage ctx)
                          (get-in evt [:build :sid])
                          r)))
