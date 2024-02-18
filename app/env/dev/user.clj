(ns user
  (:require [config :as c]
            [logging :as l]
            [server :as server]
            [storage :as s]))

(defn global-config []
  @c/global-config)

(defn start-staging []
  (c/load-config! "oci/staging-config.edn")
  (c/load-config! "staging.edn")
  (server/start-server))
