(ns monkey.ci.storage.sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities.helpers :as eh]
            [monkey.ci
             [protocols :as p]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.entities.core :as ec]
            [monkey.ci.storage.sql :as sut]))

(defn- gen-cust []
  (let [id (st/new-id)]
    {:id id
     :name (str "Test customer " id)}))

(deftest ^:sql sql-storage
  (eh/with-prepared-db conn
    (let [s (sut/make-storage conn)]
      (testing "customers"
        (testing "can write and read"
          (let [cust (gen-cust)]
            (is (sid/sid? (st/save-customer s cust)))
            (is (= 1 (count (ec/select-customers conn [:is :id [:not nil]]))))
            (is (some? (ec/select-customer conn (ec/by-uuid (parse-uuid (:id cust))))))
            (is (= (assoc cust :repos {})
                   (st/find-customer s (:id cust))))))

        (testing "can write and read with repos"
          (let [cust (gen-cust)
                repo {:name "test repo"
                      :customer-id (:id cust)
                      :id "test-repo"
                      :url "http://test-repo"}]
            (is (sid/sid? (st/save-customer s cust)))
            (is (sid/sid? (st/save-repo s repo)))
            (is (= (assoc cust :repos {(:id repo) (dissoc repo :customer-id)})
                   (st/find-customer s (:id cust))))))

        (testing "can delete with repos"
          (let [cust (gen-cust)
                repo {:name "test repo"
                      :customer-id (:id cust)
                      :id "test-repo"
                      :url "http://test-repo"}]
            (is (sid/sid? (st/save-customer s cust)))
            (is (sid/sid? (st/save-repo s repo)))
            (is (some? (st/find-customer s (:id cust))))
            (is (true? (p/delete-obj s (st/customer-sid (:id cust))))
                "expected to delete customer record")
            (is (nil? (st/find-customer s (:id cust)))
                "did not expect to find customer after deletion"))))

      (testing "repos"
        (let [repo {:name "test repo"
                    :id "test-repo"}
              lbl (str "test-label-" (random-uuid))
              cust (-> (gen-cust)
                       (assoc-in [:repos (:id repo)] repo))
              sid [(:id cust) (:id repo)]]
          (testing "can find by sid"
            (is (sid/sid? (st/save-customer s cust)))
            (is (= (assoc repo :customer-id (:id cust))
                   (st/find-repo s sid))))

          (testing "can add labels"
            (let [labels [{:name lbl
                           :value "test value"}]]
              (is (sid/sid? (st/update-repo s sid assoc :labels labels)))
              (is (= labels (-> (st/find-repo s sid)
                                :labels)))
              (is (= 1 (count (ec/select-repo-labels conn [:= :name lbl]))))))

          (testing "can update labels"
            (let [labels [{:name lbl
                           :value "updated value"}]]
              (is (sid/sid? (st/update-repo s sid assoc :labels labels)))
              (is (= labels (-> (st/find-repo s sid)
                                :labels)))
              (is (= 1 (count (ec/select-repo-labels conn [:= :name lbl]))))))

          (testing "can remove labels"
            (is (sid/sid? (st/update-repo s sid dissoc :labels)))
            (is (empty? (ec/select-repo-labels conn [:= :name lbl]))))

          (testing "creates labels on new repo"
            (let [labels [{:name "test-label"
                           :value "test value"}]
                  _ (st/save-repo s {:name "new repo"
                                     :id "new-repo"
                                     :customer-id (:id cust)
                                     :labels labels})
                  sid [(:id cust) "new-repo"]]
              (is (sid/sid? sid))
              (is (= 1 (count (ec/select-repo-labels conn [:= :name "test-label"]))))
              (let [repo (st/find-repo s sid)]
                (is (some? repo))
                (is (= labels (:labels repo))))))))

      (testing "watched github repos"
        (let [cust (gen-cust)
              github-id 64253
              repo {:name "github test"
                    :id "github-test"
                    :url "http://github.com/test"
                    :customer-id (:id cust)
                    :github-id github-id}]
          (is (sid/sid? (st/save-customer s cust)))
          
          (testing "can find watched repos"
            (let [repo-sid (st/watch-github-repo s repo)]
              (is (sid/sid? repo-sid))
              (is (= repo (st/find-repo s repo-sid)))
              (is (= [repo] (st/find-watched-github-repos s github-id)))))

          (testing "can unwatch"
            (is (true? (st/unwatch-github-repo s [(:id cust) (:id repo)])))
            (is (empty? (st/find-watched-github-repos s github-id)))))))))

