(ns monkey.ci.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
             [logging :as l]
             [sidecar :as sut]
             [spec :as spec]]
            [monkey.ci.helpers :as h]))

(deftest poll-events
  (testing "dispatches events from file to bus"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            recv (atom [])
            rt {:events {:poster (partial swap! recv conj)}
                :config {:sidecar {:poll-interval 10
                                   :events-file f}}}
            _ (spit f (prn-str evt))
            c (sut/poll-events rt)]
        (is (md/deferred? c))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)) "delete the file to stop the sidecar")
        (is (= 0 (deref c 500 :timeout))))))

  (testing "reads events as they are posted"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"}
            recv (atom [])
            rt {:events {:poster (partial swap! recv conj)}
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}}
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (true? (.delete f)))
        (is (= 0 (deref c 500 :timeout))))))

  (testing "stops when a terminating event is received"
    (h/with-tmp-dir dir
      (let [f (io/file dir "events.edn")
            evt {:type :test/event
                 :message "This is a test event"
                 :done? true}
            recv (atom [])
            rt {:events {:poster (partial swap! recv conj)}
                :config {:sidecar {:events-file f
                                   :poll-interval 10}}}
            c (sut/poll-events rt)]
        ;; Post the event after sidecar has started
        (is (nil? (spit f (prn-str evt))))
        (is (not= :timeout (h/wait-until #(not-empty @recv) 500)))
        (is (= evt (-> (first @recv)
                       (select-keys (keys evt)))))
        (is (= 0 (deref c 500 :timeout)))))))

(deftest restore-src
  (testing "nothing if no workspace in build"
    (let [rt {}]
      (is (= rt (sut/restore-src rt)))))

  (testing "restores using the workspace path in build into checkout dir"
    (let [stored (atom {"path/to/workspace" "local/dir"})
          store (h/->FakeBlobStore stored)
          rt {:build {:workspace "path/to/workspace"
                      :checkout-dir "local/dir"}
               :workspace {:store store}}]
      (is (true? (-> (sut/restore-src rt)
                     (get-in [:workspace :restored?]))))
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

  (testing "adds step config from args"
    (is (= {:key "value"}
           (-> (c/normalize-key :sidecar {:sidecar {}
                                          :args {:step-config {:key "value"}}})
               :sidecar
               :step-config)))))

(defrecord TestLogger [streams path]
  l/LogCapturer
  (handle-stream [_ _]
    (swap! streams conj path)))

(deftest upload-logs
  (testing "does nothing if no logger"
    (is (nil? (sut/upload-logs {} nil))))

  (testing "does nothing if no output files"
    (is (nil? (sut/upload-logs {} #(throw (ex-info "This should not be called" {}))))))

  (testing "opens output file and handles stream, stores using file name"
    (h/with-tmp-dir dir
      (let [c (atom [])
            f (io/file dir "test.txt")
            _ (spit f "This is a test file")]
        (is (nil? (sut/upload-logs {:stdout (.getCanonicalPath f)}
                                   (fn [p]
                                     (->TestLogger c p)))))
        (is (= [["test.txt"]] @c))))))

#_(deftest run
  (testing "restores required artifacts")

  (testing "stores generated artifacts")

  (testing "restores caches")

  (testing "saves caches"))
