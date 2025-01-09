(ns monkey.ci.gui.test.charts-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [monkey.ci.gui.charts :as sut]
            [oops.core :as oc]))

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
        (let [d (-> old (oc/oget "data.datasets") (aget 0) (sut/get-data))]
          (is (= 2 (aget d 0)))
          (is (= 2 (aget d 1)))
          (is (= 4 (aget d 2))))))

  (testing "keeps dataset labels"
      (let [old (clj->js
                 {:data
                  {:datasets
                   [{:label "test data"
                     :data [1 2 3]}]}})
            new {:data
                 {:datasets
                  [{:label "test data"
                    :data [2 2 4]}]}}
            res (sut/update-chart-data! old new)]
        (is (= old res))
        (let [d (-> old (oc/oget "data.datasets") (aget 0))]
          (is (= "test data" (oc/oget d "label"))))))

  (testing "replaces modified data in multiple datasets"
    (let [old (clj->js
               {:data
                {:datasets
                 [{:data [1 2 3]}
                  {:data [4 5 6]}]}})
          new {:data
               {:datasets
                [{:data [1 2 4]}
                 {:data [4 5 7]}]}}]
      (is (= old (sut/update-chart-data! old new)))
      (let [ds (oc/oget old "data.datasets")]
        (is (some? ds))
        (is (= 4 (-> ds (aget 0) (sut/get-data) (aget 2))))
        (is (= 7 (-> ds (aget 1) (sut/get-data) (aget 2)))))))

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
        (let [d (-> old (oc/oget "data.datasets") (aget 0) (sut/get-data))]
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
        (let [d (-> old (oc/oget "data.datasets") (aget 0) (sut/get-data))]
          (is (= 3 (count d)))
          (is (= 3 (aget d 1)))
          (is (= 4 (aget d 2))))))

    (testing "updates labels"
      (let [old (clj->js
                 {:data
                  {:labels ["first" "second"]
                   :datasets []}})
            new {:data
                 {:labels ["first" "third" "fourth"]}}
            res (sut/update-chart-data! old new)]
        (is (= old res))
        (is (= 3 (count (oc/oget old "data.labels"))))
        (is (= ["first" "third" "fourth"]
               (js->clj (oc/oget old "data.labels")))))))
