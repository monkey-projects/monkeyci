(ns monkey.ci.cli.print-events-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.cli.print-events :as sut]))

(deftest make-routes
  (testing "handles required event types"
    (is (not-empty (->> (sut/make-routes {})
                        (map first))))))
