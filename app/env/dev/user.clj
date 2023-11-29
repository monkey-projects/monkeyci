(ns user
  (:require [config :as c]
            [logging :as l]
            [storage :as s]))

(defn global-config []
  @c/global-config)
