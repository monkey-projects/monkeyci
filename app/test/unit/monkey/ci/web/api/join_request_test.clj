(ns monkey.ci.web.api.join-request-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [cuid :as cuid]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.test
             [storage :as ts]
             [web :as tw]]
            [monkey.ci.web.api.join-request :as sut]))

(deftest search-join-requests
  (testing "for user"
    (ts/with-memory-store st
      (let [u (ts/gen-user)
            org (ts/gen-org)]
        (is (sid/sid? (st/save-user st u)))
        (is (sid/sid? (st/save-org st org)))
        (is (sid/sid? (st/save-join-request st {:user-id (:id u)
                                                :org-id (:id org)})))
        
        (testing "adds org name"
          (let [r (-> {:storage st}
                      (tw/->req)
                      (assoc-in [:parameters :path :user-id] (:id u))
                      (sut/search-join-requests))]
            (is (= 200 (:status r)))
            (is (= (:name org)
                   (-> r :body first :org :name)))))))))

(deftest approve-join-request
  (ts/with-memory-store st
    (let [u (-> (ts/gen-user)
                (assoc :orgs []))
          org (ts/gen-org)
          jr {:id (cuid/random-cuid)
              :user-id (:id u)
              :org-id (:id org)
              :status :pending}]

      (is (sid/sid? (st/save-user st u)))
      (is (sid/sid? (st/save-org st org)))
      (is (sid/sid? (st/save-join-request st jr)))
      (let [r (-> {:storage st}
                  (tw/->req)
                  (assoc :parameters {:path {:request-id (:id jr)
                                             :org-id (:id org)}
                                      :body {:message "request approved"}})
                  (sut/approve-join-request))]
        (is (= 200 (:status r)))
        
        (testing "marks join request as approved"
          (is (= :approved (-> (st/find-join-request st (:id jr))
                               :status))))
        
        (testing "adds org to user org list"
          (is (= [(:id org)] (-> (st/find-user st (:id u))
                                 :orgs)))))

      (testing "fails when user not found"
        (is (sid/sid? (st/save-join-request st (assoc jr :user-id (cuid/random-cuid)))))
        (is (= 400 (-> {:storage st}
                       (tw/->req)
                       (assoc :parameters {:path {:request-id (:id jr)
                                                  :org-id (:id org)}
                                           :body {:message "request approved"}})
                       (sut/approve-join-request)
                       :status)))))))

(deftest reject-join-request
  (ts/with-memory-store st
    (let [u (-> (ts/gen-user)
                (assoc :orgs []))
          org (ts/gen-org)
          jr {:id (cuid/random-cuid)
              :user-id (:id u)
              :org-id (:id org)
              :status :pending}]

      (is (sid/sid? (st/save-user st u)))
      (is (sid/sid? (st/save-org st org)))
      (is (sid/sid? (st/save-join-request st jr)))
      (let [r (-> {:storage st}
                  (tw/->req)
                  (assoc :parameters {:path {:request-id (:id jr)
                                             :org-id (:id org)}
                                      :body {:message "request approved"}})
                  (sut/reject-join-request))]
        (is (= 200 (:status r)))
        
        (testing "marks join request as rejected"
          (is (= :rejected (-> (st/find-join-request st (:id jr))
                               :status))))
        
        (testing "does not add org to user org list"
          (is (empty? (-> (st/find-user st (:id u))
                          :orgs))))))))
