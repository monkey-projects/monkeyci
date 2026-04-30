(ns monkey.ci.cli.test
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.cli
             [print :as p]
             [process :as process]]))

(defn bb-edn-content
  "Generates the contents of the bb.edn file, used for testing"
  [conf]
  {:tasks
   {'test
    {:doc  "Run unit tests using cognitect test runner"
     :extra-deps {'lambdaisland/kaocha
                  {:mvn/version "1.91.1392"}}
     :extra-paths ["."]   ; Must be relative path
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

(defn run-tests
  "Runs script unit tests (if any) in the script subdir of the specified directory.
   If there is no `.monkeyci` directory in the target, runs tests in target itself."
  [{:keys [dir] :as opts}]
  ;; TODO If a deps.edn file is present, run as regular clojure instead of bb.
  (let [dir (-> dir (fs/file) (fs/absolutize))
        conf (write-kaocha-config opts)
        bb-edn (write-temp-bb-edn dir conf)]
    (try
      (log/debug "Running tests in" dir "using babashka")
      (p/print-msg "Running unit tests in" (str dir))
      (process/run ["bb" "--config" bb-edn "run" "test"] dir)
      (finally
        (fs/delete-if-exists conf)
        (fs/delete-if-exists bb-edn)))))
