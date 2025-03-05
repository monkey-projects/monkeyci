(ns runners
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [config :as co]
            [manifold.deferred :as md]
            [monkey.ci runners
             [build :as b]
             [commands :as cmd]
             [process :as proc]
             [time :as t]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runners.runtime :as rr]
            [monkey.ci.script
             [core :as s]
             [runtime :as sr]]
            [monkey.ci.web.auth :as auth]))

(defn clear-git-dir [build]
  (when-let [d (get-in build [:git :dir])]
    (when (fs/exists? d)
      (fs/delete-tree d))))

(defn gen-token [conf]
  (let [keypair (auth/config->keypair conf)
        ctx {:jwk keypair}
        token (auth/build-token (b/sid (:build conf)))]
    (auth/generate-jwt-from-rt ctx token)))

(defn- add-token [conf]
  (assoc-in conf [:api :token] (gen-token conf)))

(defn make-build [sid git-url branch]
  (-> (zipmap [:customer-id :repo-id :build-id] sid)
      (assoc :sid sid
             :git {:url git-url
                   :branch branch})))

(defn run-build
  "Runs a build using given or current config"
  ([config sid url branch]
   (let [build-id (str "build-" (t/now))
         sid (conj sid build-id)
         build (-> (zipmap b/sid-props sid)
                   (assoc :sid sid
                          :git {:url url
                                :branch (or branch "main")}))]
     (log/info "Running build at url" url)
     (rr/with-runner-system (assoc config :build build)
       (fn [sys]
         (let [rt (:runtime sys)
               r (get-in sys [:runner :runner])]
           (r build rt))))))

  ([sid url branch]
   (run-build @co/global-config sid url branch)))
