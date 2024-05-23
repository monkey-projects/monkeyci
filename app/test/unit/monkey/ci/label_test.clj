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

  (testing "does not match conjunction filter when only one value matches"
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

(deftest reconcile-labels
  (testing "all new labels should be inserted"
    (let [labels [{:name "new label"
                   :value "new value"}]]
      (is (= {:insert labels}
             (sut/reconcile-labels [] labels)))))

  (testing "all removed labels should be deleted"
    (let [labels [{:id 1
                   :name "new label"
                   :value "new value"}]]
      (is (= {:delete labels}
             (sut/reconcile-labels labels [])))))

  (testing "ignores unchanged labels"
    (is (empty? (sut/reconcile-labels [{:id 1
                                        :name "test label"
                                        :value "unchanged"}]
                                      [{:name "test label"
                                        :value "unchanged"}]))))

  (testing "changed labels should be updated"
    (is (= {:update [{:id 1
                      :name "test label"
                      :value "updated"}]}
           (sut/reconcile-labels [{:id 1
                                   :name "test label"
                                   :value "original"}]
                                 [{:name "test label"
                                   :value "updated"}]))))

  (testing "combines updated and new"
    (let [new  {:name "new label"
                :value "new value"}
          orig {:id 1
                :name "existing label"
                :value "original value"}
          upd  {:name "existing label"
                :value "updated value"}]
      (is (= {:insert [new]
              :update [{:id 1
                        :name "existing label"
                        :value "updated value"}]}
             (sut/reconcile-labels [orig] [upd new])))))

  (testing "combines updated and deleted"
    (let [del  {:id 2
                :name "removed label"
                :value "removed value"}
          orig {:id 1
                :name "existing label"
                :value "original value"}
          upd  {:name "existing label"
                :value "updated value"}]
      (is (= {:delete [del]
              :update [{:id 1
                        :name "existing label"
                        :value "updated value"}]}
             (sut/reconcile-labels [orig del] [upd]))))))
