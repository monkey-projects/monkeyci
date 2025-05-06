(ns monkey.ci.web.api.customer-test
  (:require [clojure.test :refer [deftest is testing]]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.customer :as sut]))

(deftest get-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id cust)))]
      (is (sid/sid? (st/save-customer st cust)))
      (is (= cust (:body (sut/get-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (h/with-path-param :org-id "nonexisting")
                   (sut/get-customer)
                   :status))))

  (testing "converts repo map into list"
    (let [cust {:id (st/new-id)
                :name "Customer with projects"}
          repo {:id "test-repo"
                :name "Test repository"
                :org-id (:id cust)}
          {st :storage :as rt} (trt/test-runtime)]
      (is (sid/sid? (st/save-customer st cust)))
      (is (sid/sid? (st/save-repo st repo)))
      (let [r (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id cust))
                  (sut/get-customer)
                  :body)
            repos (-> r :repos)]
        (is (some? repos))
        (is (not (map? repos)))
        (is (= (select-keys repo [:id :name])
               (first repos)))))))

(deftest create-customer
  (testing "returns created customer with id"
    (let [r (-> (trt/test-runtime)
                (h/->req)
                (h/with-body {:name "new customer"})
                (sut/create-customer)
                :body)]
      (is (= "new customer" (:name r)))
      (is (string? (:id r)))))

  (let [user (-> (h/gen-user)
                 (dissoc :customers))
        {st :storage :as rt} (trt/test-runtime)
        r (-> rt
              (h/->req)
              (h/with-body {:name "another customer"})
              (h/with-identity user)
              (sut/create-customer)
              :body)]
    (is (some? r))

    (testing "links current user to customer"
      (is (= [(:id r)] (-> (st/find-user st (:id user))
                           :customers)))
      (is (= [r] (st/list-user-customers st (:id user)))))

    (let [cust-id (:id r)]
      (testing "creates subscription"
        (is (= 1 (-> (st/list-customer-credit-subscriptions st cust-id)
                     (count)))))

      (testing "issues credits"
        (let [cc (st/list-customer-credits st cust-id)]
          (is (= 1 (count cc)))
          (is (some? (->> cc
                          first
                          :subscription-id
                          (vector cust-id)
                          (st/find-credit-subscription st)))))))))

(deftest update-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id cust))
                  (h/with-body {:name "updated"}))]
      (is (sid/sid? (st/save-customer st cust)))
      (is (= {:id (:id cust)
              :name "updated"}
             (:body (sut/update-customer req))))))

  (testing "can update with repos"
    (let [cust {:id "other-cust"
                :name "Other customer"
                :repos {"test-repo" {:id "test-repo"}}}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id cust))
                  (h/with-body {:name "updated"}))]
      (is (sid/sid? (st/save-customer st cust)))
      (is (= "updated" (-> req
                           (sut/update-customer)
                           :body
                           :name)))
      (is (= ["test-repo"] (-> (st/find-customer st (:id cust))
                               :repos
                               (keys))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (h/with-path-param :org-id "nonexisting")
                   (sut/update-customer)
                   :status)))))

(deftest search-customers
  (let [{st :storage :as rt} (trt/test-runtime)
        cust {:id (st/new-id)
              :name "Test customer"}
        sid (st/save-customer st cust)]
    (testing "retrieves customer by id"
      (is (= [cust]
             (-> rt
                 (h/->req)
                 (h/with-query-param :id (:id cust))
                 (sut/search-customers)
                 :body))))
    
    (testing "searches customers by name"
      (is (= [cust]
             (-> rt
                 (h/->req)
                 (h/with-query-param :name "Test")
                 (sut/search-customers)
                 :body))))
    
    (testing "fails if no query params given"
      (is (= 400
             (-> rt
                 (h/->req)
                 (sut/search-customers)
                 :status))))))

