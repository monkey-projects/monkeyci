(ns monkey.ci.cli.containers.podman-test
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli.containers.podman :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.mailman
             [core :as mmc]
             [core-async :as mmca]]))

;;;; ─── Helpers ───────────────────────────────────────────────────────────────

(defn- test-mailman [] (mmca/core-async-broker))

(defn- ctx-with-state [state-map]
  (emi/set-state {} state-map))

(defn- event-ctx
  ([sid job-id]
   {:event {:sid sid :job-id job-id}})
  ([sid job-id job]
   (assoc-in (event-ctx sid job-id) [:event :job] job)))

(def ^:private test-sid ["org" "repo" "build-1"])
(def ^:private test-job-id "my-job")

(defn- ctx-with-job [job]
  (-> (event-ctx test-sid test-job-id job)
      (emi/set-state {:jobs {test-sid {test-job-id job}}})))

;;;; ─── Command-line building ─────────────────────────────────────────────────

(deftest build-cmd-args-test
  (let [job {:type :container :container/image "alpine:3" :size 1}
        sid (conj test-sid test-job-id)]
    (fs/with-temp-dir [wd]
      (fs/with-temp-dir [sd]
        (fs/with-temp-dir [ld]
          (let [args (sut/build-cmd-args {:job job
                                          :sid sid
                                          :work-dir wd
                                          :script-dir sd
                                          :log-dir ld})]
            (testing "starts with podman run"
              (is (= "podman" (first args)))
              (is (= "run" (second args))))

            (testing "includes image"
              (is (some #(= "alpine:3" %) args)))

            (testing "includes cpu and memory limits"
              (let [s (vec args)]
                (is (some #(= "--cpus" %) s))
                (is (some #(= "--memory" %) s))))

            (testing "includes work-dir mount"
              (is (some #(clojure.string/includes? % (str wd)) args)))

            (testing "sets --rm by default"
              (is (some #(= "--rm" %) args)))))))))

(deftest build-cmd-args-no-rm-in-dev-test
  (let [job {:type :container :container/image "alpine:3"}
        sid (conj test-sid test-job-id)]
    (fs/with-temp-dir [wd]
      (fs/with-temp-dir [sd]
        (fs/with-temp-dir [ld]
          (let [args (sut/build-cmd-args {:job job :sid sid :work-dir wd
                                          :script-dir sd :log-dir ld
                                          :dev-mode true})]
            (testing "does NOT include --rm in dev mode"
              (is (not (some #(= "--rm" %) args))))))))))

;;;; ─── Port helpers ──────────────────────────────────────────────────────────

(deftest find-avail-ports-test
  (testing "returns n available ports"
    (is (= [20000 20001 20002]
           (sut/find-avail-ports 3 [20000 21000] #{}))))

  (testing "skips ports in use"
    (is (= [20001 20002]
           (sut/find-avail-ports 2 [20000 21000] #{20000}))))

  (testing "returns empty when range is exhausted"
    (is (empty? (sut/find-avail-ports 5 [20000 20003] #{20000 20001 20002})))))

;;;; ─── Reserved env vars ─────────────────────────────────────────────────────

(deftest reserved?-test
  (testing "true for known reserved vars"
    (is (sut/reserved? "TMPDIR"))
    (is (sut/reserved? "CONTAINERS_CONF")))

  (testing "true for PODMAN_* prefix"
    (is (sut/reserved? "PODMAN_SYSLOG")))

  (testing "false for ordinary vars"
    (is (not (sut/reserved? "MY_APP_SECRET")))
    (is (not (sut/reserved? "CI")))))

;;;; ─── filter-container-job ──────────────────────────────────────────────────

(deftest filter-container-job-test
  (let [{:keys [enter]} sut/filter-container-job]
    (testing "continues when job has an image"
      (let [ctx {:event {:job {:container/image "nginx"}}}]
        ;; Should not set :terminated
        (is (not (:terminated (enter ctx))))))

    (testing "sets :terminated when job has no image"
      (let [ctx {:event {:job {:type :action}}}]
        (is (:terminated (enter ctx)))))))

;;;; ─── save-job / get-job round-trip ─────────────────────────────────────────

(deftest save-and-get-job-test
  (let [job {:id test-job-id :type :container :container/image "test"}
        ctx (-> (event-ctx test-sid test-job-id job)
                (emi/set-state {}))]
    (testing "save-job stores job in state"
      (let [result ((:enter sut/save-job) ctx)]
        (is (= job (sut/get-job result test-job-id)))))))

;;;; ─── make-routes ───────────────────────────────────────────────────────────

(deftest make-routes-test
  (let [mm     (test-mailman)
        routes (into {} (sut/make-routes {:mailman mm}))]
    (testing "includes :container/job-queued route"
      (is (contains? routes :container/job-queued)))

    (testing "includes :job/initializing route"
      (is (contains? routes :job/initializing)))

    (testing "includes :container/pending route"
      (is (contains? routes :container/pending)))

    (testing "includes :container/end route"
      (is (contains? routes :container/end)))))

;;;; ─── watch-events / stop-watch-events ─────────────────────────────────────

(deftest watch-events-stop-test
  (testing "stop-watch-events closes the events channel"
    (let [ch  (ca/chan 1)
          ctx (-> (event-ctx test-sid test-job-id)
                  (emi/set-state {})
                  (sut/set-events-ch ch))
          result ((:enter sut/stop-watch-events) ctx)]
      ;; Channel should be closed after stop
      (is (nil? (ca/poll! ch))))))

;;;; ─── job-queued-result ─────────────────────────────────────────────────────

(deftest job-queued-result-test
  (let [job {:id test-job-id :type :container :container/image "img"}
        ctx (-> (ctx-with-job job)
                (emi/set-state {:jobs {test-sid {test-job-id job}}
                                ::sut/job-dir "/tmp/jobs/org/repo/build-1/my-job"}))
        {:keys [leave]} (sut/job-queued-result {})]
    (testing "sets job/initializing event as result"
      (let [result (leave ctx)]
        (is (= :job/initializing (-> (emi/get-result result)
                                     first
                                     :type)))))))

(deftest restore-caches
  (let [restored (atom nil)
        {:keys [enter] :as i} (sut/restore-caches
                               (fn [dir a]
                                 (reset! restored (assoc a :dir dir))))]
    (is (keyword? (:name i)))
    (is (fn? enter))
    
    (testing "`enter` restores configured caches on job"
      (is (= [{:id "test-cache"
               :path "test/location"
               :dir (fs/path "/tmp/test-dir/work")}]
             (-> {:event
                  {:job-id "test-job"}}
                 (sut/set-job {:id "test-job"
                               :caches [{:id "test-cache" :path "test/location"}]})
                 (sut/set-job-dir "/tmp/test-dir")
                 (enter)
                 (sut/get-restored-caches)))))))

(deftest save-caches
  (let [saved (atom nil)
        {:keys [leave] :as i} (sut/save-caches
                               (fn [dir a]
                                 (reset! saved (assoc a :dir dir))))]
    (is (keyword? (:name i)))
    (is (fn? leave))
    
    (testing "`leave` saves configured caches on job"
      (is (= [{:id "test-cache"
               :path "test/location"
               :dir (fs/path "/tmp/test-dir/work")}]
             (-> {:event
                  {:job-id "test-job"}}
                 (sut/set-job {:id "test-job"
                               :caches [{:id "test-cache" :path "test/location"}]})
                 (sut/set-job-dir "/tmp/test-dir")
                 (leave)
                 (sut/get-saved-caches)))))))

(deftest restore-artifacts
  (let [restored (atom nil)
        {:keys [enter] :as i} (sut/restore-artifacts
                               (fn [dir a]
                                 (reset! restored (assoc a :dir dir))))]
    (is (keyword? (:name i)))
    (is (fn? enter))
    
    (testing "`enter` restores configured artifacts on job"
      (is (= [{:id "test-artifact"
               :path "test/location"
               :dir (fs/path "/tmp/test-dir/work")}]
             (-> {:event
                  {:job-id "test-job"}}
                 (sut/set-job {:id "test-job"
                               :restore-artifacts [{:id "test-artifact" :path "test/location"}]})
                 (sut/set-job-dir "/tmp/test-dir")
                 (enter)
                 (sut/get-restored-artifacts)))))))

(deftest save-artifacts
  (let [saved (atom nil)
        {:keys [leave] :as i} (sut/save-artifacts
                               (fn [dir a]
                                 (reset! saved (assoc a :dir dir))))]
    (is (keyword? (:name i)))
    (is (fn? leave))
    
    (testing "`leave` saves configured artifacts on job"
      (is (= [{:id "test-artifact"
               :path "test/location"
               :dir (fs/path "/tmp/test-dir/work")}]
             (-> {:event
                  {:job-id "test-job"}}
                 (sut/set-job {:id "test-job"
                               :save-artifacts [{:id "test-artifact" :path "test/location"}]})
                 (sut/set-job-dir "/tmp/test-dir")
                 (leave)
                 (sut/get-saved-artifacts)))))))
