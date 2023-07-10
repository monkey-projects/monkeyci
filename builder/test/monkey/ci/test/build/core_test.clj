(ns monkey.ci.test.build.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci.build.core :as sut]))

(deftest pipeline
  (testing "does nothing if no steps"
    (is (= :success (:status (sut/pipeline {:steps []})))))

  (testing "executes single step"
    (let [executed? (atom nil)
          step (fn [& args]
                 (reset! executed? true)
                 {:status :success})]
      (is (= :success (:status (sut/pipeline {:steps [step]}))))
      (is (true? @executed?))))

  (testing "stops on failed step"
    (let [executed? (atom nil)
          step (fn [r rv]
                 (fn [& args]
                   (reset! executed? r)
                   {:status rv}))
          p {:steps [(step :first :failure)
                     (step :second :success)]}]
      (is (= :failure (:status (sut/pipeline p))))
      (is (= :first @executed?)))))
