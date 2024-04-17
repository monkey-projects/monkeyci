(ns monkey.ci.entities.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as sut]
             [helpers :as eh]]
            [next.jdbc :as jdbc]))

(deftest memory-db
  (testing "can connect"
    (eh/with-memory-db*
      (fn [conn]
        (is (some? conn))))))

(deftest prepared-db
  (testing "has customers table"
    (eh/with-prepared-db*
      (fn [conn]
        (is (number? (-> (jdbc/execute-one! (:ds conn) ["select count(*) as c from customers"])
                         :C)))))))

(deftest customer-entities
  (eh/with-prepared-db conn
    (let [cust {:name "test customer"}
          r (sut/insert-customer conn cust)]
      (testing "can insert"
        (is (some? (:uuid r)))
        (is (number? (:id r)))
        (is (= cust (select-keys r (keys cust)))))

      (testing "can select by id"
        (is (= r (sut/select-customer conn (sut/by-id (:id r))))))

      (testing "can select by uuid"
        (is (= r (sut/select-customer conn (sut/by-uuid (:uuid r))))))

      (testing "can update"
        (is (= 1 (sut/update-customer conn (assoc r :name "updated"))))
        (is (= "updated" (:name (sut/select-customer conn (sut/by-id (:id r)))))))

      (testing "cannot update nonexisting"
        (is (zero? (sut/update-customer conn {:id -1 :name "nonexisting customer"})))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-customer conn {}))))))

(deftest repo-entities
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer conn {:name "test customer"})
          r (sut/insert-repo conn {:name "test repo"
                                   :customer-id (:id cust)
                                   :url "http://test"})]
      (testing "can insert"
        (is (some? (:uuid r)))
        (is (number? (:id r)))
        (is (= "test repo" (:name r))))

      (testing "can select by id"
        (is (= r (sut/select-repo conn (sut/by-id (:id r))))))

      (testing "can select by uuid"
        (is (= r (sut/select-repo conn (sut/by-uuid (:uuid r))))))

      (testing "can update"
        (is (= 1 (sut/update-repo conn (assoc r :name "updated"))))
        (is (= "updated" (:name (sut/select-repo conn (sut/by-id (:id r))))))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-repo conn {:name "customerless repo"}))))))

(deftest repo-labels
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer
                conn
                {:name "test customer"})
          repo (sut/insert-repo
                conn
                {:name "test repo"
                 :customer-id (:id cust)
                 :url "http://test"})
          lbl  (sut/insert-repo-label
                conn
                {:name "test-label"
                 :value "test-value"
                 :repo-id (:id repo)})]
      (testing "can insert"
        (is (number? (:id lbl))))

      (testing "can select for repo"
        (is (= [lbl] (sut/select-repo-labels conn (sut/by-repo (:id repo))))))

      (testing "can delete"
        (is (= 1 (sut/delete-repo-labels conn (sut/by-id (:id lbl)))))
        (is (empty? (sut/select-repo-labels conn (sut/by-repo (:id repo)))))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-repo-label conn {:name "repoless label"}))))))

(deftest customer-params
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-customer
                 conn
                 {:name "test customer"})
          param (sut/insert-customer-param
                 conn
                 {:name "test-label"
                  :value "test-value"
                  :customer-id (:id cust)})]
      (testing "can insert"
        (is (number? (:id param))))

      (testing "can select for customer"
        (is (= [param] (sut/select-customer-params conn (sut/by-customer (:id cust))))))

      (testing "can delete"
        (is (= 1 (sut/delete-customer-params conn (sut/by-id (:id param)))))
        (is (empty? (sut/select-customer-params conn (sut/by-customer (:id cust)))))))))

(deftest param-labels
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-customer
                 conn
                 {:name "test customer"})
          param (sut/insert-customer-param
                conn
                {:name "test param"
                 :value "test param value"
                 :customer-id (:id cust)})
          lbl   (sut/insert-param-label
                 conn
                 {:name "test-label"
                  :value "test-value"
                  :param-id (:id param)})]
      (testing "can insert"
        (is (number? (:id lbl))))

      (testing "can select for param"
        (is (= [lbl] (sut/select-param-labels conn (sut/by-param (:id param))))))

      (testing "can delete"
        (is (= 1 (sut/delete-param-labels conn (sut/by-id (:id lbl)))))
        (is (empty? (sut/select-param-labels conn (sut/by-param (:id param)))))))))

(deftest webhooks
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer
                conn
                {:name "test customer"})
          repo (sut/insert-repo
                conn
                {:name "test repo"
                 :customer-id (:id cust)}) 
          wh   (sut/insert-webhook
                conn
                {:repo-id (:id repo)
                 :secret "very secret"})]
      (testing "can insert"
        (is (number? (:id wh))))

      (testing "can select for repo"
        (is (= [wh] (sut/select-webhooks conn (sut/by-repo (:id repo))))))

      (testing "can delete"
        (is (= 1 (sut/delete-webhooks conn (sut/by-id (:id wh)))))
        (is (empty? (sut/select-webhooks conn (sut/by-repo (:id repo)))))))))

(deftest ssh-keys
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer
                conn
                {:name "test customer"})
          key  (sut/insert-ssh-key
                conn
                {:description "test key"
                 :public-key "pubkey"
                 :private-key "privkey"
                 :customer-id (:id cust)})]
      (testing "can insert"
        (is (number? (:id key))))

      (testing "can select for customer"
        (is (= [key] (sut/select-ssh-keys conn (sut/by-customer (:id cust))))))

      (testing "can delete"
        (is (= 1 (sut/delete-ssh-keys conn (sut/by-id (:id key)))))
        (is (empty? (sut/select-ssh-keys conn (sut/by-customer (:id cust)))))))))

(deftest ssh-key-labels
  (eh/with-prepared-db conn
    (let [cust (sut/insert-customer
                conn
                {:name "test customer"})
          key  (sut/insert-ssh-key
                conn
                {:description "test ssh-key"
                 :public-key "pubkey"
                 :private-key "privkey"
                 :customer-id (:id cust)})
          lbl  (sut/insert-ssh-key-label
                conn
                {:name "test-label"
                 :value "test-value"
                 :ssh-key-id (:id key)})]
      (testing "can insert"
        (is (number? (:id lbl))))

      (testing "can select for ssh-key"
        (is (= [lbl] (sut/select-ssh-key-labels conn (sut/by-ssh-key (:id key))))))

      (testing "can delete"
        (is (= 1 (sut/delete-ssh-key-labels conn (sut/by-id (:id lbl)))))
        (is (empty? (sut/select-ssh-key-labels conn (sut/by-ssh-key (:id key)))))))))
