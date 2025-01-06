(ns monkey.ci.web.api.join-request-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.web.api.join-request :as sut]
            [monkey.ci.helpers :as h]))

(deftest search-join-requests
  (testing "for user"
    (h/with-memory-store st
      (let [u (h/gen-user)
            cust (h/gen-cust)]
        (is (sid/sid? (st/save-user st u)))
        (is (sid/sid? (st/save-customer st cust)))
        (is (sid/sid? (st/save-join-request st {:user-id (:id u)
                                                :customer-id (:id cust)})))
        
        (testing "adds customer name"
          (let [r (-> {:storage st}
                      (h/->req)
                      (assoc-in [:parameters :path :user-id] (:id u))
                      (sut/search-join-requests))]
            (is (= 200 (:status r)))
            (is (= (:name cust)
                   (-> r :body first :customer :name)))))))))
