(ns monkey.ci.test.commands-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [commands :as sut]
             [spec :as spec]]))

(deftest build
  (testing "invokes runner from context"
    (let [ctx {:runner (constantly :invoked)}]
      (is (= :invoked (sut/build ctx)))))

  (testing "adds build id"
    (is (re-matches #"build-\d+"
                    (-> {:runner (comp :build-id :build)}
                        (sut/build)))))

  (testing "sets git opts in build config"
    (is (= {:url "test-url"
            :branch "test-branch"
            :id "test-id"}
           (-> {:args {:git-url "test-url"
                       :branch "test-branch"
                       :commit-id "test-id"}
                :runner (comp :git :build)}
               (sut/build)))))

  (testing "defaults to `main` branch"
    (is (= "main" (-> {:args {:git-url "test-url"}
                       :runner (comp :branch :git :build)}
                      (sut/build))))))

(deftest http-server
  (testing "returns a channel"
    (is (spec/channel? (sut/http-server {})))))
