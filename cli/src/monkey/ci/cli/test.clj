(ns monkey.ci.cli.test
  "Functions for running script unit tests from the CLI.  By default Babashka
   is used, but the user can choose to use Clojure as well."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.cli
             [print :as p]
             [process :as process]
             [utils :as u]
             [version :as v]]))

(def kaocha-coords {:mvn/version "1.91.1392"})

(defn bb-edn-content
  "Generates the contents of the bb.edn file, used for testing"
  [conf]
  {:tasks
   {'test
    {:doc  "Run unit tests using cognitect test runner"
     :extra-deps {'lambdaisland/kaocha kaocha-coords}
     :extra-paths ["."]                 ; Must be relative path
     :task '(exec 'kaocha.repl/run-all)
     :exec-args {:config-file conf}}}})

(defn- write-tmp-edn [dest contents]
  (doto (str dest)
    (fs/delete-on-exit)
    (spit (pr-str contents))
    (fs/absolutize)))

(defn write-temp-bb-edn [dir conf-path]
  ;; Create the bb.edn config file in the test dir, because paths are resolved relative to
  ;; this file, and the paths in the config file must be relative.
  (write-tmp-edn (fs/file dir (str "monkeyci-bb-" (random-uuid) ".edn"))
                 (bb-edn-content conf-path)))

(defn kaocha-config
  "Generates kaocha configuration for running tests."
  [{:keys [watch]}]
  (cond-> {:kaocha/tests [{:kaocha.testable/type :kaocha.type/clojure.test
                           :kaocha.testable/id :unit
                           :kaocha/ns-patterns ["-test$"]
                           :kaocha/source-paths ["."]
                           :kaocha/test-paths ["."]}]
           :kaocha/fail-fast? false
           :kaocha/color? true
           :kaocha/reporter ['kaocha.report/documentation]}
    (true? watch) (assoc :watch? true)))

(defn write-kaocha-config [opts]
  (write-tmp-edn (fs/create-temp-file {:prefix "monkeyci-kaocha-" :suffix ".edn"})
                 (kaocha-config opts)))

(defn- get-runner
  "Retrieves the type of runner from the opts.  Returns `:bb` by default."
  [opts]
  (or (:runner opts) :bb))

(defn- run-bb-tests [{:keys [dir] :as opts}]
  (let [conf (write-kaocha-config opts)
        bb-edn (write-temp-bb-edn dir conf)]
    (try
      (process/run ["bb" "--config" bb-edn "run" "test"] dir)
      (finally
        (fs/delete-if-exists conf)
        (fs/delete-if-exists bb-edn)))))

(defn generate-test-deps [lib-coords test-lib-coords watch?]
  {:aliases
   {:monkeyci/test
    {:extra-deps {'com.monkeyci/script lib-coords
                  'com.monkeyci/test test-lib-coords
                  'lambdaisland/kaocha kaocha-coords}
     :paths ["."]
     :exec-fn 'kaocha.runner/exec-fn
     :exec-args (cond-> {:tests [{:type :kaocha.type/clojure.test
                                  :id :unit
                                  :ns-patterns ["-test$"]
                                  :source-paths ["."]
                                  :test-paths ["."]}]}
                  watch? (assoc :watch? true))}}})

(defn- run-clj-tests [{:keys [dir] :as opts}]
  (let [lib-coords {:mvn/version (or (:lib-version opts) (v/version))}
        deps (generate-test-deps lib-coords lib-coords (:watch opts))]
    (process/run ["clojure" "-Sdeps" (pr-str deps) "-X:monkeyci/test"] dir)))

(defn run-tests
  "Runs script unit tests (if any) in the script subdir of the specified directory.
   If there is no `.monkeyci` directory in the target, runs tests in target itself."
  [{:keys [dir] :as opts}]
  (let [dir (-> dir (fs/file) (fs/canonicalize) (u/find-script-dir) fs/path)
        opts (assoc opts :dir dir)
        ;; TODO If a deps.edn file is present, run as regular clojure instead of bb.
        runner (get-runner opts)]
    (log/debug "Running tests in" dir "using runner" runner)
    (p/print-msg "Running unit tests in" (str dir))
    (case runner
      :bb  (run-bb-tests opts)
      :clj (run-clj-tests opts))))
