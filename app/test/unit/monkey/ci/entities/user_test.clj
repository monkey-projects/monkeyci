(ns monkey.ci.entities.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as ec]
             [user :as sut]]
            [monkey.ci.entities.helpers :as eh]))

(deftest select-user-orgs
  (eh/with-prepared-db conn
    (testing "retrieves all orgs linked to a user"
      (let [[org other-org] (->> (repeatedly 2 eh/gen-org)
                                 (map (partial ec/insert-org conn)))
            user (ec/insert-user conn (eh/gen-user))
            _ (ec/insert-user-org conn {:user-id (:id user)
                                        :org-id (:id org)})]
        (is (= [org] (sut/select-user-orgs conn (:cuid user))))))))
