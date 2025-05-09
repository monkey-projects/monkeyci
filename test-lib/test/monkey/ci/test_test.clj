(ns monkey.ci.test-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.test :as sut]))

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
