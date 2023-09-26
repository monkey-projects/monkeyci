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

  (testing "sets git url in build config"
    (is (= "test-url" (-> {:args {:git-url "test-url"}
                           :runner (comp :url :git :build)}
                          (sut/build))))))

(deftest http-server
  (testing "returns a channel"
    (is (spec/channel? (sut/http-server {})))))
