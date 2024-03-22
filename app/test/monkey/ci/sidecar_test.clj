(ns monkey.ci.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [monkey.ci
             [artifacts :as art]
             [blob :as b]
             [config :as c]
             [logging :as l]
             [sidecar :as sut]
             [spec :as spec]]
            [monkey.ci.helpers :as h]))

(defrecord TestLogger [streams path]
  l/LogCapturer
  (handle-stream [_ _]
    (swap! streams conj path)))

(defn- wait-for-exit [c]
  (:exit-code (deref c 500 :timeout)))

(deftest poll-events
  (testing "dispatches events from file to bus"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            {:keys [recv] :as e} (h/fake-events)
            rt {:events e
                :config {:sidecar {:poll-interval 10
                                   :events-file f}}}
            _ (spit f (prn-str evt))
            c (sut/poll-events rt)]
        (is (md/deferred? c))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)) "delete the file to stop the sidecar")
        (is (= 0 (wait-for-exit c))))))

  (testing "reads events as they are posted"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            {:keys [recv] :as e} (h/fake-events)
            rt {:events e
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}}
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)))
        (is (= 0 (wait-for-exit c))))))

  (testing "adds sid and job to events"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            {:keys [recv] :as e} (h/fake-events)
            sid (repeatedly 3 random-uuid)
            job {:id "test-job"}
            rt {:events e
                :job job
                :build {:sid sid}
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}}
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (let [evt (first @recv)]
          (is (= sid (:sid evt)))
          (is (= job (:job evt))))
        (is (true? (.delete f)))
        (is (= 0 (wait-for-exit c))))))

  (testing "stops when a terminating event is received"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"
                 :done? true}
            {:keys [recv] :as e} (h/fake-events)
            rt {:events e
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}}
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (= 0 (wait-for-exit c))))))

  (testing "logs using job sid and file path"
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
            rt {:events {:poster (constantly true)}
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}
                :build {:build-id "test-build"}
                :job {:id "test-job"}
                :logging {:maker (fn [_ path]
                                   (->TestLogger streams path))}}
            c (sut/poll-events rt)]
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @streams) 500)))
        (is (= 0 (wait-for-exit c)))
        (is (= ["test-build" "test-job" "out.log"] (first @streams)))))))

(deftest restore-src
  (testing "nothing if no workspace in build"
    (let [rt {}]
      (is (= rt (sut/restore-src rt)))))

  (testing "restores using the workspace path in build into checkout dir"
    (let [stored (atom {"path/to/workspace" "local"})
          store (h/strict-fake-blob-store stored)
          rt {:build {:workspace "path/to/workspace"
                      :checkout-dir "local/dir"}
              :workspace store}]
      (is (true? (-> (sut/restore-src rt)
                     (deref)
                     (get-in [:build :workspace/restored?]))))
      (is (empty? @stored)))))

(deftest mark-start
  (testing "creates start file"
    (h/with-tmp-dir dir
      (let [start (io/file dir "start")
            rt {:config
                {:sidecar {:start-file (.getCanonicalPath start)}}}]
        (is (= rt (sut/mark-start rt)))
        (is (.exists start)))))

  (testing "creates start file directory"
    (h/with-tmp-dir dir
      (let [start (io/file dir "sub/start")
            rt {:config
                {:sidecar {:start-file (.getCanonicalPath start)}}}]
        (is (= rt (sut/mark-start rt)))
        (is (.exists start))))))

