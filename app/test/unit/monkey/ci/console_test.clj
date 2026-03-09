(ns monkey.ci.console-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.console :as sut]))

(deftest rows
  (testing "returns lines from env"
    (is (= ::lines (sut/rows {:lines ::lines})))))

(deftest cols
  (testing "returns columns from env"
    (is (= ::cols (sut/cols {:columns ::cols})))))

(deftest render-next
  (testing "invokes renderer with state"
    (is (= ::new-state (-> {:renderer (constantly [[] ::new-state])}
                           (sut/render-next)
                           :state))))

  (testing "prints lines as returned by renderer"
    (let [w (java.io.StringWriter.)]
      (binding [*out* w]
        (is (some? (sut/render-next {:renderer (constantly [["test"] {}])})))
        (is (= "test" (.trim (.toString w)))))))

  (testing "moves cursor up number of previously printed lines"
    (let [w (java.io.StringWriter.)]
      (binding [*out* w]
        (is (some? (sut/render-next {:renderer (constantly [["test"] {}])
                                     :prev ["first" "second"]})))
        (is (.startsWith (.toString w) "\033[2A"))))))

(deftest progress-bar
  (testing "returns string of full width"
    (let [s (sut/progress-bar {:width 10 :value 0.5})]
      (is (string? s))
      (is (= 10 (count s)))))

  (testing "fills up to given value"
    (is (= "=====     "
           (sut/progress-bar {:width 10
                              :value 0.5
                              :filled-char \=}))))

  (testing "starts at given point"
    (is (= "  =====   "
           (sut/progress-bar {:width 10
                              :value 0.7
                              :start 0.2
                              :filled-char \=})))))
