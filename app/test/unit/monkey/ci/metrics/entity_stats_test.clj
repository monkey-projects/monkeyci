(ns monkey.ci.metrics.entity-stats-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics
             [entity-stats :as sut]
             [prometheus :as p]]
            [monkey.ci.storage :as st]
            [monkey.ci.test.helpers :as h]))

(defn- gauge-val [g]
  (.. g
      (collect)
      (getDataPoints)
      (get 0)
      (getValue)))

(deftest user-count-gauge
  (h/with-memory-store st
    (testing "provides number of users in storage"
      (let [reg (p/make-registry)
            g (sut/user-count-gauge st reg)]
        (is (= 0.0 (gauge-val g)))
        (is (some? (st/save-user st (h/gen-user))))
        (is (= 1.0 (gauge-val g)))))))

(deftest org-count-gauge
  (h/with-memory-store st
    (testing "provides number of orgs in storage"
      (let [reg (p/make-registry)
            g (sut/org-count-gauge st reg)]
        (is (= 0.0 (gauge-val g)))
        (is (some? (st/save-org st (h/gen-org))))
        (is (= 1.0 (gauge-val g)))))))
