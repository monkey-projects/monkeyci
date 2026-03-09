(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [utils :as utils]
             [version :as v]]))

(defn generate-deps [script-dir lib-version]
  {:paths ["."]
   :aliases
   {:monkeyci/build
    {:exec-fn 'monkey.ci.script.runtime/run-script!
     :extra-deps {'com.monkeyci/app {:mvn/version (or lib-version (v/version))}}}}})

(defn add-logback-config
  "Updates the deps config to add jvm opts that configures logback
   using the given path"
  [deps alias path]
  (update-in deps [:aliases alias :jvm-opts] conj (str "-Dlogback.configurationFile=" path)))

(defn update-alias
  "Updates the monkeyci/build alias in the given deps"
  [deps f & args]
  (apply update-in deps [:aliases :monkeyci/build] f args))

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
  "Executes any unit tests that have been defined for the build script at
   given location by starting a clojure process with a custom alias for
   running tests using kaocha."
  [dir {:keys [watch? dev-mode?]}]
  (let [deps (generate-test-deps dev-mode? watch?)]
    (bp/process
     {:cmd ["clojure" "-Sdeps" (pr-str deps) "-X:monkeyci/test"]
      :out :inherit
      :err :inherit
      :dir dir})))

(defn exit-fn
  "Due to a strange issue with the onExit functionality in java.lang.Process, the
   classloaders get messed up and stuff doesn't work in there.  So we use a promise
   to trigger the on-exit indirectly.  This function wraps `f` and returns another
   function that can be passed safely to the Process onExit."
  [f]
  (let [exit (promise)]
    (md/chain exit f)
    (partial deliver exit)))
