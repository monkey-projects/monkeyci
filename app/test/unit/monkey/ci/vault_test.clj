(ns monkey.ci.vault-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [cuid :as cuid]
             [vault :as sut]]))

(deftest cuid->iv
  (testing "generates iv from cuid"
    (is (= 16 (count (sut/cuid->iv (cuid/random-cuid))))))

  (testing "takes last 6 bits"
    (is (->> (repeat cuid/cuid-length 0x40)
             (byte-array)
             (String.)
             (sut/cuid->iv)
             (seq)
             (every? zero?)))))
