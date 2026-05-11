(ns monkey.ci.events.edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.events.edn :as sut])
  (:import [java.io PipedInputStream PipedOutputStream OutputStreamWriter]))

(defn- make-pipe []
  (let [out (PipedOutputStream.)
        in  (PipedInputStream. out)
        w   (OutputStreamWriter. out)]
    {:in in :out out :writer w}))

(deftest read-edn-test
  (testing "reads EDN values from a reader and passes them to callback"
    (let [{:keys [in writer out]} (make-pipe)
          received (atom [])
          done     (promise)]
      (sut/read-edn in (sut/stop-at-eof
                        (fn [evt]
                          (swap! received conj evt)
                          true)))
      ;; Write two EDN forms then close
      (.write writer "{:type :test/event :value 1}\n")
      (.write writer "{:type :test/event :value 2}\n")
      (.flush writer)
      (.close out)
      ;; Give the daemon thread time to finish
      (Thread/sleep 200)
      (is (= 2 (count @received)))
      (is (= [{:type :test/event :value 1}
              {:type :test/event :value 2}]
             @received))))

  (testing "stop-at-eof stops when stream ends"
    (let [{:keys [in out]} (make-pipe)
          call-count (atom 0)]
      (.close out)
      (sut/read-edn in (sut/stop-at-eof
                        (fn [_] (swap! call-count inc) true)))
      (Thread/sleep 100)
      ;; EOF was received but stop-at-eof prevents callback from being called
      (is (zero? @call-count))))

  (testing "sleep-on-eof keeps looping on EOF"
    ;; Write an event after a short delay; the thread should still read it
    (let [{:keys [in out writer]} (make-pipe)
          received (atom [])
          ;; Use stop-on-file-delete with a path that does not exist as a stop condition
          non-existent (java.io.File. "/tmp/monkeyci-test-nonexistent-edn-sentinel")]
      (.delete non-existent)
      (sut/read-edn in (-> (fn [evt]
                             (swap! received conj evt)
                             true)
                           (sut/sleep-on-eof 50)
                           (sut/stop-at-eof)))
      (Thread/sleep 80)
      (.write writer "{:type :delayed}\n")
      (.flush writer)
      (.close out)
      (Thread/sleep 200)
      (is (= [{:type :delayed}] @received)))))

(deftest eof?-test
  (testing "true for the eof sentinel only"
    (is (sut/eof? ::sut/eof))
    (is (not (sut/eof? nil)))
    (is (not (sut/eof? {})))))
