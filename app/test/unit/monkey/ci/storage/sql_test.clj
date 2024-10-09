(ns monkey.ci.storage.sql-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [medley.core :as mc]
            [monkey.ci.entities.helpers :as eh]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.entities.core :as ec]
            [monkey.ci.helpers :as h]
            [monkey.ci.spec.entities :as se]
            [monkey.ci.storage.sql :as sut]))

(defmacro with-storage [conn s & body]
  `(eh/with-prepared-db ~conn
     (let [~s (sut/make-storage ~conn)]
       ~@body)))

(deftest ^:sql customers
  (with-storage conn s
    (testing "can write and read"
      (let [cust (h/gen-cust)]
        (is (sid/sid? (st/save-customer s cust)))
        (is (= 1 (count (ec/select-customers conn [:is :id [:not nil]]))))
        (is (some? (ec/select-customer conn (ec/by-cuid (:id cust)))))
        (is (= (assoc cust :repos {})
               (st/find-customer s (:id cust))))))

    (testing "can write and read with repos"
      (let [cust (h/gen-cust)
            repo {:name "test repo"
                  :customer-id (:id cust)
                  :id "test-repo"
                  :url "http://test-repo"}]
        (is (sid/sid? (st/save-customer s cust)))
        (is (sid/sid? (st/save-repo s repo)))
        (is (= (assoc cust :repos {(:id repo) (dissoc repo :customer-id)})
               (st/find-customer s (:id cust))))))

    (testing "can delete with repos"
      (let [cust (h/gen-cust)
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
            "did not expect to find customer after deletion")))

    (testing "can search"
      (let [cust (-> (h/gen-cust)
                     (assoc :name "test customer"))]
        (is (sid/sid? (st/save-customer s cust)))
        
        (testing "by name"
          (is (= [cust] (st/search-customers s {:name "test"}))))

        (testing "by id"
          (is (= [cust] (st/search-customers s {:id (:id cust)}))))))))

(deftest ^:sql repos
  (with-storage conn s
    (let [repo {:name "test repo"
                :id "test-repo"}
          lbl (str "test-label-" (cuid/random-cuid))
          cust (-> (h/gen-cust)
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
            (is (= labels (:labels repo))))))

      (testing "lists display ids"
        (is (= ["test-repo" "new-repo"] (st/list-repo-display-ids s (:id cust))))))))

(deftest ^:sql watched-github-repos
  (with-storage conn s
    (let [cust (h/gen-cust)
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
        (is (empty? (st/find-watched-github-repos s github-id))))

      (testing "empty if no matches"
        (is (empty? (st/find-watched-github-repos s 12432)))))))

(deftest ^:sql ssh-keys
  (with-storage conn s
    (testing "ssh keys"
      (let [{cust-id :id :as cust} (h/gen-cust)
            k (assoc (h/gen-ssh-key) :customer-id cust-id)]
        (is (sid/sid? (st/save-customer s cust)))
        
        (testing "can create and retrieve"
          (let [ce (ec/select-customer conn (ec/by-cuid cust-id))]
            (is (sid/sid? (st/save-ssh-keys s cust-id [k])))
            (is (= 1 (count (ec/select-ssh-keys conn (ec/by-customer (:id ce))))))
            (is (= k (->> (st/find-ssh-keys s cust-id)
                          (first)
                          (mc/remove-vals nil?))))))

        (testing "can update label filters"
          (let [lf [[{:label "test-label"
                      :value "test-value"}]]]
            (is (sid/sid? (st/save-ssh-keys s cust-id [(assoc k :label-filters lf)])))
            (let [matches (st/find-ssh-keys s cust-id)]
              (is (= 1 (count matches)))
              (is (= lf (-> matches first :label-filters))))))))))

(deftest ^:sql webhooks
  (with-storage conn s
    (testing "webhooks"
      (let [cust (h/gen-cust)
            repo (-> (h/gen-repo)
                     (assoc :customer-id (:id cust)))
            wh (-> (h/gen-webhook)
                   (assoc :customer-id (:id cust)
                          :repo-id (:id repo)))]
        (is (some? (st/save-customer s (assoc-in cust [:repos (:id repo)] repo))))
        
        (testing "can create and retrieve"
          (is (sid/sid? (st/save-webhook s wh)))
          (is (= wh (st/find-webhook s (:id wh)))))))))

(deftest ^:sql customer-params
  (with-storage conn s
    (let [{cust-id :id :as cust} (h/gen-cust)
          params (assoc (h/gen-customer-params) :customer-id cust-id)
          sid (st/params-sid cust-id (:id params))]
      (is (sid/sid? (st/save-customer s cust)))
      
      (testing "can create and retrieve multiple"
        (let [ce (ec/select-customer conn (ec/by-cuid cust-id))]
          (is (sid/sid? (st/save-params s cust-id [params])))
          (is (= [params] (st/find-params s cust-id)))))

      (testing "can create and retrieve single"
        (let [ce (ec/select-customer conn (ec/by-cuid cust-id))]
          (is (sid/sid? (st/save-param s params)))
          (is (= params (st/find-param s sid)))))
      
      (testing "can update label filters"
        (let [lf [[{:label "test-label"
                    :value "test-value"}]]]
          (is (sid/sid? (st/save-params s cust-id [(assoc params :label-filters lf)])))
          (let [matches (st/find-params s cust-id)]
            (is (= 1 (count matches)))
            (is (= lf (-> matches first :label-filters))))))

      (testing "can update parameter values"
        (let [pv [{:name "new-param"
                   :value "new value"}]]
          (is (sid/sid? (st/save-params s cust-id [(assoc params :parameters pv)])))
          (let [matches (st/find-params s cust-id)]
            (is (= 1 (count matches)))
            (is (= pv (-> matches first :parameters))))))

      (testing "empty for nonexisting customer"
        (is (empty? (st/find-params s (cuid/random-cuid)))))

      (testing "can update single"
        (let [params (-> (st/find-param s sid)
                         (assoc :description "updated description"))]
          (is (sid/sid? (st/save-param s params)))
          (is (= params (st/find-param s sid)))))

      (testing "can delete single"
        (is (true? (st/delete-param s sid)))
        (is (nil? (st/find-param s sid)))))))

(deftest ^:sql users
  (with-storage conn s
    (let [user (-> (h/gen-user)
                   (dissoc :customers))
          user->id (juxt :type :type-id)]
      (testing "can save and find"
        (is (sid/sid? (st/save-user s user)))
        (is (= user (st/find-user-by-type s (user->id user)))))

      (testing "can find by cuid"
        (is (= user (st/find-user s (:id user)))))

      (testing "can link to customer"
        (let [cust (h/gen-cust)
              user (assoc user :customers [(:id cust)])]
          (is (sid/sid? (st/save-customer s cust)))
          (is (sid/sid? (st/save-user s user)))
          (is (= (:customers user)
                 (-> (st/find-user s (:id user)) :customers)))))

      (testing "can find customers"
        (is (not-empty (st/list-user-customers s (:id user)))))
      
      (testing "can unlink from customer"
        (is (sid/sid? (st/save-user s (dissoc user :customers))))
        (is (empty? (-> (st/find-user s (:id user)) :customers)))))))

(deftest ^:sql builds
  (with-storage conn s
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc-in [:repos (:id repo)] repo))
          build (-> (h/gen-build)
                    (assoc :customer-id (:id cust)
                           :repo-id (:id repo)
                           :script {:script-dir "test-dir"})
                    (mc/update-existing :git dissoc :ssh-keys-dir :ssh-keys))
          build-sid (st/ext-build-sid build)]
      (is (sid/sid? (st/save-customer s cust)))

      (testing "can save and retrieve"
        (is (sid/sid? (st/save-build s build)))
        (is (= 1 (count (ec/select conn {:select :*
                                         :from :builds}))))
        (is (= build (-> (st/find-build s build-sid)
                         (select-keys (keys build))))))

      (testing "removes ssh private keys"
        (let [ssh-key {:id (st/new-id)
                       :description "test key"
                       :private-key "secret private key"}
              build (assoc-in build [:git :ssh-keys] [ssh-key])
              _ (st/save-build s build)
              match (st/find-build s (st/ext-build-sid build))]
          (is (= (select-keys ssh-key [:id :description])
                 (-> match :git :ssh-keys first)))))

      (testing "can replace jobs"
        (let [job (h/gen-job)
              jobs {(:id job) job}]
          (is (sid/sid? (st/save-build s (assoc-in build [:script :jobs] jobs))))
          (is (= 1 (count (ec/select conn {:select :*
                                           :from :jobs}))))
          (is (= jobs (-> (st/find-build s build-sid) :script :jobs)))))

      (testing "can update jobs"
        (let [job (assoc (h/gen-job) :status :pending)
              jobs {(:id job) job}
              upd (assoc job :status :running)]
          (is (sid/sid? (st/save-build s (assoc-in build [:script :jobs] jobs))))
          (is (sid/sid? (st/save-build s (assoc-in build [:script :jobs (:id job)] upd))))
          (is (= upd (get-in (st/find-build s build-sid) [:script :jobs (:id job)])))))

      (testing "stores job message"
        (let [job (assoc (h/gen-job)
                         :status :failure
                         :message "Test error message")
              jobs {(:id job) job}]
          (is (sid/sid? (st/save-build s (assoc-in build [:script :jobs] jobs))))
          (is (= (:message job) (get-in (st/find-build s build-sid) [:script :jobs (:id job) :message])))))

      (testing "stores credit multiplier"
        (let [job (assoc (h/gen-job) :credit-multiplier 3)
              jobs {(:id job) job}]
          (is (sid/sid? (st/save-build s (assoc-in build [:script :jobs] jobs))))
          (is (= 3 (get-in (st/find-build s build-sid) [:script :jobs (:id job) :credit-multiplier])))))

      (testing "can list"
        (is (= [(:build-id build)]
               (st/list-build-ids s [(:id cust) (:id repo)]))))

      (testing "can check for existence"
        (is (true? (st/build-exists? s build-sid))))

      (testing "can list with details, excluding jobs"
        (let [d (st/list-builds s (take 2 build-sid))]
          (is (= 1 (count d)))
          (is (= (update build :script dissoc :jobs)
                 (select-keys (first d) (keys build))))))

      (testing "can get next idx"
        (let [repo (-> (h/gen-repo)
                       (assoc :customer-id (:id cust)))
              repo-sid [(:id cust) (:id repo)]]
          (is (some? (st/save-repo s repo)))
          (is (= 1 (st/find-next-build-idx s repo-sid))
              "initial index is one")
          (is (some? (st/save-build s (-> (h/gen-build)
                                          (assoc :customer-id (:id cust)
                                                 :repo-id (:id repo)
                                                 :idx 100)))))
          (is (= 101 (st/find-next-build-idx s repo-sid)))))

      (testing "can list builds since timestamp"
        (let [repo (h/gen-repo)
              cust (-> (h/gen-cust)
                       (assoc :repos {(:id repo) repo}))
              old-build (-> (h/gen-build)
                            (assoc :customer-id (:id cust)
                                   :repo-id (:id repo)
                                   :start-time 100)
                            (dissoc :script))
              new-build (-> (h/gen-build)
                            (assoc :customer-id (:id cust)
                                   :repo-id (:id repo)
                                   :start-time 200)
                            (dissoc :script))]
          (is (sid/sid? (st/save-customer s cust)))
          (is (sid/sid? (st/save-build s old-build)))
          (is (sid/sid? (st/save-build s new-build)))
          (let [r (st/list-builds-since s (:id cust) 150)]
            (is (= [(:build-id new-build)] (map :build-id r)))
            (is (= (:id cust) (:customer-id (first r))))
            (is (= (:id repo) (:repo-id (first r)))))))

      (testing "can find latest by build index"
        (let [repo (h/gen-repo)
              cust (-> (h/gen-cust)
                       (assoc :repos {(:id repo) repo}))
              old-build (-> (h/gen-build)
                            (assoc :customer-id (:id cust)
                                   :repo-id (:id repo)
                                   :start-time 100
                                   :idx 9
                                   :build-id "build-9")
                            (dissoc :script))
              new-build (-> (h/gen-build)
                            (assoc :customer-id (:id cust)
                                   :repo-id (:id repo)
                                   :start-time 200
                                   :idx 10
                                   :build-id "build-10")
                            (dissoc :script))]
          (is (sid/sid? (st/save-customer s cust)))
          (is (sid/sid? (st/save-build s old-build)))
          (is (sid/sid? (st/save-build s new-build)))
          (let [r (st/find-latest-build s [(:id cust) (:id repo)])]
            (is (= (:build-id new-build) (:build-id r)))))))))

(deftest ^:sql join-requests
  (with-storage conn s
    (let [cust (h/gen-cust)
          user (h/gen-user)
          _ (st/save-customer s cust)
          _ (st/save-user s user)
          jr (-> (h/gen-join-request)
                 (assoc :user-id (:id user)
                        :customer-id (:id cust)
                        :status :pending
                        :request-msg "test request")
                 (dissoc :response-msg))]
      (testing "can create and retrieve"
        (is (sid/sid? (st/save-join-request s jr)))
        (is (= jr (st/find-join-request s (:id jr)))))

      (testing "can update"
        (is (sid/sid? (st/save-join-request s (assoc jr :status :approved))))
        (is (= :approved (-> (st/find-join-request s (:id jr))
                             :status))))

      (testing "can list for user"
        (is (= [(:id jr)] (->> (st/list-user-join-requests s (:id user))
                               (map :id))))))))

(deftest ^:sql email-registrations
  (with-storage conn s
    (let [er (h/gen-email-registration)]
      (testing "can create and retrieve"
        (is (sid/sid? (st/save-email-registration s er)))
        (is (= er (st/find-email-registration s (:id er)))))

      (testing "can retrieve by email"
        (is (= er (st/find-email-registration-by-email s (:email er)))))

      (testing "`nil` if not found by email"
        (is (nil? (st/find-email-registration-by-email s "nonexisting"))))

      (testing "can list"
        (is (= [(:id er)] (->> (st/list-email-registrations s)
                               (map :id)))))

      (testing "can delete"
        (is (true? (st/delete-email-registration s (:id er))))
        (is (empty? (st/list-email-registrations s)))))))

(deftest ^:sql customer-credits
  (with-storage conn s
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc :repos {(:id repo) repo}))
          cred (-> (h/gen-cust-credit)
                   (assoc :customer-id (:id cust)
                          :amount 100M)
                   (dissoc :user-id :subscription-id))]
      (is (sid/sid? (st/save-customer s cust)))
        
      (testing "can create and retrieve"
        (is (sid/sid? (st/save-customer-credit s cred)))
        (is (= cred (st/find-customer-credit s (:id cred)))))
        
      (testing "can list for customer"
        (let [other-cust (h/gen-cust)
              _ (st/save-customer s other-cust)
              sids (->> [(assoc cred :from-time 1000)
                         (-> (h/gen-cust-credit)
                             (assoc :customer-id (:id cust)
                                    :from-time 2000
                                    :amount 200M)
                             (dissoc :user-id :subscription-id))
                         (-> (h/gen-cust-credit)
                             (assoc :customer-id (:id other-cust)
                                    :from-time 1000)
                             (dissoc :user-id :subscription-id))]
                        (mapv (partial st/save-customer-credit s)))]
          (is (some? sids))
          (is (= [(-> sids first last)]
                 (->> (st/list-customer-credits-since s (:id cust) 1100)
                      (map :id))))))

      (testing "calculates available credits"
        (let [build (-> (h/gen-build)
                        (assoc :customer-id (:id cust)
                               :repo-id (:id repo)
                               :credits 25M))]
          (is (sid/sid? (st/save-build s build)))
          (is (= 275M (st/calc-available-credits s (:id cust)))))))))

(deftest make-storage
  (testing "creates sql storage object using connection settings"
    (let [s (st/make-storage {:storage {:type :sql
                                        :url (:jdbcUrl eh/h2-config)}})]
      (is (some? s))
      (is (some? (-> s :conn :ds))))))
