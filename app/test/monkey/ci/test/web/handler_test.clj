(ns monkey.ci.test.web.handler-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.handler :as sut]))

(deftest handler
  (testing "is a fn"
    (is (fn? sut/handler))))
