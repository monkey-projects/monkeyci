(ns monkey.ci.edn-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.edn :as sut]
            [monkey.ci.helpers :as h]))

(deftest edn-conversion
  (testing "can convert objects to edn and back"
    (let [objs [{:key "value"}
                "test string"
                (range 10)
                {:events
                 {:type :jms
                  :client {:address "tcp:/test"}}}
                (let [pk (h/generate-private-key)]
                  {:private-key pk})]]
      (doseq [o objs]
        (is (= o (sut/edn-> (sut/->edn o)))
            (str "Could not convert:" o))))))
