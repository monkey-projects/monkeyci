(ns monkey.ci.containers-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [config :as c]
             [containers :as sut]
             [protocols :as p]
             [runtime :as rt]]
            [monkey.ci.helpers]))

(deftest normalize-key
  (testing "handles string type"
    (is (= :podman (-> (c/normalize-key :containers {:containers {:type "podman"}})
                       :containers
                       :type)))))

(deftest setup-runtime
  (let [conf (-> {:containers {:type :fake}}
                 (rt/setup-runtime :containers))]
    #_(testing "creates container runner"
      (is (p/container-runner? conf)))
    
    (testing "adds credit-consumer fn"
      (is (fn? (:credit-consumer conf))))))
