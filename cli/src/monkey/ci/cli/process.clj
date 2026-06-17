(ns monkey.ci.cli.process
  (:require [babashka.fs :as fs]
            [monkey.ci.cli.version :as v]))

(defn run
  "Runs the given command vector in the specified working directory.
   Inherits stdio so output streams directly to the terminal.
   Returns the exit code of the child process.

   An optional `env` map of String->String is merged into the child
   process environment (on top of the current process environment)."
  ([cmd dir]
   (run cmd dir {}))
  ([cmd dir env]
   (let [pb (-> (ProcessBuilder. ^java.util.List (map str cmd))
                (.directory (java.io.File. (str dir)))
                (.inheritIO))]
     (when (seq env)
       (let [proc-env (.environment pb)]
         (doseq [[k v] env]
           (.put proc-env (str k) (str v)))))
     (-> pb (.start) (.waitFor)))))

(defn generate-deps [lib-version]
  {:paths ["."]
   :aliases
   {:monkeyci/build
    {:exec-fn 'monkey.ci.script.core/run-clj-cli
     :extra-deps {'com.monkeyci/script {:mvn/version (or lib-version (v/version))}}}}})

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
    ;; This future is created with the classloader of the caller
    (future (f @exit))
    ;; The deliver is triggered with the "bare" classloader
    (partial deliver exit)))
