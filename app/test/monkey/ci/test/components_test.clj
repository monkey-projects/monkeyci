(ns monkey.ci.test.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [components :as sut]
             [events :as e]]))

(deftest bus-component
  (testing "`start` creates a bus"
    (is (e/bus? (-> (sut/new-bus)
                    (c/start)))))

  (testing "`stop` destroys the bus"
    (is (nil? (-> (sut/new-bus)
                  (c/start)
                  (c/stop)
                  :pub)))))
