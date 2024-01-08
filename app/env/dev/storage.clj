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

(defn list-builds
  "Lists builds according to current account settings"
  []
  (s/list-builds (make-storage)
                 (c/account->sid)))
