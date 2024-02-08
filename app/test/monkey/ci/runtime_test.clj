(ns monkey.ci.runtime-test
  (:require [monkey.ci
             [config :as c]
             [runtime :as sut]]
            [clojure.test :refer [deftest testing is]]))

(deftest config->runtime
  (testing "creates default runtime from empty config"
    (is (map? (sut/config->runtime c/default-app-config)))))
