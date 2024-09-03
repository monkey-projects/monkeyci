(ns monkey.ci.runtime.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [artifacts :as art]
             [protocols :as p]]
            [monkey.ci.config.script :as cs]
            [monkey.ci.runtime.script :as sut]))

(def test-config (-> cs/empty-config
                     (cs/set-api {:url "http://test"
                                  :token "test-token"})
                     (cs/set-build {:build-id "test-build"})))

(deftest make-system
  (testing "creates system map with runtime"
    (is (sut/runtime? (:runtime (sut/make-system test-config))))))

(deftest with-runtime
  (testing "invokes target function with runtime"
    (is (= ::invoked (sut/with-runtime test-config (constantly ::invoked)))))

  (testing "drops workspace from runtime"
    (is (nil? (sut/with-runtime test-config :workspace))))

  (testing "adds artifact repo"
    (is (art/repo? (sut/with-runtime
                     test-config
                     :artifacts))))

  (testing "adds cache repo"
    (is (art/repo? (sut/with-runtime
                     test-config
                     :cache))))

  (testing "adds container runner"
    (is (p/container-runner? (sut/with-runtime
                               test-config
                               :containers)))))
