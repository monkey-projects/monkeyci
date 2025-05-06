(ns monkey.ci.sidecar.core-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as ca]
             [cuid :as cuid]
             [logging :as l]
             [protocols :as p]
             [workspace :as ws]]
            [monkey.ci.sidecar.core :as sut]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]
            [monkey.ci.test.runtime.sidecar :as trs]))

(defrecord TestLogger [streams path]
  l/LogCapturer
  (handle-stream [_ _]
    (swap! streams conj path)))

(defn- wait-for-exit [c]
  (:exit (deref c 500 :timeout)))

(deftest poll-events
  (testing "dispatches events from file to bus"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            {:keys [mailman] :as rt} (-> (trs/make-test-rt)
                                         (trs/set-events-file f)
                                         (trs/set-poll-interval 10))
            _ (spit f (prn-str evt))
            c (sut/poll-events rt)]
        (is (md/deferred? c))
        (is (not= :timeout (h/wait-until #(not-empty (tm/get-posted mailman)) 500)))
        (is (= evt (-> (tm/get-posted mailman)
                       (first)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)) "delete the file to stop the sidecar")
        (is (= 0 (wait-for-exit c))))))

  (testing "reads events as they are posted"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            {:keys [mailman] :as rt} (-> (trs/make-test-rt)
                                         (trs/set-events-file f)
                                         (trs/set-poll-interval 10))
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty (tm/get-posted mailman)) 500)))
        (is (= evt (-> (tm/get-posted mailman)
                       (first)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)))
        (is (= 0 (wait-for-exit c))))))

  (testing "adds sid and job-id to events"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            sid (repeatedly 3 cuid/random-cuid)
            job {:id "test-job"}
            {:keys [mailman] :as rt} (-> (trs/make-test-rt)
                                         (trs/set-events-file f)
                                         (trs/set-poll-interval 10)
                                         (trs/set-job job)
                                         (trs/set-sid sid))
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty (tm/get-posted mailman)) 500)))
        (let [evt (first (tm/get-posted mailman))]
          (is (= sid (:sid evt)))
          (is (= (:id job) (:job-id evt))))
        (is (true? (.delete f)))
        (is (= 0 (wait-for-exit c))))))

  (testing "stops when a terminating event is received"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"
                 :done? true}
            {:keys [mailman] :as rt} (-> (trs/make-test-rt)
                                         (trs/set-events-file f)
                                         (trs/set-poll-interval 10))
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty (tm/get-posted mailman)) 500)))
        (is (= evt (-> (tm/get-posted mailman)
                       (first)
                       (select-keys (keys evt)))))
        (is (= 0 (wait-for-exit c))))))

  #_(testing "logs using job sid and file path"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            logfile (io/file dir "out.log")
            _ (spit logfile "test log")
            evt {:type :test/event
                 :message "This is a test event"
                 :stdout (.getCanonicalPath logfile)
                 :exit 0
                 :done? true}
            streams (atom [])
            rt (-> (trs/make-test-rt)
                   (trs/set-events-file f)
                   (trs/set-poll-interval 10)
                   (trs/set-log-maker (fn [_ path]
                                        (->TestLogger streams path)))
                   (trs/set-sid ["test-cust" "test-repo" "test-build"])
                   (trs/set-job {:id "test-job"}))
            c (sut/poll-events rt)]
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @streams) 500)))
        (is (= 0 (wait-for-exit c)))
        (is (= ["test-build" "test-job" "out.log"] (first @streams)))))))

(deftest mark-start
  (testing "creates start file"
    (h/with-tmp-dir dir
      (let [start (io/file dir "start")
            rt {:paths {:start-file (.getCanonicalPath start)}}]
        (is (= rt (sut/mark-start rt)))
        (is (.exists start)))))

  (testing "creates start file directory"
    (h/with-tmp-dir dir
      (let [start (io/file dir "sub/start")
            rt {:paths {:start-file (.getCanonicalPath start)}}]
        (is (= rt (sut/mark-start rt)))
        (is (.exists start))))))

#_(deftest upload-logs
  (testing "does nothing if no logger"
    (is (nil? (sut/upload-logs {} nil))))

  (testing "does nothing if no output files"
    (is (nil? (sut/upload-logs {} (fn [_]
                                    (throw (ex-info "This should not be called" {})))))))

  (testing "does nothing if files are empty"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.txt")]
        (is (some? (fs/create-file f)))
        (is (nil? (sut/upload-logs {:stdout (.getCanonicalPath f)}
                                   (fn [_]
                                     (throw (ex-info "This should not be called" {})))))))))

  (testing "opens output file and handles stream, stores using file name"
    (h/with-tmp-dir dir
      (let [c (atom [])
            f (io/file dir "test.txt")
            _ (spit f "This is a test file")]
        (is (nil? (sut/upload-logs {:stdout (.getCanonicalPath f)}
                                   (fn [p]
                                     (->TestLogger c p)))))
        (is (= [["test.txt"]] @c))))))

(defrecord SlowBlobStore [delay]
  p/BlobStore
  (save-blob [_ _ _ _]
    (log/info "Simulating slow blob storage...")
    (mt/in delay (constantly {::blob :saved})))
  (restore-blob [_ _ _]
    (md/success-deferred nil)))

(defrecord TestWorkspace []
  p/Workspace
  (restore-workspace [this sid]
    (md/success-deferred "ok")))

