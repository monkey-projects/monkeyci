(ns monkey.ci.entities.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as ec]
             [user :as sut]]
            [monkey.ci.entities.helpers :as eh]))

(deftest select-user-customers
  (eh/with-prepared-db conn
    (testing "retrieves all customers linked to a user"
      (let [[cust other-cust] (->> (repeatedly 2 eh/gen-customer)
                                   (map (partial ec/insert-customer conn)))
            user (ec/insert-user conn (eh/gen-user))
            _ (ec/insert-user-customer conn {:user-id (:id user)
                                             :customer-id (:id cust)})]
        (is (= [cust] (sut/select-user-customers conn (:cuid user))))))))
