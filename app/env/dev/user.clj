(ns user
  (:require [config :as c]
            [logging :as l]
            [server :as server]
            [storage :as s]))

(defn global-config []
  @c/global-config)
