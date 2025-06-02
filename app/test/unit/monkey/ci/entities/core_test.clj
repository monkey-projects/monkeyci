(ns monkey.ci.entities.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as sut]
             [helpers :as eh]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(deftest ^:sql memory-db
  (testing "can connect"
    (eh/with-test-db*
      (fn [conn]
        (is (some? conn))))))

(deftest ^:sql prepared-db
  (testing "has orgs table"
    (eh/with-prepared-db*
      (fn [conn]
        (is (number? (-> (jdbc/execute-one! (:ds conn) ["select count(*) as c from orgs"]
                                            {:builder-fn rs/as-unqualified-lower-maps})
                         :c)))))))

(deftest ^:sql org-entities
  (eh/with-prepared-db conn
    (let [cust (eh/gen-org)
          r (sut/insert-org conn cust)]
      (testing "can insert"
        (is (some? (:cuid r)))
        (is (number? (:id r)))
        (is (= cust (select-keys r (keys cust)))))

      (testing "can select by id"
        (is (= r (sut/select-org conn (sut/by-id (:id r))))))

      (testing "can select by cuid"
        (is (= r (sut/select-org conn (sut/by-cuid (:cuid r))))))

      (testing "can update"
        (is (some? (sut/update-org conn (assoc r :name "updated"))))
        (is (= "updated" (:name (sut/select-org conn (sut/by-id (:id r)))))))

      (testing "cannot update nonexisting"
        (is (nil? (sut/update-org conn {:id -1 :name "nonexisting org"})))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-org conn {}))))))

(deftest ^:sql repo-entities
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          r (sut/insert-repo conn (-> (eh/gen-repo)
                                      (assoc :org-id (:id cust))))]
      (testing "can insert"
        (is (some? (:cuid r)))
        (is (number? (:id r)))
        (is (some? (:name r))))

      (testing "can select by id"
        (is (= r (-> (sut/select-repo conn (sut/by-id (:id r)))
                     (select-keys (keys r))))))

      (testing "can select by cuid"
        (is (= r (-> (sut/select-repo conn (sut/by-cuid (:cuid r)))
                     (select-keys (keys r))))))

      (testing "can update"
        (is (some? (sut/update-repo conn (assoc r :name "updated"))))
        (is (= "updated" (:name (sut/select-repo conn (sut/by-id (:id r))))))))

    (testing "throws on invalid record"
      (is (thrown? Exception (sut/insert-repo conn {:name "orgless repo"}))))))

(deftest ^:sql repo-labels
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org
                conn
                {:name "test org"})
          repo (sut/insert-repo
                conn
                {:name "test repo"
                 :display-id "test-repo"
                 :org-id (:id cust)
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

(deftest ^:sql org-params
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-org
                 conn
                 (eh/gen-org))
          param (sut/insert-org-param
                 conn
                 (assoc (eh/gen-org-param) :org-id (:id cust)))]
      (testing "can insert"
        (is (number? (:id param))))

      (testing "can select for org"
        (is (= [param] (->> (sut/select-org-params conn (sut/by-org (:id cust)))
                            (map #(select-keys % (keys param)))))))

      (testing "can delete"
        (is (= 1 (sut/delete-org-params conn (sut/by-id (:id param)))))
        (is (empty? (sut/select-org-params conn (sut/by-org (:id cust)))))))))

(deftest ^:sql org-param-values
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-org
                 conn
                 (eh/gen-org))
          param (sut/insert-org-param
                 conn
                 (assoc (eh/gen-org-param) :org-id (:id cust)))
          value (sut/insert-org-param-value
                 conn
                 (assoc (eh/gen-param-value) :params-id (:id param)))]
      (testing "can insert"
        (is (number? (:id value))))

      (testing "can select for param"
        (is (= [value] (sut/select-org-param-values conn [:= :params-id (:id param)]))))

      (testing "can delete"
        (is (= 1 (sut/delete-org-param-values conn (sut/by-id (:id value)))))
        (is (nil? (sut/select-org-param-value conn (sut/by-id (:id value)))))))))

(deftest ^:sql webhooks
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org
                conn
                (eh/gen-org))
          repo (sut/insert-repo
                conn
                {:name "test repo"
                 :display-id "test-repo"
                 :org-id (:id cust)}) 
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

