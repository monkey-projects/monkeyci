(ns monkey.ci.integration-test.job-test
  "Integration test that runs a build api, sidecar and script, similar to what would
   happen when running a container job."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [babashka
             [fs :as fs]
             [process :as proc]]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runners.runtime :as rr]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.sidecar
             [config :as sc]
             [core :as sco]
             [runtime :as sr]]
            [monkey.ci.test.helpers :as h]))

(defn- run-sidecar [conf]
  (log/info "Starting sidecar with config:" conf)
  (rc/with-system-async (sr/make-system conf)
    (fn [sys]
      (sco/run (:runtime sys)))))

(defn- run-script [conf dir]
  (try
    (fs/create-dirs dir)
    (let [script (-> conf (sc/job) :script)
          wd (b/checkout-dir (sc/build conf))
          job-sh (str (fs/absolutize (io/resource "job.sh")))]
      (log/info "Running script:" script "in dir" wd)
      ;; Prepare script files
      (log/debug "Generating script lines in" dir)
      (->> script
           (map-indexed (fn [idx l]
                          (spit (fs/file (fs/path dir (str idx))) l)))
           (doall))
      (log/debug "Job script:" job-sh)
      (proc/process
       {:cmd (->> (range (count script))
                  (map str)
                  (into ["/bin/bash" (str job-sh)]))
        :dir dir
        :env {"MONKEYCI_WORK_DIR" wd
              "MONKEYCI_SCRIPT_DIR" (str dir)
              "MONKEYCI_EVENT_FILE" (sc/events-file conf)
              "MONKEYCI_START_FILE" (sc/start-file conf)
              "MONKEYCI_ABORT_FILE" (sc/abort-file conf)}}))
    (catch Exception ex
      (log/error "Failed to run script" ex))))

(deftest container-job-simulation
  (testing "runs sidecar and script commands"
    (h/with-tmp-dir dir
      (let [build (zipmap b/sid-props (repeatedly cuid/random-cuid))
            job {:id "test-job"
                 :script ["echo 'this is a test' > test.txt"]
                 :save-artifacts [{:path "test.txt"
                                   :id "test-result"}]}
            conf {:build build
                  :checkout-base-dir (str (fs/path dir "checkout"))
                  :artifacts
                  {:type :disk
                   :dir (fs/file (fs/path dir "artifacts"))}
                  :cache
                  {:type :disk
                   :dir (fs/file (fs/path dir "cache"))}
                  :workspace
                  {:type :disk
                   :dir (fs/file (fs/create-dirs (fs/path dir "ws")))}
                  :build-cache
                  {:type :disk
                   :dir (fs/file (fs/path dir "build-cache"))}
                  :mailman
                  {:type :manifold}
                  :containers
                  {:type :oci}}
            sidecar-file (fn [f]
                           (str (-> (fs/path dir "sidecar")
                                    (fs/create-dirs)
                                    (fs/path f))))]
        (rr/with-runner-system conf
          (fn [sys]
            (is (some? sys))
            (is (string? (-> sys :api-config :token)))
            (let [script-conf (-> {}
                                  (sc/set-build (:build sys))
                                  (sc/set-job job)
                                  (sc/set-api (bas/srv->api-config (:api-config sys)))
                                  (sc/set-start-file (sidecar-file "start"))
                                  (sc/set-abort-file (sidecar-file "abort"))
                                  (sc/set-events-file (sidecar-file "events.edn")))]
              (is (= 0 (-> (md/zip
                            ;; Start sidecar
                            (md/timeout! (run-sidecar script-conf)
                                         2000
                                         :sidecar/timeout)
                            ;; Start script
                            (run-script script-conf (fs/path dir "script")))
                           (deref)
                           first
                           :exit))))))))))