(deftest run
  (let [test-rt (trs/make-test-rt
                 {:workspace (->TestWorkspace)})]
    (with-redefs [sut/mark-start identity
                  sut/poll-events (fn [rt]
                                    (md/success-deferred (assoc rt :exit-code 0)))]
      
      (testing "restores src from workspace"
        (with-redefs [ws/restore (constantly {:stage ::restored})
                      sut/poll-events (fn [rt]
                                        (when (= ::restored (:stage rt))
                                          {:stage ::polling}))]
          (is (= ::polling (:stage @(sut/run (trs/make-test-rt)))))))

      (testing "restores and saves caches if configured"
        (h/with-tmp-dir dir
          (let [stored (atom {})
                path "test-path"
                _ (fs/create-file (fs/path dir path))
                sid (repeatedly 3 cuid/random-cuid)
                cache (ca/make-blob-repository (h/fake-blob-store stored) sid)
                r (sut/run
                    (merge
                     test-rt
                     {:sid sid
                      :checkout-dir dir
                      :logging {:maker (l/make-logger {})}
                      :cache cache
                      :job 
                      {:id "test-job"
                       :container/image "test-img"
                       :script ["first" "second"]
                       :caches [{:id "test-cache"
                                 :path path}]}}))]
            (is (map? (deref r 500 :timeout)))
            (is (not-empty @stored)))))
      
      (testing "restores artifacts if configured"
        (h/with-tmp-dir dir
          (let [stored (atom {"test-cust/test-repo/test-build/test-artifact.tgz" "/tmp/checkout"})
                build {:org-id "test-cust"
                       :repo-id "test-repo"
                       :build-id "test-build"
                       :checkout-dir "/tmp/checkout"
                       :workspace "test-ws"}
                sid (b/sid build)
                repo (art/make-blob-repository (h/fake-blob-store stored) sid)
                tr (sut/run
                     (merge
                      test-rt
                      {:containers {:type :podman}
                       :sid sid
                       :work-dir dir
                       :logging {:maker (l/make-logger {})}
                       :artifacts repo
                       :job 
                       {:id "test-job"
                        :container/image "test-img"
                        :script ["first" "second"]
                        :restore-artifacts [{:id "test-artifact"
                                             :path "test-path"}]}}))]
            (is (empty? @stored)))))

      (testing "saves artifacts if configured"
        (h/with-tmp-dir dir
          (let [stored (atom {})
                path "test-artifact"
                _ (fs/create-file (fs/path dir path))
                build {:org-id "test-cust"
                       :repo-id "test-repo"
                       :build-id "test-build"
                       :checkout-dir dir
                       :workspace "test-ws"}
                sid (b/sid build)
                repo (art/make-blob-repository (h/fake-blob-store stored) sid)
                r (sut/run
                    (merge
                     test-rt
                     {:containers {:type :podman}
                      :sid sid
                      :logging {:maker (l/make-logger {})}
                      :artifacts repo
                      :job 
                      {:id "test-job"
                       :container/image "test-img"
                       :script ["first" "second"]
                       :save-artifacts [{:id "test-artifact"
                                         :path path}]}}))]
            (is (not-empty @stored)))))

      (testing "waits until artifacts have been stored"
        ;; Set up blob saving so it takes a while
        (is (= ::timeout (-> {:artifacts (art/make-blob-repository (->SlowBlobStore 1000) [])
                              :workspace (->TestWorkspace)
                              :job {:id "test-job"
                                    :save-artifacts [{:id "test-artifact"
                                                      :path "test-path"}]}}
                             (trs/make-test-rt)
                             (sut/run)
                             (deref 100 ::timeout)))
            "expected timeout while waiting for blobs to save"))

      (testing "restores artifacts and caches before creating start file"
        (let [actions (atom [])
              mark-action (fn [t]
                            (fn [rt]
                              (swap! actions conj t)
                              rt))]
          (with-redefs [sut/mark-start (mark-action ::started)
                        art/restore-artifacts (mark-action ::restore-artifacts)]
            (is (some? (deref (sut/run test-rt))))
            (is (= [::restore-artifacts ::started] @actions)))))

      (testing "blocks until caches have been stored"
        ;; Set up blob saving so it takes a while
        (is (= ::timeout (-> test-rt
                             (merge {:cache (ca/make-blob-repository (->SlowBlobStore 1000) [])
                                     :job {:id "test-job"
                                           :caches [{:id "test-artifact"
                                                     :path "test-path"}]}})
                             (sut/run)
                             (deref 100 ::timeout)))))

      (testing "aborts on async error"
        (with-redefs [sut/poll-events (constantly (md/error-deferred (ex-info "test error" {})))]
          (h/with-tmp-dir dir
            (let [abort-file (io/file dir "abort")]
              (is (some? (-> test-rt
                             (trs/set-abort-file (str abort-file))
                             (sut/run)
                             deref
                             :exception)))
              (is (fs/exists? abort-file))))))

      (testing "aborts on exception thrown"
        (with-redefs [ws/restore (fn [_] (throw (ex-info "test error" {})))]
          (h/with-tmp-dir dir
            (let [abort-file (io/file dir "abort")]
              (is (some? (-> test-rt
                             (trs/set-abort-file (str abort-file))
                             (sut/run)
                             deref
                             :exception)))
              (is (fs/exists? abort-file)))))))))

