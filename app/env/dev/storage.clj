(ns storage
  (:require [clojure.java.io :as io]
            [config :as c]
            [monkey.ci
             [config :as config]
             [protocols :as p]
             [storage :as s]]
            [monkey.ci.storage.oci]
            [monkey.oci.os.core :as os]))

(defn- storage-config []
  (-> @c/global-config
      (config/normalize-config {} {})))

(defn make-storage
  ([conf]
   (s/make-storage conf))
  ([]
   (make-storage (storage-config))))

(defn get-customer
  "Retrieves customer info for the current config"
  []
  (s/find-customer (make-storage)
                   (get-in @c/global-config [:account :customer-id])))

(defn update-customer
  [upd]
  (s/save-customer (make-storage) upd))

(defn list-builds
  "Lists builds according to current account settings"
  []
  (s/list-builds (make-storage)
                 (c/account->sid)))

(defn get-build [id]
  (let [b (make-storage)
        sid (vec (concat (c/account->sid) [id]))]
    (merge (s/find-build-metadata b sid)
           (s/find-build-results b sid))))

(defn delete-build [id]
  (let [b (make-storage)
        sid (vec (concat (c/account->sid) [id]))
        d (juxt (comp (partial p/delete-obj b) s/build-metadata-sid)
                (comp (partial p/delete-obj b) s/build-results-sid))]
    (d sid)))

(defn download-all
  "Downloads all files in the storage bucket with given prefix to local disk"
  [prefix dir]
  (let [conf (:storage (storage-config))
        s (make-storage)
        c (.client s)
        files (-> @(os/list-objects c (cond-> conf
                                        prefix (assoc :prefix prefix)))
                  :objects)
        dir (io/file dir)]
    (println "Found" (count files) "files to download")
    (.mkdirs dir)
    (doseq [f (map :name files)]
      (println f)
      (let [dest (io/file dir f)]
        (.mkdirs (.getParentFile dest))
        (io/copy @(os/get-object c (assoc conf :object-name f)) dest)))
    (println "Done.")))
