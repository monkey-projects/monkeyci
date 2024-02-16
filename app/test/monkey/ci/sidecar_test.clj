(ns monkey.ci.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as c]
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
                           :start-file)))))
