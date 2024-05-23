(ns monkey.ci.integration-test.containers.docker-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers :as mc]
            [monkey.ci.containers.docker :as sut]))

(deftest ^:docker ^:integration run-container
  (testing "creates and starts a container, returns the result as a seq of strings"
    (let [c (sut/make-client :containers)]
      (is (= "This is a test"
             (->> {:image "alpine:latest"
                   :cmd ["echo" "This is a test"]}
                  (sut/run-container c (str "test-" (random-uuid)))
                  (first)))))))

(deftest ^:docker ^:integration containers-run-container
  (testing "runs jobs in container, returns exit code"
    (let [r (-> {:job {:container/image "alpine:latest"
                       :script ["`echo 'just testing'"]}
                 :containers {:type :docker}
                 :build {:build-id (str (random-uuid))}}
                (mc/run-container))]
      (is (some? r))
      (is (= 0 (:exit r)))
      (is (= 1 (count (:results r)))))))

