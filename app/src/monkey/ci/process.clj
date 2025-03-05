(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [monkey.ci
             [build :as b]
             [runtime :as rt]
             [utils :as utils]
             [version :as v]]))

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
