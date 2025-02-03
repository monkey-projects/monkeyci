(ns monkey.ci.test
  "This is the basic namespace to use in unit tests.  It provides various functions that
   can be used to simulate a MonkeyCI build environment."
  (:require [monkey.ci.build.api :as ba]
            [monkey.ci.protocols :as p]))

(defn with-build-params* [params f]
  (with-redefs [ba/build-params (constantly params)]
    (f)))

(defmacro with-build-params
  "Simulates given build parameters for the body to execute"
  [params & body]
  `(with-build-params* ~params (fn [] ~@body)))

(def test-ctx
  "Basic test context, to use in test jobs"
  {:build
   {:git
    {:main-branch "main"}}})

(defn with-git-ref
  "Sets given ref in the context git configuration"
  [ctx ref]
  (assoc-in ctx [:build :git :ref] ref))

(defn update-changes
  "Updates context file changes"
  [ctx f & args]
  (apply update-in ctx [:build :changes] f args))

(defn with-changes
  "Adds given file set to the context file changes"
  [ctx changes]
  (update-changes ctx merge changes))

(defn set-changes
  "Overwrites context file changes"
  [ctx changes]
  (update-changes ctx (constantly changes)))

(defn added
  "Defines a set of added (i.e. new) files.  To be used with the `xxx-changes` functions."
  [files]
  {:added files})

(defn modified
  "Defines a set of modified files."
  [files]
  {:modified files})

(defn removed
  "Defines a set of removed files."
  [files]
  {:removed files})

(defn resolve-jobs
  "Given one or more unresolved jobs, resolves them using the given context.  This is
   useful if you want to get a full list of the actual jobs that MonkeyCI will run given
   a specific context."
  [jobs ctx]
  (p/resolve-jobs jobs ctx))
