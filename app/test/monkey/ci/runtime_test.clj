(ns monkey.ci.runtime-test
  (:require [monkey.ci
             [config :as c]
             [runtime :as sut]]
            [clojure.test :refer [deftest testing is]]))

(deftest config->runtime
  (testing "creates default runtime from empty config"
    (is (map? (sut/config->runtime c/default-app-config))))

  (testing "provides log maker"
    (let [rt (-> c/default-app-config
                 (assoc :logging {:type :file
                                  :dir "/tmp"})
                 (sut/config->runtime)
                 :logging
                 :maker)]
      (is (fn? rt))
      (is (not= rt (get-in sut/default-runtime [:logging :maker]))))))
