(ns monkey.ci.test.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.commands :as sut]))

(deftest handle-command--http
  (testing "does nothing"
    (is (nil? (sut/handle-command {:command :http})))))

(deftest handle-command--build
  (testing "creates build event"
    (is (= :build/started (-> {:command :build
                               :args {:pipeline "test-pipeline"}}
                              (sut/handle-command)
                              :type))))

  (testing "adds args as root properties"
    (is (= "test-pipeline" (-> {:command :build
                                :pipeline "test-pipeline"}
                               (sut/handle-command)
                               :pipeline))))

  (testing "adds build id"
    (is (re-matches #"build-\d+"
                    (-> {:command :build
                         :pipeline "test-pipeline"}
                        (sut/handle-command)
                        :build-id)))))
