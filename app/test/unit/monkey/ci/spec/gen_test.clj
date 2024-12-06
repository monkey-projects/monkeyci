(ns monkey.ci.spec.gen-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.gen.alpha :as g]
            [monkey.ci.spec.gen :as sut]))

(deftest fixed-byte-array
  (is (every? (comp (partial = 10) count)
              (g/sample (sut/fixed-byte-array 10)))))