(deftest ^:sql ssh-keys
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org
                conn
                (eh/gen-org))
          key  (sut/insert-ssh-key
                conn
                (assoc (eh/gen-ssh-key) :org-id (:id cust)))]
      (testing "can insert"
        (is (number? (:id key))))

      (testing "can select for org"
        (is (= [key] (sut/select-ssh-keys conn (sut/by-org (:id cust))))))

      (testing "can delete"
        (is (= 1 (sut/delete-ssh-keys conn (sut/by-id (:id key)))))
        (is (empty? (sut/select-ssh-keys conn (sut/by-org (:id cust)))))))))

(deftest ^:sql builds
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-org
                 conn
                 (eh/gen-org))
          repo  (sut/insert-repo
                 conn
                 (-> (eh/gen-repo)
                     (assoc :org-id (:id cust))))
          build (sut/insert-build
                 conn
                 (-> (eh/gen-build)
                     (assoc :repo-id (:id repo)
                            :status :pending)))]
      (testing "can insert"
        (is (number? (:id build))))

      (testing "can select for repo"
        (is (not-empty (sut/select-builds conn (sut/by-repo (:id repo))))))

      (testing "can delete"
        (is (= 1 (sut/delete-builds conn (sut/by-id (:id build)))))
        (is (empty? (sut/select-builds conn (sut/by-repo (:id repo))))))

      (testing "can save message"
        (let [build (-> (eh/gen-build)
                        (assoc :repo-id (:id repo)
                               :message "test message")
                        (as-> x (sut/insert-build conn x)))]
          (is (= "test message" (-> (sut/select-build conn (:id build))
                                    :message))))))))

(deftest ^:sql jobs
  (eh/with-prepared-db conn
    (let [cust  (sut/insert-org
                 conn
                 (eh/gen-org))
          repo  (sut/insert-repo
                 conn
                 (-> (eh/gen-repo)
                     (assoc :org-id (:id cust))))
          build (sut/insert-build
                conn
                (-> (eh/gen-build)
                    (assoc :repo-id (:id repo))))
          job   (sut/insert-job
                 conn
                 (-> (eh/gen-job)
                     (assoc :build-id (:id build)
                            :details {:image "test-image"})))]
      (testing "can insert"
        (is (number? (:id job))))

      (testing "can select for build"
        (is (not-empty (sut/select-jobs conn (sut/by-build (:id build))))))

      (testing "parses details from edn"
        (is (= (:details job)
               (-> (sut/select-job conn (sut/by-id (:id job)))
                   :details))))

      (testing "can delete"
        (is (= 1 (sut/delete-jobs conn (sut/by-id (:id job)))))
        (is (empty? (sut/select-jobs conn (sut/by-build (:id build)))))))))

(deftest ^:sql users
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org
                conn
                {:name "test org"})
          user (sut/insert-user
                conn
                {:type "github"
                 :type-id "1234"
                 :email "test@monkeyci.com"})]
      (testing "can insert"
        (is (number? (:id user))))

      (testing "can select by cuid"
        (is (= user (sut/select-user conn (sut/by-cuid (:cuid user))))))

      (testing "can link to org"
        (is (some? (sut/insert-user-org conn {:user-id (:id user)
                                                   :org-id (:id cust)}))))

      (testing "can delete"
        (is (= 1 (sut/delete-user-orgs conn [:and
                                                  [:= :user-id (:id user)]
                                                  [:= :org-id (:id cust)]])))
        (is (= 1 (sut/delete-users conn (sut/by-id (:id user)))))
        (is (empty? (sut/select-users conn (sut/by-id (:id user)))))))))

(deftest ^:sql join-requests
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          user (sut/insert-user conn (eh/gen-user))]
      (testing "can insert"
        (is (number? (:id (sut/insert-join-request
                           conn
                           (-> (eh/gen-join-request)
                               (assoc :user-id (:id user)
                                      :org-id (:id cust))))))))

      (testing "can select by user id"
        (is (some? (sut/select-join-request conn (sut/by-user (:id user))))))

      (testing "can delete"
        (is (= 1 (sut/delete-join-requests conn (sut/by-user (:id user)))))
        (is (empty? (sut/select-join-requests conn (sut/by-org (:id cust)))))))))

(deftest ^:sql email-registration
  (eh/with-prepared-db conn
    (let [reg (eh/gen-email-registration)]
      (testing "can insert"
        (is (number? (:id (sut/insert-email-registration conn reg)))))

      (testing "can select by cuid"
        (is (= reg (-> (sut/select-email-registration conn (sut/by-cuid (:cuid reg)))
                       (select-keys (keys reg))))))

      (testing "can delete"
        (is (= 1 (sut/delete-email-registrations conn (sut/by-cuid (:cuid reg)))))
        (is (nil? (sut/select-email-registration conn (sut/by-cuid (:cuid reg)))))))))

