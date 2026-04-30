(ns monkey.ci.cli.test
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.cli
             [print :as p]
             [process :as process]]))

(defn bb-edn-content [conf]
  {:tasks
   {'test
    {:doc  "Run unit tests using cognitect test runner"
     :extra-deps {'lambdaisland/kaocha
                  {:mvn/version "1.91.1392"}}
     :extra-paths ["."]   ; Must be relative path
     :task '(exec 'kaocha.repl/run-all)
     :exec-args {:config-file conf}}}})

(defn write-temp-bb-edn [dir conf-path]
  ;; Create the bb.edn config file in the test dir, because paths are resolved relative to
  ;; this file, and the paths in the config file must be relative.
  (doto (str (fs/file dir (str "monkeyci-bb-" (random-uuid) ".edn")))
    (fs/delete-on-exit)
    (spit (pr-str (bb-edn-content conf-path)))
    (fs/absolutize)))

(defn kaocha-config [{:keys [watch]}]
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
  (doto (str (fs/create-temp-file {:prefix "monkeyci-kaocha-" :suffix ".edn"}))
    (fs/delete-on-exit)
    (spit (pr-str (kaocha-config opts)))
    (fs/absolutize)))

(defn run-tests [{:keys [dir] :as opts}]
  ;; TODO If a deps.edn file is present, run as regular clojure instead of bb.
  (let [dir (-> dir (fs/file) (fs/absolutize))
        conf (write-kaocha-config opts)
        bb-edn (write-temp-bb-edn dir conf)]
    (log/debug "Running tests in" dir "using babashka")
    (p/print-msg "Running unit tests in" (str dir))
    (process/run ["bb" "--config" bb-edn "run" "test"] dir)))
