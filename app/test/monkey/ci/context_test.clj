(ns monkey.ci.context-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.context :as sut]))

(deftest get-sid
  (testing "returns build sid"
    (is (= ::build-sid (sut/get-sid {:build {:sid ::build-sid}}))))

  (testing "constructs sid from account"
    (is (= [:a :b :c] (sut/get-sid {:account
                                    {:customer-id :a
                                     :project-id :b
                                     :repo-id :c}}))))

  (testing "`nil` if incomplete account"
    (is (nil? (sut/get-sid {:account {:project-id "p"
                                      :repo-id "r"}})))
    (is (nil? (sut/get-sid {:account {:customer-id "c"
                                      :repo-id "r"}})))
    (is (nil? (sut/get-sid {:account {:customer-id "c"
                                      :project-id "p"}})))))

(deftest get-step-id
  (testing "combines build id, pipeline and step"
    (is (= "test-build-test-pipeline-1"
           (-> {:build {:build-id "test-build"}
                :pipeline {:name "test-pipeline"}
                :step {:index 1}}
               (sut/get-step-id)))))
  
  (testing "uses pipeline index when no name"
    (is (= "test-build-0-1"
           (-> {:build {:build-id "test-build"}
                :pipeline {:index 0}
                :step {:index 1}}
               (sut/get-step-id))))))
