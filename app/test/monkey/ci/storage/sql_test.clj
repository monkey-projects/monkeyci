(ns monkey.ci.storage.sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.entities.helpers :as eh]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.entities.core :as ec]
            [monkey.ci.spec.entities :as se]
            [monkey.ci.storage.sql :as sut]))

(defn- gen-entity [t]
  (gen/generate (spec/gen t)))

(defn- gen-cust []
  (gen-entity :entity/customer))

(defn- gen-repo []
  (gen-entity :entity/repo))

(defn- gen-webhook []
  (gen-entity :entity/webhook))

(defn- gen-ssh-key []
  (gen-entity :entity/ssh-key))

(defmacro with-storage [conn s & body]
  `(eh/with-prepared-db ~conn
     (let [~s (sut/make-storage ~conn)]
       ~@body)))

(deftest ^:sql customers
  (with-storage conn s
    (testing "customers"
      (testing "can write and read"
        (let [cust (gen-cust)]
          (is (sid/sid? (st/save-customer s cust)))
          (is (= 1 (count (ec/select-customers conn [:is :id [:not nil]]))))
          (is (some? (ec/select-customer conn (ec/by-cuid (:id cust)))))
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
              "did not expect to find customer after deletion"))))))

(deftest ^:sql repos
  (with-storage conn s
    (testing "repos"
      (let [repo {:name "test repo"
                  :id "test-repo"}
            lbl (str "test-label-" (cuid/random-cuid))
            cust (-> (gen-cust)
                     (assoc-in [:repos (:id repo)] repo))
            sid [(:id cust) (:id repo)]]
        
        (testing "saved with customer"
          (is (sid/sid? (st/save-customer s cust)))
          (is (= (assoc repo :customer-id (:id cust))
                 (st/find-repo s sid))))

        (testing "saved with `save-repo`"
          (let [r (assoc repo :customer-id (:id cust))
                sid (vec (take-last 2 (st/save-repo s r)))]
            (is (sid/sid? sid))
            (is (= [(:id cust) (:id repo)] sid))
            (is (= r (st/find-repo s sid)))))

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
                saved-sid (st/save-repo s {:name "new repo"
                                           :id "new-repo"
                                           :customer-id (:id cust)
                                           :labels labels})
                sid [(:id cust) "new-repo"]]
            (is (= sid (take-last 2 saved-sid)))
            (is (= 1 (count (ec/select-repo-labels conn [:= :name "test-label"]))))
            (is (= "new-repo" (get-in (st/find-customer s (:id cust)) [:repos "new-repo" :id])))
            (let [repo (st/find-repo s sid)]
              (is (some? repo))
              (is (= labels (:labels repo))))))))))

(deftest ^:sql watched-github-repos
  (with-storage conn s
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
        (is (empty? (st/find-watched-github-repos s github-id)))))))

(deftest ^:sql ssh-keys
  (with-storage conn s
    (testing "ssh keys"
      (let [{cust-id :id :as cust} (gen-cust)
            k (assoc (gen-ssh-key) :customer-id cust-id)]
        (is (sid/sid? (st/save-customer s cust)))
        
        (testing "can create and retrieve"
          (let [ce (ec/select-customer conn (ec/by-cuid cust-id))]
            (is (sid/sid? (st/save-ssh-keys s cust-id [k])))
            (is (= 1 (count (ec/select-ssh-keys conn (ec/by-customer (:id ce))))))
            (is (= [k] (st/find-ssh-keys s cust-id)))))

        (testing "can update label filters")))))

(deftest ^:sql webhooks
  (with-storage conn s
    (testing "webhooks"
      (let [cust (gen-cust)
            repo (-> (gen-repo)
                     (assoc :customer-id (:id cust)))
            wh (-> (gen-webhook)
                   (assoc :customer-id (:id cust)
                          :repo-id (:id repo)))]
        (is (some? (st/save-customer s (assoc-in cust [:repos (:id repo)] repo))))
        
        (testing "can create and retrieve"
          (is (sid/sid? (st/save-webhook-details s wh)))
          (is (= wh (st/find-details-for-webhook s (:id wh)))))))))