(deftest normalize-key
  (testing "adds events file from args"
    (is (= "test-file" (-> (c/normalize-key :sidecar {:sidecar {}
                                                      :args {:events-file "test-file"}})
                           :sidecar
                           :events-file))))

  (testing "adds start file from args"
    (is (= "test-file" (-> (c/normalize-key :sidecar {:sidecar {}
                                                      :args {:start-file "test-file"}})
                           :sidecar
                           :start-file))))

  (testing "adds job config from args"
    (is (= {:key "value"}
           (-> (c/normalize-key :sidecar {:sidecar {}
                                          :args {:job-config {:key "value"}}})
               :sidecar
               :job-config))))

  (testing "reads log config if specified"
    (h/with-tmp-dir dir
      (let [p (io/file dir "log-test.xml")
            file-contents "test-config-xml"]
        (is (nil? (spit p file-contents)))
        (is (= file-contents (-> (c/normalize-key :sidecar {:sidecar {:log-config (.getCanonicalPath p)}})
                                 :sidecar
                                 :log-config)))))))

(deftest upload-logs
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
  b/BlobStore
  (save [_ _ _]
    (log/info "Simulating slow blob storage...")
    (mt/in delay (constantly {::blob :saved})))
  (restore [_ _ _]
    (md/success-deferred nil)))

(deftest run
  (with-redefs [sut/mark-start identity
                sut/poll-events (fn [rt]
                                  (md/success-deferred (assoc rt :exit-code 0)))]
    (testing "adds job config to runtime"
      (is (= "test-job" (-> {:config {:sidecar {:job-config {:id "test-job"}}}}
                            (sut/run)
                            (deref)
                            :id))))
    
    (testing "restores src from workspace"
      (with-redefs [sut/restore-src (constantly {:stage ::restored})
                    sut/poll-events (fn [rt]
                                      (when (= ::restored (:stage rt))
                                        {:stage ::polling}))]
        (is (= ::polling (:stage @(sut/run {}))))))

    (testing "restores and saves caches if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              path "test-path"
              _ (fs/create-file (fs/path dir path))
              cache (h/fake-blob-store stored)
              r (sut/run
                  {:containers {:type :podman}
                   :build {:build-id "test-build"
                           :checkout-dir dir}
                   :job {:name "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :caches [{:id "test-cache"
                                   :path path}]}
                   :logging {:maker (l/make-logger {})}
                   :cache cache})]
          (is (map? (deref r 500 :timeout)))
          (is (not-empty @stored)))))
    
    
    (testing "restores artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {"test-cust/test-build/test-artifact.tgz" "/tmp/checkout"})
              store (h/fake-blob-store stored)
              r (sut/run
                  {:containers {:type :podman}
                   :build {:build-id "test-build"
                           :sid ["test-cust" "test-build"]
                           :checkout-dir "/tmp/checkout"}
                   :work-dir dir
                   :job {:name "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :restore-artifacts [{:id "test-artifact"
                                              :path "test-path"}]}
                   :logging {:maker (l/make-logger {})}
                   :artifacts store})]
          (is (empty? @stored)))))

    (testing "saves artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              path "test-artifact"
              _ (fs/create-file (fs/path dir path))
              store (h/fake-blob-store stored)
              r (sut/run
                  {:containers {:type :podman}
                   :build {:build-id "test-build"
                           :sid ["test-cust" "test-build"]
                           :checkout-dir dir}
                   :job {:name "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :save-artifacts [{:id "test-artifact"
                                           :path path}]}
                   :logging {:maker (l/make-logger {})}
                   :artifacts store})]
          (is (not-empty @stored)))))

    (testing "waits until artifacts have been stored"
      ;; Set up blob saving so it takes a while
      (is (= ::timeout (-> {:job {:save-artifacts [{:id "test-artifact"
                                                    :path "test-path"}]}
                            :artifacts (->SlowBlobStore 1000)}
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
          (is (some? (deref (sut/run {}))))
          (is (= [::restore-artifacts ::started] @actions)))))

    (testing "blocks until caches have been stored"
      ;; Set up blob saving so it takes a while
      (is (= ::timeout (-> {:job {:caches [{:id "test-artifact"
                                            :path "test-path"}]}
                            :cache (->SlowBlobStore 1000)}
                           (sut/run)
                           (deref 100 ::timeout)))))

    (testing "aborts on error")))
