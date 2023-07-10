(ns monkey.ci.test.docker-test
  (:require [clojure.test :refer :all]
            [contajners.core :as cc]
            [monkey.ci
             [docker :as sut]
             [step-runner :as st]]))

(deftest make-client
  (testing "creates client with defaults"
    (with-redefs [cc/client identity]
      (let [r (sut/make-client :test-cat)]
        (is (= :test-cat (:category r)))
        (is (some? (:conn r)))))))

(deftest create-container
  (testing "invokes `:container-create` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerCreate (sut/create-container :test-client "test-container" {})))))

  (testing "converts body to PascalCase"
    (with-redefs [cc/invoke (fn [_ p] (:data p))]
      (is (= {:TestKey "value"} (sut/create-container :test-client "test-container" {:test-key "value"}))))))

(deftest start-container
  (testing "invokes `:container-start` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerStart (sut/start-container :test-client "test-container"))))))
    
(deftest run-step
  (testing "creates and starts a container to run the command"
    (let [r (-> (sut/->DockerConfig {})
                (st/run-step {}))]
      (is (some? r)))))
