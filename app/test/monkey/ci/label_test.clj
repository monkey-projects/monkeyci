(ns monkey.ci.label-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.labels :as sut]))

(deftest apply-label-filters
  (testing "matches params with empty filter"
    (is (true? (sut/apply-label-filters
                {"test-label" "test-value"}
                {:label-filters []}))))

  (testing "matches params with single matching filter"
    (is (true? (sut/apply-label-filters
                {"test-label" "test-value"}
                {:label-filters [[{:label "test-label"
                                   :value "test-value"}]]}))))

  (testing "does not match when no matching filter"
    (is (not (sut/apply-label-filters
              {"test-label" "test-value"}
              {:label-filters [[{:label "test-label"
                                 :value "other-value"}]]}))))

  (testing "matches conjunction filter"
    (is (true? (sut/apply-label-filters
                {"first-label" "first-value"
                 "second-label" "second-value"}
                {:label-filters [[{:label "first-label"
                                   :value "first-value"}
                                  {:label "second-label"
                                   :value "second-value"}]]}))))

  (testing "does not conjunction filter when only one value matches"
    (is (not (sut/apply-label-filters
              {"first-label" "first-value"
               "second-label" "other-value"}
              {:label-filters [[{:label "first-label"
                                 :value "first-value"}
                                {:label "second-label"
                                 :value "second-value"}]]}))))
  
  (testing "matches disjunction filter"
    (is (true? (sut/apply-label-filters
                {"first-label" "first-value"
                 "second-label" "second-value"}
                {:label-filters [[{:label "first-label"
                                   :value "first-value"}]
                                 [{:label "other-label"
                                   :value "other-value"}]]})))))

