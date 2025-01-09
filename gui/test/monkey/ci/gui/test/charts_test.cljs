(ns monkey.ci.gui.test.charts-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [monkey.ci.gui.charts :as sut]))

(deftest update-chart-data!
  (testing "replaces modified data in single dataset"
    (let [old (clj->js
               {:data
                {:datasets
                 [{:data [1 2 3]}]}})
          new {:data
               {:datasets
                [{:data [2 2 4]}]}}
          res (sut/update-chart-data! old new)]
      (is (= old res))
      (let [d (-> old (.-data) (.-datasets) (aget 0) (.-data))]
        (is (= 2 (aget d 0)))
        (is (= 2 (aget d 1)))
        (is (= 4 (aget d 2))))))

  (testing "replaces modified data in multiple dataset"
    (let [old (clj->js
               {:data
                {:datasets
                 [{:data [1 2 3]}
                  {:data [4 5 6]}]}})
          new {:data
               {:datasets
                [{:data [1 2 4]}
                 {:data [4 5 7]}]}}
          res (sut/update-chart-data! old new)]
      (is (= old res))
      (let [ds (-> old (.-data) (.-datasets))]
        (is (= 4 (-> ds (aget 0) (.-data) (aget 2))))
        (is (= 7 (-> ds (aget 1) (.-data) (aget 2)))))))

  (testing "removes value from data"
    (let [old (clj->js
               {:data
                {:datasets
                 [{:data [1 2 3]}]}})
          new {:data
               {:datasets
                [{:data [1 3]}]}}
          res (sut/update-chart-data! old new)]
      (is (= old res))
      (let [d (-> old (.-data) (.-datasets) (aget 0) (.-data))]
        (is (= 2 (count d)))
        (is (= 1 (aget d 0)))
        (is (= 3 (aget d 1))))))

  (testing "adds new values to data"
    (let [old (clj->js
               {:data
                {:datasets
                 [{:data [1 2]}]}})
          new {:data
               {:datasets
                [{:data [1 3 4]}]}}
          res (sut/update-chart-data! old new)]
      (is (= old res))
      (let [d (-> old (.-data) (.-datasets) (aget 0) (.-data))]
        (is (= 3 (count d)))
        (is (= 3 (aget d 1)))
        (is (= 4 (aget d 2))))))

  (testing "updates labels"
    (let [old (clj->js
               {:data
                {:labels ["first" "second"]}})
          new {:data
               {:labels ["first" "third" "fourth"]}}
          res (sut/update-chart-data! old new)]
      (is (= old res))
      (is (= 3 (count (-> old (.-data) (.-labels)))))
      (is (= ["first" "third" "fourth"]
             (js->clj (-> old (.-data) (.-labels))))))))
