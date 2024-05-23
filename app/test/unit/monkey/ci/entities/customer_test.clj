(ns monkey.ci.entities.customer-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as c]
             [customer :as sut]
             [helpers :as h]]))

(deftest ^:sql customer-with-repos
  (testing "returns customer and its repos"
    (h/with-prepared-db conn
      (let [cust (c/insert-customer conn {:name "test customer"})
            repos (->> (range 3)
                       (map #(c/insert-repo conn {:customer-id (:id cust)
                                                  :display-id (str "repo-" %)
                                                  :name (str "repo-" %)}))
                       (doall))
            match (sut/customer-with-repos conn (c/by-cuid (:cuid cust)))]
        (is (some? match))
        (is (= (:id cust) (:id match)))
        (is (map? (:repos match)))
        (is (= (count repos) (count (:repos match))))))))
