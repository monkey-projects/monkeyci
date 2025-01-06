(ns monkey.ci.web.api.join-request-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [cuid :as cuid]
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

(deftest approve-join-request
  (h/with-memory-store st
    (let [u (-> (h/gen-user)
                (assoc :customers []))
          cust (h/gen-cust)
          jr {:id (cuid/random-cuid)
              :user-id (:id u)
              :customer-id (:id cust)
              :status :pending}]

      (is (sid/sid? (st/save-user st u)))
      (is (sid/sid? (st/save-customer st cust)))
      (is (sid/sid? (st/save-join-request st jr)))
      (let [r (-> {:storage st}
                  (h/->req)
                  (assoc :parameters {:path {:request-id (:id jr)
                                             :customer-id (:id cust)}
                                      :body {:message "request approved"}})
                  (sut/approve-join-request))]
        (is (= 200 (:status r)))
        
        (testing "marks join request as approved"
          (is (= :approved (-> (st/find-join-request st (:id jr))
                               :status))))
        
        (testing "adds customer to user customer list"
          (is (= [(:id cust)] (-> (st/find-user st (:id u))
                                  :customers)))))

      (testing "fails when user not found"
        (is (sid/sid? (st/save-join-request st (assoc jr :user-id (cuid/random-cuid)))))
        (is (= 400 (-> {:storage st}
                       (h/->req)
                       (assoc :parameters {:path {:request-id (:id jr)
                                                  :customer-id (:id cust)}
                                           :body {:message "request approved"}})
                       (sut/approve-join-request)
                       :status)))))))

(deftest reject-join-request
  (h/with-memory-store st
    (let [u (-> (h/gen-user)
                (assoc :customers []))
          cust (h/gen-cust)
          jr {:id (cuid/random-cuid)
              :user-id (:id u)
              :customer-id (:id cust)
              :status :pending}]

      (is (sid/sid? (st/save-user st u)))
      (is (sid/sid? (st/save-customer st cust)))
      (is (sid/sid? (st/save-join-request st jr)))
      (let [r (-> {:storage st}
                  (h/->req)
                  (assoc :parameters {:path {:request-id (:id jr)
                                             :customer-id (:id cust)}
                                      :body {:message "request approved"}})
                  (sut/reject-join-request))]
        (is (= 200 (:status r)))
        
        (testing "marks join request as rejected"
          (is (= :rejected (-> (st/find-join-request st (:id jr))
                               :status))))
        
        (testing "does not add customer to user customer list"
          (is (empty? (-> (st/find-user st (:id u))
                          :customers))))))))
