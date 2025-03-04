(ns monkey.ci.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.runtime :as sut]))

(deftest from-config
  (testing "gets value from config"
    (is (= "test-val" ((sut/from-config :test-val)
                       {:config {:test-val "test-val"}})))))

