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

    (testing "retrieves builds"
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
        
        (testing "started in recent 24h"
          (is (= [new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:customer-id (:id cust)}})
                     (sut/recent-builds)
                     :body))))

        (testing "since specified timestamp"
          (is (= [old-build
                  new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:customer-id (:id cust)}
                             :query
                             {:since (-> now
                                         (jt/minus (jt/days 3))
                                         (jt/to-millis-from-epoch))}})
                     (sut/recent-builds)
                     :body))))))))

(deftest stats
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc :repos {(:id repo) repo}))
          req  (-> {:storage st}
                  (h/->req)
                  (assoc :parameters
                         {:path
                          {:customer-id (:id cust)}}))]
      
      (is (some? (st/save-customer st cust)))
      
      (testing "zero values if no builds"
        (is (every? (comp zero? :seconds)
                    (-> (sut/stats req)
                        :body
                        :stats
                        :elapsed-seconds))))

      (testing "contains default period"
        (let [{:keys [start end]} (-> (sut/stats req)
                                      :body
                                      :period)]
          (is (number? start))
          (is (number? end))))

      (testing "contains zone offset"
        (is (string? (-> (sut/stats req)
                         :body
                         :zone-offset))))

      (testing "client error if invalid zone offset"
        (is (= 400 (-> req
                       (assoc-in [:parameters :query :zone-offset] "invalid")
                       (sut/stats)
                       :status))))

      (testing "with builds"
        (letfn [(gen-build [start end creds]
                  (-> (h/gen-build)
                      (assoc :repo-id (:id repo)
                             :customer-id (:id cust)
                             :start-time start
                             :end-time end
                             :credits creds)))
                (ts [& args]
                  (-> (apply jt/offset-date-time args)
                      (jt/with-offset (jt/zone-offset "Z"))
                      (jt/to-millis-from-epoch)))]
          (is (->> [(gen-build (ts 2024 9 17 10) (ts 2024 9 17 10 5)  10)
                    (gen-build (ts 2024 9 17 11) (ts 2024 9 17 11 20) 20)
                    (gen-build (ts 2024 9 19 15) (ts 2024 9 19 15 30) 30)]
                   (map (partial st/save-build st))
                   (every? some?)))

          (let [stats (-> req
                          (assoc-in [:parameters :query] {:since (ts 2024 9 17)
                                                          :until (ts 2024 9 20)})
                          (sut/stats)
                          :body)]
            (testing "contains elapsed seconds per day"
              (is (= [{:date    (ts 2024 9 17)
                       :seconds (* 60 25)}
                      {:date    (ts 2024 9 18)
                       :seconds 0}
                      {:date    (ts 2024 9 19)
                       :seconds (* 60 30)}]
                     (->> stats
                          :stats
                          :elapsed-seconds))))
            
            (testing "contains consumed credits per day"
              (is (= [{:date    (ts 2024 9 17)
                       :credits 30}
                      {:date    (ts 2024 9 18)
                       :credits 0}
                      {:date    (ts 2024 9 19)
                       :credits 30}]
                     (->> stats
                          :stats
                          :consumed-credits))))))))))
