(ns monkey.ci.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.runtime :as sut]
            [monkey.ci.events.core]
            [monkey.ci.helpers :as h]))

(deftest from-config
  (testing "gets value from config"
    (is (= "test-val" ((sut/from-config :test-val)
                       {:config {:test-val "test-val"}})))))

(deftest post-events
  (letfn [(verify-time [evt checker]
            (let [{:keys [recv] :as e} (h/fake-events)]
              (is (some? (sut/post-events {:events e} evt)))
              (is (checker (-> @recv
                               first
                               :time)))))]
    
    (testing "adds time"
      (is (verify-time {:type :test-event} number?)))

    (testing "keeps provided time"
      (is (verify-time {:type :test-event :time 100} (partial = 100))))))
