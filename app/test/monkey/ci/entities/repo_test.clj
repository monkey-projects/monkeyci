(ns monkey.ci.entities.repo-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as ec]
             [helpers :as eh]
             [repo :as sut]]))

(deftest ^:sql repos-with-labels
  (eh/with-prepared-db conn
    (testing "selects repos and their labels"
      (let [cust (ec/insert-customer conn {:name "test customer"})
            repo (ec/insert-repo conn {:customer-id (:id cust)
                                       :display-id "test-repo"
                                       :name "test repo"})]
        (is (= 2 (count (ec/insert-repo-labels
                         conn
                         [{:repo-id (:id repo)
                           :name "first"
                           :value "test value"}
                          {:repo-id (:id repo)
                           :name "second"
                           :value "another value"}]))))
        (let [m (sut/repos-with-labels conn (ec/by-id (:id repo)))]
          (is (some? m))
          (is (= ["first" "second"]
                 (->> m first :labels (map :name)))))))))
