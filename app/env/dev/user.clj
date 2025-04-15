(ns user
  (:require [babashka.fs :as fs]
            [clojure.tools.namespace.repl :as nr]
            [config :as c]
            [instances :as i]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as cmd]
             [config :as config]
             [utils :as u]]
            [monkey.ci.agent.main :as am]
            [server :as server]))

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
                        :log-config (str (fs/absolutize "dev-resources/logback-script.xml"))}))

(defn run-agent []
  (let [d (md/deferred)]
    (am/run-agent (config/load-config-file "dev-resources/config/agent.edn")
                  (constantly d))
    d))
