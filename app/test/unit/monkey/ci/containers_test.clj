(ns monkey.ci.containers-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [config :as c]
             [containers :as sut]
             [runtime :as rt]]))

(deftest normalize-key
  (testing "handles string type"
    (is (= :podman (-> (c/normalize-key :containers {:containers {:type "podman"}})
                       :containers
                       :type)))))

(deftest setup-runtime
  (testing "adds credit-consumer fn"
    (is (fn? (-> {:containers {}}
                 (rt/setup-runtime :containers)
                 :credit-consumer)))))
