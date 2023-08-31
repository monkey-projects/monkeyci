(ns monkey.ci.test.docker-test
  (:require [clojure.test :refer :all]
            [contajners.core :as cc]
            [monkey.ci
             [docker :as sut]
             [step-runner :as st]])
  (:import [java.io ByteArrayInputStream]))

(deftest make-client
  (testing "creates client with defaults"
    (with-redefs [cc/client identity]
      (let [r (sut/make-client :test-cat)]
        (is (= :test-cat (:category r)))
        (is (some? (:conn r)))))))

(deftest list-containesr
  (testing "invokes `:container-list` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerList (sut/list-containers :test-client {}))))))

(deftest inspect-container
  (testing "invokes `:container-inspect` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerInspect (sut/inspect-container :test-client "test-container")))))

  (testing "converts response to kebab-case"
    (with-redefs [cc/invoke (constantly {:TestKey "test-value"})]
      (is (= {:test-key "test-value"} (sut/inspect-container :test-client "test-container"))))))

(deftest create-container
  (testing "invokes `:container-create` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerCreate (sut/create-container :test-client "test-container" {})))))

  (testing "converts body to PascalCase"
    (with-redefs [cc/invoke (fn [_ p] (get-in p [:data :TestKey]))]
      (is (= "value" (sut/create-container :test-client "test-container" {:test-key "value"}))))))

(deftest start-container
  (testing "invokes `:container-start` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerStart (sut/start-container :test-client "test-container"))))))

(deftest delete-container
  (testing "invokes `:container-delete` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerDelete (sut/delete-container :test-client "container-id"))))))

(deftest container-logs
  (testing "invokes `:container-logs` call on client"
    (with-redefs [cc/invoke (fn [_ v]
                              (:op v))]
      (is (= :ContainerLogs (sut/container-logs :test-client "test-container")))))

  (testing "adds opts to default params"
    (with-redefs [cc/invoke (fn [_ v]
                              (:params v))]
      (is (= {:id "test-container"
              :follow false
              :stdout true
              :stderr true} (sut/container-logs :test-client "test-container" {:follow false
                                                                               :stderr true}))))))

(defn- str->stream [s]
  (-> s
      (.getBytes)
      (ByteArrayInputStream.)))

(deftest parse-log-stream
  (testing "parses line stream into seq"
    (let [input (str "      first\n"
                     "      second\n")
          stream (str->stream input)
          lines (sut/parse-log-stream stream)]
      (is (= 2 (count lines)))
      (is (= ["first\n" "second\n"] (->> lines
                                         (take 2)
                                         (map :message)))))))

(deftest ^:docker ^:integration run-container
  (testing "creates and starts a container, returns the result as a seq of strings"
    (let [c (sut/make-client :containers)]
      (is (= "This is a test"
             (->> {:image "alpine:latest"
                   :cmd ["echo" "This is a test"]}
                  (sut/run-container c (str "test-" (random-uuid)))
                  (first)))))))
    
(deftest run-step
  (testing "creates and starts a container to run the command"
    (let [r (-> (sut/->DockerConfig {})
                (st/run-step {}))]
      (is (some? r)))))
