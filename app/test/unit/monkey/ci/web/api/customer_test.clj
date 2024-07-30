(ns monkey.ci.web.api.customer-test
  (:require [clojure.test :refer [deftest testing is]]
            [java-time.api :as jt]
            [monkey.ci
             [helpers :as h]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.web.api.customer :as sut]))

(deftest recent-builds
  (h/with-memory-store st
    (testing "status `404` if customer does not exist"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:customer-id "non-existing"}})
                     (sut/recent-builds)
                     :status))))

    (testing "retrieves builds started in recent 24h"
      (let [repo (h/gen-repo)
            cust (-> (h/gen-cust)
                     (assoc :repos {(:id repo) repo}))
            now (jt/instant)
            old-build (-> (h/gen-build)
                          (assoc :customer-id (:id cust)
                                 :repo-id (:id repo)
                                 :start-time (-> now
                                                 (jt/minus (jt/days 2))
                                                 (jt/to-millis-from-epoch))))
            new-build (-> (h/gen-build)
                          (assoc :customer-id (:id cust)
                                 :repo-id (:id repo)
                                 :start-time (-> now
                                                 (jt/minus (jt/hours 2))
                                                 (jt/to-millis-from-epoch))))]
        (is (some? (st/save-customer st cust)))
        (is (some? (st/save-build st old-build)))
        (is (some? (st/save-build st new-build)))
        (is (= [new-build]
               (-> {:storage st}
                   (h/->req)
                   (assoc :parameters
                          {:path
                           {:customer-id (:id cust)}})
                   (sut/recent-builds)
                   :body)))))))
