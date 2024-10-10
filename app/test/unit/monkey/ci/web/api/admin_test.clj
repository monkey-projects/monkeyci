(ns monkey.ci.web.api.admin-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.cuid :as cuid]
            [monkey.ci.web.api.admin :as sut]
            [monkey.ci.helpers :as h]))

(deftest issue-credits
  (h/with-memory-store st
    (let [cust (h/gen-cust)]
      (testing "creates new customer credits in db"
        (let [user (h/gen-user)
              resp (-> {:storage st}
                       (h/->req)
                       (h/with-identity user)
                       (assoc :parameters
                              {:path
                               {:customer-id (:id cust)}
                               :body
                               {:amount 1000M}})
                       (sut/issue-credits))]
          (is (= 201 (:status resp)))
          (is (cuid/cuid? (get-in resp [:body :id])))
          (is (= (:id user) (get-in resp [:body :user-id]))))))))
