(ns monkey.ci.web.api.org-test
  (:require [clojure.test :refer [deftest is testing]]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.crypto :as wc]
            [monkey.ci.web.api.org :as sut]))

(deftest get-org
  (testing "returns org in body"
    (let [org {:id "test-org"
                :name "Test org"}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id org)))]
      (is (sid/sid? (st/save-org st org)))
      (is (= org (:body (sut/get-org req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (h/with-path-param :org-id "nonexisting")
                   (sut/get-org)
                   :status))))

  (testing "converts repo map into list"
    (let [org {:id (st/new-id)
                :name "Org with projects"}
          repo {:id "test-repo"
                :name "Test repository"
                :org-id (:id org)}
          {st :storage :as rt} (trt/test-runtime)]
      (is (sid/sid? (st/save-org st org)))
      (is (sid/sid? (st/save-repo st repo)))
      (let [r (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id org))
                  (sut/get-org)
                  :body)
            repos (-> r :repos)]
        (is (some? repos))
        (is (not (map? repos)))
        (is (= (select-keys repo [:id :name])
               (first repos)))))))

(deftest create-org
  (testing "returns created org with id"
    (let [r (-> (trt/test-runtime)
                (h/->req)
                (h/with-body {:name "new org"})
                (sut/create-org)
                :body)]
      (is (= "new org" (:name r)))
      (is (string? (:id r)))))

  (let [user (-> (h/gen-user)
                 (dissoc :orgs))
        {st :storage :as rt} (trt/test-runtime)
        r (-> rt
              (trt/set-dek-generator (constantly {:enc "encrypted-key"
                                                  :key "plain-key"}))
              (h/->req)
              (h/with-body {:name "another org"})
              (h/with-identity user)
              (sut/create-org)
              :body)]
    (is (some? r))

    (testing "links current user to org"
      (is (= [(:id r)] (-> (st/find-user st (:id user))
                           :orgs)))
      (is (= [r] (st/list-user-orgs st (:id user)))))

    (let [org-id (:id r)]
      (testing "creates subscription"
        (is (= 1 (-> (st/list-org-credit-subscriptions st org-id)
                     (count)))))

      (testing "issues credits"
        (let [cc (st/list-org-credits st org-id)]
          (is (= 1 (count cc)))
          (is (some? (->> cc
                          first
                          :subscription-id
                          (vector org-id)
                          (st/find-credit-subscription st))))))

      (testing "generates data encryption key"
        (let [c (st/find-crypto st org-id)]
          (is (some? c))
          (is (= "encrypted-key" (:dek c))
              "stores encrypted key"))))))

(deftest update-org
  (testing "returns org in body"
    (let [org {:id "test-org"
                :name "Test org"}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id org))
                  (h/with-body {:name "updated"}))]
      (is (sid/sid? (st/save-org st org)))
      (is (= {:id (:id org)
              :name "updated"}
             (:body (sut/update-org req))))))

  (testing "can update with repos"
    (let [org {:id "other-org"
                :name "Other org"
                :repos {"test-repo" {:id "test-repo"}}}
          {st :storage :as rt} (trt/test-runtime)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :org-id (:id org))
                  (h/with-body {:name "updated"}))]
      (is (sid/sid? (st/save-org st org)))
      (is (= "updated" (-> req
                           (sut/update-org)
                           :body
                           :name)))
      (is (= ["test-repo"] (-> (st/find-org st (:id org))
                               :repos
                               (keys))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (h/with-path-param :org-id "nonexisting")
                   (sut/update-org)
                   :status)))))

(deftest search-orgs
  (let [{st :storage :as rt} (trt/test-runtime)
        org {:id (st/new-id)
              :name "Test org"}
        sid (st/save-org st org)]
    (testing "retrieves org by id"
      (is (= [org]
             (-> rt
                 (h/->req)
                 (h/with-query-param :id (:id org))
                 (sut/search-orgs)
                 :body))))
    
    (testing "searches orgs by name"
      (is (= [org]
             (-> rt
                 (h/->req)
                 (h/with-query-param :name "Test")
                 (sut/search-orgs)
                 :body))))
    
    (testing "fails if no query params given"
      (is (= 400
             (-> rt
                 (h/->req)
                 (sut/search-orgs)
                 :status))))))

