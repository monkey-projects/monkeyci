(ns storage
  (:require [config :refer [load-config]]
            [monkey.ci.storage :as s]))

(defn make-storage [conf]
  (-> conf
      (load-config)
      :storage
      (s/make-storage)))
