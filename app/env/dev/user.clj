(ns user
  (:require [babashka.fs :as fs]
            [config :as c]
            [instances :as i]
            [logging :as l]
            [server :as server]
            [storage :as s]
            [clojure.tools.namespace.repl :as nr]
            [monkey.ci
             [commands :as cmd]
             [utils :as u]]))

(defn global-config []
  @c/global-config)

(defn start-staging []
  (c/reset-config!)
  (c/load-config! "staging.edn")
  (c/load-config! "oci/staging-config.edn")
  (c/load-config! "github/staging.edn")
  (c/load-config! "bitbucket/dev.edn")
  (c/load-config! "storage/staging.edn")
  (server/start-server))

(defn start-local []
  (c/reset-config!)
  (c/load-config! "local.edn")
  (c/load-config! "github/staging.edn")
  (c/load-config! "bitbucket/dev.edn")
  (server/start-server))

(defn refresh []
  (server/stop-server)
  (nr/refresh))

(defn list-staging []
  (c/load-config! "oci/staging-config.edn")
  (->> @(i/list-active)
       (map (juxt :id :display-name :time-created :lifecycle-state))))

(defn run-local [wd & [sd]]
  (cmd/run-build-local {:workdir wd
                        :dir sd
                        :lib-coords {:local/root (u/cwd)}
                        :log-config (str (fs/absolutize "dev-resources/logback-test.xml"))}))
