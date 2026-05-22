(ns monkey.ci.utils.map-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.utils.map :as sut]))

(deftest prune-tree-test
  (testing "removes nil values from a flat map"
    (is (= {:a 1} (sut/prune-tree {:a 1 :b nil}))))

  (testing "removes empty collections"
    (is (= {:a 1} (sut/prune-tree {:a 1 :b [] :c {}}))))

  (testing "removes empty strings"
    (is (= {:a 1} (sut/prune-tree {:a 1 :b ""}))))

  (testing "recursively prunes nested maps"
    (is (= {:parent {:child {:name "test"}}}
           (sut/prune-tree {:parent {:child {:name "test" :extra nil}}}))))

  (testing "removes entirely-empty nested maps"
    (is (= {} (sut/prune-tree {:key nil}))))

  (testing "leaves non-map values untouched"
    (is (= {:a [1 2 3]} (sut/prune-tree {:a [1 2 3]})))))
