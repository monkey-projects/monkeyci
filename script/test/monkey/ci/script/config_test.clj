(ns monkey.ci.script.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.script.config :as sut]))

(deftest set-job-filter
  (testing "sets job filter"
    (let [f ["test-filter"]]
      (is (= f (-> sut/empty-config
                   (sut/set-job-filter f)
                   (sut/job-filter))))))

  (testing "does not set filter if `nil`"
    (is (not (contains? (-> sut/empty-config
                            (sut/set-job-filter nil))
                        sut/job-filter)))))

