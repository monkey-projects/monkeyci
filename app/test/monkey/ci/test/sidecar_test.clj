(ns monkey.ci.test.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [monkey.ci
             [events :as e]
             [sidecar :as sut]
             [spec :as spec]]
            [monkey.ci.test.helpers :as h]))

(deftest poll-events
  (testing "dispatches events from file to bus"
    (h/with-tmp-dir dir
      (h/with-bus
        (fn [bus]
          (let [f (io/file dir "events.edn")
                evt {:type :test/event
                     :message "This is a test event"}
                recv (atom [])
                _ (e/register-handler bus (:type evt) (partial swap! recv conj))
                ctx {:event-bus bus
                     :args {:events-file f}
                     :sidecar {:poll-interval 10}}
                _ (spit f (prn-str evt))
                c (sut/poll-events ctx)]
            (is (spec/channel? c))
            (is (not= :timeout (h/wait-until #(pos? (count @recv)) 500)))
            (is (= evt (-> (first @recv)
                           (select-keys (keys evt)))))
            (is (true? (.delete f)) "delete the file to stop the sidecar")
            (is (= 0 (h/try-take c 500 :timeout))))))))

  (testing "reads events as they are posted"
    (h/with-tmp-dir dir
      (h/with-bus
        (fn [bus]
          (let [f (io/file dir "events.edn")
                evt {:type :test/event
                     :message "This is a test event"}
                recv (atom [])
                _ (e/register-handler bus (:type evt) (partial swap! recv conj))
                ctx {:event-bus bus
                     :args {:events-file f}
                     :sidecar {:poll-interval 10}}
                c (sut/poll-events ctx)]
            ;; Post the event after sidecar has started
            (is (nil? (spit f (prn-str evt))))
            (is (not= :timeout (h/wait-until #(pos? (count @recv)) 500)))
            (is (= evt (-> (first @recv)
                           (select-keys (keys evt)))))
            (is (true? (.delete f)))
            (is (= 0 (h/try-take c 500 :timeout)))))))))

(deftest restore-src
  (testing "nothing if no workspace in build"
    (let [ctx {}]
      (is (= ctx (sut/restore-src ctx)))))

  (testing "restores using the workspace path in build into checkout dir"
    (let [stored (atom {"path/to/workspace" "local/dir"})
          store (h/->FakeBlobStore stored)
          ctx {:build {:workspace "path/to/workspace"
                       :git {:dir "local/dir"}}
               :workspace {:store store}}]
      (is (true? (-> (sut/restore-src ctx)
                     (get-in [:workspace :restored?]))))
      (is (empty? @stored)))))

(deftest mark-start
  (testing "creates start file"
    (h/with-tmp-dir dir
      (let [start (io/file dir "start")
            ctx {:args {:start-file (.getCanonicalPath start)}}]
        (is (= ctx (sut/mark-start ctx)))
        (is (.exists start))))))
