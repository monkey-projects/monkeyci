(ns monkey.ci.test-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci
             [api :as api]
             [test :as sut]]))

(deftest test-ctx
  (testing "is a context map"
    (is (map? sut/test-ctx)))

  (testing "contains a basic build"
    (is (some? (:build sut/test-ctx)))))

(deftest with-tmp-checkout-dir
  (testing "sets temp checkout dir in context"
    (is (some? (-> sut/test-ctx
                   (sut/with-tmp-checkout-dir)
                   (sut/checkout-dir))))))

(deftest with-tmp-dir
  (testing "invokes body in tmp dir, deletes dir afterward"
    (let [r (sut/with-tmp-dir dir
              (if (fs/exists? dir) dir ::error))]
      (is (not= ::error r))
      (is (not (fs/exists? r))))))

(deftest set-main-branch
  (testing "sets main branch in context"
    (is (= "test-main"
           (-> sut/test-ctx
               (sut/set-main-branch "test-main")
               (api/main-branch))))))
