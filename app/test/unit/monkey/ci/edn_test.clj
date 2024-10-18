(ns monkey.ci.edn-test
  (:require [clojure.test :refer [deftest testing is]]
            [aero.core :as ac]
            [monkey.ci
             [edn :as sut]
             [version :as v]]
            [monkey.ci.helpers :as h])
  (:import java.io.StringReader))

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

(deftest version
  (testing "replaces `#version` with app version"
    (is (= (str "test-" (v/version))
           (ac/read-config (StringReader. "#join [ \"test-\" #version [] ]"))))))