(deftest recent-builds
  (h/with-memory-store st
    (testing "status `404` if customer does not exist"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id "non-existing"}})
                     (sut/recent-builds)
                     :status))))

    (testing "retrieves builds"
      (let [repo (h/gen-repo)
            cust (-> (h/gen-cust)
                     (assoc :repos {(:id repo) repo}))
            now (jt/instant)
            old-build {:org-id (:id cust)
                       :repo-id (:id repo)
                       :build-id "build-1"
                       :idx 1
                       :start-time (-> now
                                       (jt/minus (jt/days 2))
                                       (jt/to-millis-from-epoch))
                       :script {:jobs nil}}
            new-build {:org-id (:id cust)
                       :repo-id (:id repo)
                       :build-id "build-2"
                       :idx 2
                       :start-time (-> now
                                       (jt/minus (jt/hours 2))
                                       (jt/to-millis-from-epoch))
                       :script {:jobs nil}}]
        (is (some? (st/save-customer st cust)))
        (is (some? (st/save-build st old-build)))
        (is (some? (st/save-build st new-build)))
        
        (testing "started in recent 24h"
          (is (= [new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id (:id cust)}})
                     (sut/recent-builds)
                     :body))))

        (testing "since specified timestamp"
          (is (= [old-build
                  new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id (:id cust)}
                             :query
                             {:since (-> now
                                         (jt/minus (jt/days 3))
                                         (jt/to-millis-from-epoch))}})
                     (sut/recent-builds)
                     :body))))

        (testing "latest `n` builds"
          (is (= [old-build
                  new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id (:id cust)}
                             :query
                             {:n 10}})
                     (sut/recent-builds)
                     :body
                     (as-> x (sort-by :start-time x))))))))))

(deftest latest-builds
  (testing "returns latest build for each customer repo"
    (h/with-memory-store st
      (let [repos (repeatedly 2 h/gen-repo)
            cust (-> (h/gen-cust)
                     (assoc :repos (->> repos
                                        (map (fn [r]
                                               [(:id r) r]))
                                        (into {}))))
            builds (->> repos
                        (map (fn [r]
                               (-> (h/gen-build)
                                   (assoc :org-id (:id cust)
                                          :repo-id (:id r))))))]
        (is (sid/sid? (st/save-customer st cust)))
        (doseq [b builds]
          (is (sid/sid? (st/save-build st b))))
        (let [res (-> {:storage st}
                      (h/->req)
                      (assoc-in [:parameters :path :org-id] (:id cust))
                      (sut/latest-builds))]
          (is (= 200 (:status res)))
          (is (= 2 (count (:body res))))
          (is (= (->> builds
                      (map :build-id)
                      (set))
                 (->> res
                      :body
                      (map :build-id)
                      (set)))))))))

(deftest stats
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc :repos {(:id repo) repo}))
          req  (-> {:storage st}
                   (h/->req)
                   (assoc :parameters
                          {:path
                           {:org-id (:id cust)}}))]
      
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

      (testing "with builds and credit consumptions"
        (letfn [(gen-build [start end creds]
                  (-> (h/gen-build)
                      (assoc :repo-id (:id repo)
                             :org-id (:id cust)
                             :start-time start
                             :end-time end
                             :credits creds)))
                (ts [& args]
                  (-> (apply jt/offset-date-time args)
                      (jt/with-offset (jt/zone-offset "Z"))
                      (jt/to-millis-from-epoch)))
                (insert-cc [cred build]
                  (st/save-credit-consumption st (-> (select-keys build [:build-id :repo-id :org-id])
                                                     (assoc :id (cuid/random-cuid)
                                                            :consumed-at (:end-time build)
                                                            :amount (:credits build)
                                                            :credit-id (:id cred)))))]
          (let [cred (-> (h/gen-cust-credit)
                         (assoc :org-id (:id cust)
                                :type :user
                                :amount 1000))
                builds [(gen-build (ts 2024 9 17 10) (ts 2024 9 17 10 5)  10)
                        (gen-build (ts 2024 9 17 11) (ts 2024 9 17 11 20) 20)
                        (gen-build (ts 2024 9 19 15) (ts 2024 9 19 15 30) 30)]]
            (is (some? (st/save-customer-credit st cred)))
            (is (->> builds
                     (map (partial st/save-build st))
                     (every? some?)))
            (is (->> builds
                     (map (partial insert-cc cred))
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
                            :consumed-credits)))))))))))

(deftest credits
  (h/with-memory-store s
    (let [cust (-> (h/gen-cust)
                   (dissoc :repos))
          user (h/gen-user)]
      (is (some? (st/save-customer s cust)))
      (is (some? (st/save-user s user)))
      (is (some? (st/save-customer-credit s {:org-id (:id cust)
                                             :amount 100M
                                             :type :user
                                             :user-id (:id user)
                                             :reason "Testing"})))
      
      (testing "provides available credits"
        (is (= 100M (-> {:storage s}
                        (h/->req)
                        (assoc-in [:parameters :path :org-id] (:id cust))
                        (sut/credits)
                        :body
                        :available))))

      (testing "contains last credit provision"))))
