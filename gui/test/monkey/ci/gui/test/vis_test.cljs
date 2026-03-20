(ns monkey.ci.gui.test.vis-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [monkey.ci.gui.vis :as sut]))

(deftest jobs->network
  (testing "empty if no jobs"
    (is (= {:nodes [] :edges []}
           (sut/jobs->network []))))

  (let [jobs [{:id "root"}
              {:id "child-1"
               :dependencies ["root"]}
              {:id "child-2"
               :dependencies ["child-1"]}
              {:id "child-3"
               :dependencies ["child-1"]}]
        {:keys [nodes edges]} (sut/jobs->network jobs)]
    
    (testing "adds node per job"
      (is (= (count jobs) (count nodes))))

    (testing "adds edge per dependency"
      (is (= [{:from "child-1"
               :to "root"}]
             (filter (comp (partial = "root") :to) edges)))
      (is (= #{"child-2" "child-3"}
             (->> edges
                  (filter (comp (partial = "child-1") :to))
                  (map :from)
                  (set)))))))
