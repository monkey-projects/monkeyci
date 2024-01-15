(ns storage
  (:require [config :as c]
            [monkey.ci.storage :as s]
            [monkey.ci.storage.oci]))

(defn make-storage
  ([conf]
   (-> conf
       (c/load-config)
       (s/make-storage)))
  ([]
   (-> @c/global-config
       (s/make-storage))))

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
        d (juxt (comp (partial s/delete-obj b) s/build-metadata-sid)
                (comp (partial s/delete-obj b) s/build-results-sid))]
    (d sid)))
