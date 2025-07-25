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
            [monkey.ci.agent
             [container :as ac]
             [main :as am]]
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
  (c/load-config! "scw/crypto.edn")
  (server/start-server))

(defn refresh []
  (server/stop-server)
  (nr/refresh))

(defn list-staging []
  (c/load-config! "oci/staging-config.edn")
  (->> @(i/list-active)
       (map (juxt :id :display-name :time-created :lifecycle-state))))

(defn run-local [wd & [sd params]]
  (cmd/run-build-local
   {:args
    {:workdir wd
     :dir (or sd ".monkeyci")
     :param params}
    :lib-coords {:local/root (u/cwd)}
    :log-config (str (fs/absolutize "dev-resources/logback-script.xml"))}))

(defn run-build-agent
  "Runs build agent with local config, returns a fn that stops the agent
   server and poll loop when invoked."
  ([conf]
   (let [d (md/deferred)]
     (am/run-agent (config/load-config-file (str "dev-resources/config/" conf))
                   (constantly d))
     (fn [] (md/success! d true))))
  ([]
   (run-build-agent "build-agent.edn")))

(defn run-container-agent
  "Runs container agent, returns a fn that stops the agent when invoked."
  ([conf]
   (let [stop (promise)]
     (future
       (ac/run-agent (config/load-config-file (str "dev-resources/config/" conf))
                     (fn [sys]
                       (deliver stop #(reset! (get-in sys [:poll-loop :running?]) false))
                       @(get-in sys [:poll-loop :future]))))
     @stop))
  ([]
   (run-container-agent "container-agent.edn")))

(defn generate-token
  ([config uid]
   (server/generate-jwt (get-in config [:runner :jwk :priv]) uid))
  ([uid]
   (generate-token (global-config) uid)))