(deftest delete-org
  (h/with-memory-store st
    (testing "deletes org with id from storage"
      (let [org (h/gen-org)]
        (is (sid/sid? (st/save-org st org)))
        (is (= 204 (-> {:storage st}
                       (h/->req)
                       (assoc-in [:parameters :path :org-id] (:id org))
                       (sut/delete-org)
                       :status)))
        (is (nil? (st/find-org st (:id org))))))))

(deftest recent-builds
  (h/with-memory-store st
    (testing "status `404` if org does not exist"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id "non-existing"}})
                     (sut/recent-builds)
                     :status))))

    (testing "retrieves builds"
      (let [repo (h/gen-repo)
            org (-> (h/gen-org)
                    (assoc :repos {(:id repo) repo}))
            now (jt/instant)
            old-build {:org-id (:id org)
                       :repo-id (:id repo)
                       :build-id "build-1"
                       :idx 1
                       :start-time (-> now
                                       (jt/minus (jt/days 2))
                                       (jt/to-millis-from-epoch))
                       :script {:jobs nil}}
            new-build {:org-id (:id org)
                       :repo-id (:id repo)
                       :build-id "build-2"
                       :idx 2
                       :start-time (-> now
                                       (jt/minus (jt/hours 2))
                                       (jt/to-millis-from-epoch))
                       :script {:jobs nil}}]
        (is (some? (st/save-org st org)))
        (is (some? (st/save-build st old-build)))
        (is (some? (st/save-build st new-build)))
        
        (testing "started in recent 24h"
          (is (= [new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id (:id org)}})
                     (sut/recent-builds)
                     :body))))

        (testing "since specified timestamp"
          (is (= [old-build
                  new-build]
                 (-> {:storage st}
                     (h/->req)
                     (assoc :parameters
                            {:path
                             {:org-id (:id org)}
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
                             {:org-id (:id org)}
                             :query
                             {:n 10}})
                     (sut/recent-builds)
                     :body
                     (as-> x (sort-by :start-time x))))))))))

(deftest latest-builds
  (testing "returns latest build for each org repo"
    (h/with-memory-store st
      (let [repos (repeatedly 2 h/gen-repo)
            org (-> (h/gen-org)
                     (assoc :repos (->> repos
                                        (map (fn [r]
                                               [(:id r) r]))
                                        (into {}))))
            builds (->> repos
                        (map (fn [r]
                               (-> (h/gen-build)
                                   (assoc :org-id (:id org)
                                          :repo-id (:id r))))))]
        (is (sid/sid? (st/save-org st org)))
        (doseq [b builds]
          (is (sid/sid? (st/save-build st b))))
        (let [res (-> {:storage st}
                      (h/->req)
                      (assoc-in [:parameters :path :org-id] (:id org))
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
          org (-> (h/gen-org)
                   (assoc :repos {(:id repo) repo}))
          req  (-> {:storage st}
                   (h/->req)
                   (assoc :parameters
                          {:path
                           {:org-id (:id org)}}))]
      
      (is (some? (st/save-org st org)))
      
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
                             :org-id (:id org)
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
          (let [cred (-> (h/gen-org-credit)
                         (assoc :org-id (:id org)
                                :type :user
                                :amount 1000))
                builds [(gen-build (ts 2024 9 17 10) (ts 2024 9 17 10 5)  10)
                        (gen-build (ts 2024 9 17 11) (ts 2024 9 17 11 20) 20)
                        (gen-build (ts 2024 9 19 15) (ts 2024 9 19 15 30) 30)]]
            (is (some? (st/save-org-credit st cred)))
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
    (let [org (-> (h/gen-org)
                   (dissoc :repos))
          user (h/gen-user)]
      (is (some? (st/save-org s org)))
      (is (some? (st/save-user s user)))
      (is (some? (st/save-org-credit s {:org-id (:id org)
                                        :amount 100M
                                        :type :user
                                        :user-id (:id user)
                                        :reason "Testing"})))
      
      (testing "provides available credits"
        (is (= 100M (-> {:storage s}
                        (h/->req)
                        (assoc-in [:parameters :path :org-id] (:id org))
                        (sut/credits)
                        :body
                        :available))))

      (testing "contains last credit provision"))))