(deftest ^:sql org-credits
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          cred (-> (eh/gen-org-credit)
                   (assoc :org-id (:id cust))
                   (dissoc :subscription-id :user-id))]
      (testing "can insert"
        (is (number? (:id (sut/insert-org-credit conn cred)))))

      (testing "can select by org"
        (is (= [(:cuid cred)]
               (->> (sut/select-org-credits conn (sut/by-org (:id cust)))
                    (map :cuid))))))))

(deftest ^:sql credit-subscriptions
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          cred (-> (eh/gen-credit-subscription)
                   (assoc :org-id (:id cust)))]
      (testing "can insert"
        (is (number? (:id (sut/insert-credit-subscription conn cred)))))

      (testing "can select by org"
        (is (= [(:cuid cred)]
               (->> (sut/select-credit-subscriptions conn (sut/by-org (:id cust)))
                    (map :cuid))))))))

(deftest ^:sql credit-consumptions
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          repo (sut/insert-repo conn (assoc (eh/gen-repo)
                                            :org-id (:id cust)))
          build (sut/insert-build conn (assoc (eh/gen-build)
                                              :repo-id (:id repo)))
          cred (sut/insert-org-credit
                conn
                (-> (eh/gen-cust-credit)
                    (assoc :org-id (:id cust))
                    (dissoc :user-id :subscription-id)))
          ccons (-> (eh/gen-credit-consumption)
                    (assoc :build-id (:id build)
                           :credit-id (:id cred)))]
      (testing "can insert"
        (is (number? (:id (sut/insert-credit-consumption conn ccons)))))

      (testing "can select by build"
        (is (= [(:cuid ccons)]
               (->> (sut/select-credit-consumptions conn (sut/by-build (:id build)))
                    (map :cuid))))))))

(deftest ^:sql bb-webhook
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          repo (sut/insert-repo conn (assoc (eh/gen-repo)
                                            :org-id (:id cust)))
          wh (sut/insert-webhook conn (-> (eh/gen-webhook)
                                          (assoc :repo-id (:id repo))))
          bb-wh (-> (eh/gen-bb-webhook)
                    (assoc :webhook-id (:id wh)))]
      
      (testing "can insert"
        (is (some? (sut/insert-bb-webhook conn bb-wh))))
      
      (testing "can select by cuid"
        (is (= bb-wh (-> (sut/select-bb-webhook conn (sut/by-cuid (:cuid bb-wh)))
                         (dissoc :id)))))
      
      (testing "can select by webhook id"
        (is (= bb-wh (-> (sut/select-bb-webhook conn [:= :webhook-id (:id wh)])
                         (dissoc :id))))))))

(deftest ^:sql invoice
  (eh/with-prepared-db conn
    (let [cust (sut/insert-org conn (eh/gen-org))
          inv (-> (eh/gen-invoice)
                  (assoc :org-id (:id cust)
                         :net-amount 100M
                         :vat-perc 21M
                         :details [{:net-amount 100M
                                    :vat-perc 21M
                                    :description "Test invoice detail"}]))]
      
      (testing "can insert"
        (is (number? (:id (sut/insert-invoice conn inv)))))

      (testing "can select by cuid"
        (is (= inv (-> (sut/select-invoice conn (sut/by-cuid (:cuid inv)))
                       (dissoc :id)))))

      (testing "can select by org id"
        (is (= inv (-> (sut/select-invoice conn (sut/by-org (:id cust)))
                       (dissoc :id))))))))

(deftest ^:sql queued-task
  (eh/with-prepared-db conn
    (let [task (-> (eh/gen-queued-task)
                   (assoc :task ::test-task))]
      (testing "can insert"
        (is (number? (:id (sut/insert-queued-task conn task)))))

      (testing "can list"
        (let [m (sut/select-queued-tasks conn nil)]
          (is (= 1 (count m)))
          (is (= task (select-keys (first m) (keys task))))
          (is (number? (:id (first m))))))

      (testing "can delete"
        (is (= 1 (sut/delete-queued-tasks conn (sut/by-cuid (:cuid task)))))
        (is (empty? (sut/select-queued-tasks conn nil)))))))
