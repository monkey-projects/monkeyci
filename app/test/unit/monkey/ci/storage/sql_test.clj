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

(defn- gen-customer-params []
  (gen-entity :entity/customer-params))

(defn- gen-user []
  (gen-entity :entity/user))

(defn- gen-build []
  (-> (gen-entity :entity/build)
      ;; TODO Put this in the spec itself
      (update :jobs (fn [jobs]
                      (->> jobs
                           (mc/map-kv-vals #(assoc %2 :id %1))
                           (into {}))))))

(defn- gen-job []
  (gen-entity :entity/job))

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

(deftest ^:sql customer-params
  (with-storage conn s
    (testing "customer params"
      (let [{cust-id :id :as cust} (gen-cust)
            params (assoc (gen-customer-params) :customer-id cust-id)]
        (is (sid/sid? (st/save-customer s cust)))
        
        (testing "can create and retrieve"
          (let [ce (ec/select-customer conn (ec/by-cuid cust-id))]
            (is (sid/sid? (st/save-params s cust-id [params])))
            (is (= [params] (st/find-params s cust-id)))))

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
            (is (= pv (-> (st/find-params s cust-id) first :parameters)))))))))

(deftest ^:sql users
  (with-storage conn s
    (testing "users"
      (let [user (-> (gen-user)
                     (dissoc :customers))
            user->id (juxt :type :type-id)]
        (testing "can save and find"
          (is (sid/sid? (st/save-user s user)))
          (is (= user (st/find-user s (user->id user)))))

        (testing "can link to customer"
          (let [cust (gen-cust)
                user (assoc user :customers [(:id cust)])]
            (is (sid/sid? (st/save-customer s cust)))
            (is (sid/sid? (st/save-user s user)))
            (is (= (:customers user)
                   (-> (st/find-user s (user->id user)) :customers)))))

        (testing "can unlink from customer"
          (is (sid/sid? (st/save-user s (dissoc user :customers))))
          (is (empty? (-> (st/find-user s (user->id user)) :customers))))))))

(deftest ^:sql builds
  (with-storage conn s
    (testing "builds"
      (let [repo (gen-repo)
            cust (-> (gen-cust)
                     (assoc-in [:repos (:id repo)] repo))
            build (-> (gen-build)
                      (assoc :customer-id (:id cust)
                             :repo-id (:id repo)))
            build-sid (st/ext-build-sid build)]
        (is (sid/sid? (st/save-customer s cust)))

        (testing "can save and retrieve"
          (is (sid/sid? (st/save-build s build)))
          (is (= 1 (count (ec/select conn {:select :*
                                           :from :builds}))))
          (is (= build (-> (st/find-build s build-sid)
                           (select-keys (keys build))))))

        (testing "can replace jobs"
          (let [job (gen-job)
                jobs {(:id job) job}]
            (is (sid/sid? (st/save-build s (assoc build :jobs jobs))))
            (is (= 1 (count (ec/select conn {:select :*
                                             :from :jobs}))))
            (is (= jobs (:jobs (st/find-build s build-sid))))))

        (testing "can update jobs"
          (let [job (assoc (gen-job) :status :pending)
                jobs {(:id job) job}
                upd (assoc job :status :running)]
            (is (sid/sid? (st/save-build s (assoc build :jobs jobs))))
            (is (sid/sid? (st/save-build s (assoc-in build [:jobs (:id job)] upd))))
            (is (= upd (get-in (st/find-build s build-sid) [:jobs (:id job)])))))

        (testing "can list"
          (is (= [(:build-id build)]
                 (st/list-builds s [(:id cust) (:id repo)]))))))))

(deftest make-storage
  (testing "creates sql storage object using connection settings"
    (let [s (st/make-storage {:storage {:type :sql
                                        :url (:jdbcUrl eh/h2-config)}})]
      (is (some? s))
      (is (some? (-> s :conn :ds))))))
