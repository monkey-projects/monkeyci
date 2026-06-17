(ns monkey.ci.process
  "Process execution functions.  Executes build scripts in a separate process,
   using clojure cli tools."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [manifold.deferred :as md]
            [monkey.ci
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

(defn exit-fn
  "Due to a strange issue with the onExit functionality in java.lang.Process, the
   classloaders get messed up and stuff doesn't work in there.  So we use a promise
   to trigger the on-exit indirectly.  This function wraps `f` and returns another
   function that can be passed safely to the Process onExit."
  [f]
  (let [exit (promise)]
    (md/chain exit f)
    (partial deliver exit)))
