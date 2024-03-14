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
  (c/load-config! "github/staging.edn")
  (server/start-server))

(defn start-local []
  (c/load-config! "local.edn")
  (c/load-config! "github/staging.edn")
  (server/start-server))
