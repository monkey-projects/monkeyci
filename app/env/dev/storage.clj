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

(defn list-builds
  "Lists builds according to current account settings"
  []
  (s/list-builds (make-storage)
                 (c/account->sid)))
