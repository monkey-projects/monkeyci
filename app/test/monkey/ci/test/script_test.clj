(ns monkey.ci.test.script-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci
             [containers :as c]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]))

(defn with-listening-socket [f]
  (let [p (u/tmp-file "test-" ".sock")]
    (try
      (let [a (uds/make-address p)
            l (uds/listen-socket a)]
        (try
          (f a l)
          (finally
            (uds/close l))))
      (finally
        (uds/delete-address p)))))

(deftest exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"}))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-script"}))))
  
  (testing "connects to listening socket if specified"
    (with-listening-socket
      (fn [addr listener]
        (let [in (ca/chan 1)]
          ;; Accept connection and read into the channel
          (doto (Thread. (fn []
                           (-> (uds/accept listener)
                               (sa/read-onto-channel in))
                           (log/debug "Incoming connection accepted")))
            (.start))
          ;; Execute the script, we expect at least one incoming event
          (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"
                                              :event-socket (str (.getPath addr))})))
          ;; Try to read a message on the channel
          (is (= in (-> (ca/alts!! [in (ca/timeout 500)])
                        (second)))))))))

(deftest run-pipelines
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-pipelines {} []))))

  (testing "success if all steps succeed"
    (is (bc/success? (->> [(bc/pipeline {:steps [(constantly bc/success)]})]
                          (sut/run-pipelines {})))))

  (testing "runs a single pipline"
    (is (bc/success? (->> (bc/pipeline {:name "single"
                                        :steps [(constantly bc/success)]})
                          (sut/run-pipelines {})))))
  
  (testing "fails if a step fails"
    (is (bc/failed? (->> [(bc/pipeline {:steps [(constantly bc/failure)]})]
                         (sut/run-pipelines {})))))

  (testing "success if step returns `nil`"
    (is (bc/success? (->> (bc/pipeline {:name "nil"
                                        :steps [(constantly nil)]})
                          (sut/run-pipelines {})))))

  (testing "runs pipeline by name, if given"
    (is (bc/success? (->> [(bc/pipeline {:name "first"
                                         :steps [(constantly bc/success)]})
                           (bc/pipeline {:name "second"
                                         :steps [(constantly bc/failure)]})]
                          (sut/run-pipelines {:pipeline "first"}))))))

(defmethod c/run-container :test [ctx]
  {:test-result :run-from-test
   :exit 0})

(deftest pipeline-run-step
  (testing "executes function directly"
    (is (= :ok (sut/run-step (constantly :ok) {}))))

  (testing "executes action from map"
    (is (= :ok (sut/run-step {:action (constantly :ok)} {}))))

  (testing "executes in container if configured"
    (let [config {:container/image "test-image"}
          r (sut/run-step config {:container-runner :test})]
      (is (= :run-from-test (:test-result r)))
      (is (bc/success? r)))))
