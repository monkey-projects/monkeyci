(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [config :as config]
             [edn :as edn]
             [errors :as err]
             [logging :as l]
             [retry :as retry]
             [runtime :as rt]
             [script :as script]
             [sidecar]
             [utils :as utils]
             [version :as v]]
            [monkey.ci.build
             [api-server :as as]
             [core :as bc]]
            [monkey.ci.script.config :as sc]
            ;; Need to require these for the multimethod discovery
            [monkey.ci.containers.oci]
            [monkey.ci.events.core]
            [monkey.ci.script.runtime :as sr]
            [monkey.ci.storage
             [file]
             [sql]]))

(defn- version-or [dev? f]
  (if dev?
    {:local/root (f)}
    {:mvn/version (v/version)}))

(defn generate-test-deps [dev? watch?]
  (letfn [(test-lib-dir []
            (-> (utils/cwd) (fs/parent) (fs/path "test-lib") str))]
    {:aliases
     {:monkeyci/test
      {:extra-deps {'com.monkeyci/app (version-or dev? utils/cwd)
                    'com.monkeyci/test (version-or dev? test-lib-dir)}
       :paths ["."]
       :exec-fn 'kaocha.runner/exec-fn
       :exec-args (cond-> {:tests [{:type :kaocha.type/clojure.test
                                    :id :unit
                                    :ns-patterns ["-test$"]
                                    :source-paths ["."]
                                    :test-paths ["."]}]}
                    watch? (assoc :watch? true))}}}))

(defn test!
  "Executes any unit tests that have been defined for the build by starting a clojure process
   with a custom alias for running tests using kaocha."
  [build rt]
  (let [watch? (true? (get-in rt [:config :args :watch]))
        deps (generate-test-deps (rt/dev-mode? rt) watch?)]
    (bp/process
     {:cmd ["clojure" "-Sdeps" (pr-str deps) "-X:monkeyci/test"]
      :out :inherit
      :err :inherit
      :dir (b/script-dir build)})))
