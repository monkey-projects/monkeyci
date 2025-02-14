(ns monkey.ci.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [sid :as sid]
             [storage :as sut]
             [time :as t]]
            [monkey.ci.helpers :as h]))

(deftest transaction
  (testing "executes target"
    (is (= ::result (sut/transact (sut/make-memory-storage) (constantly ::result))))))

(deftest customers
  (h/with-memory-store st
    (testing "can find multiple"
      (let [custs (repeatedly 3 h/gen-cust)]
        (doseq [c custs]
          (sut/save-customer st c))
        (let [r (sut/find-customers st (->> custs
                                            (take 2)
                                            (map :id)))]
          (is (= (take 2 custs) r)))))))

(deftest webhooks
  (testing "webhook-sid is a sid"
    (is (sid/sid? (sut/webhook-sid "test-id"))))
  
  (testing "can create and find"
    (h/with-memory-store st
      (let [id (cuid/random-cuid)
            d {:id id}]
        (is (sid/sid? (sut/save-webhook st d)))
        (is (= d (sut/find-webhook st id))))))

  (h/with-memory-store st
    (let [wh (h/gen-webhook)]
      (is (sid/sid? (sut/save-webhook st wh)))
      
      (testing "can find for repo"
        (is (= [wh] (sut/find-webhooks-for-repo st [(:customer-id wh) (:repo-id wh)]))))

      (testing "can delete"
        (is (true? (sut/delete-webhook st (:id wh))))
        (is (nil? (sut/find-webhook st (:id wh))))))))

(deftest build-metadata
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-sid
  (testing "starts with builds"
    (is (= "builds" (first (sut/build-sid {:customer-id "cust"
                                           :repo-id "repo"
                                           :build-id "test-build"}))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sid/sid? (sut/save-build-results st md {:status :success})))
        (is (= :success (:status (sut/find-build-results st md))))))))

(defn- test-build-sid []
  (repeatedly 3 (comp str random-uuid)))

(deftest patch-build-results
  (testing "reads result, applies f with args, then writes result back"
    (h/with-memory-store st
      (let [sid (test-build-sid)]
        (is (sid/sid? (sut/patch-build-results st sid assoc :key "value")))
        (is (= {:key "value"}
               (sut/find-build-results st sid)))))))

(deftest save-build
  (testing "creates new build"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (-> (zipmap [:customer-id :repo-id :build-id] sid)
                      (assoc :start-time 100))]
        (is (sid/sid? (sut/save-build st build)))
        (is (true? (sut/build-exists? st sid)))
        (is (= build (sut/find-build st sid)))))))

(deftest find-build
  (testing "`nil` if sid is `nil`"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (zipmap [:customer-id :repo-id :build-id] sid)]
        (is (sid/sid? (sut/save-build st build)))
        (is (nil? (sut/find-build st nil))))))

  (testing "retrieves regular build"
    (h/with-memory-store st
      (let [build {:build-id "test-build"
                   :customer-id "test-cust"
                   :repo-id "test-repo"}]
        (is (sid/sid? (sut/save-build st build)))
        (is (= build (sut/find-build st (sut/ext-build-sid build)))))))

  (testing "retrieves legacy build"
    (h/with-memory-store st
      (let [md {:customer-id "test-cust"
                :repo-id "test-repo"
                :build-id "test-build"}
            results {:jobs {"test-job" {:status :success}}}
            sid (sut/ext-build-sid md)]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (sid/sid? (sut/save-build-results st sid results)))
        (let [r (sut/find-build st (sut/ext-build-sid md))]
          (is (some? (:jobs r)))
          (is (= "test-cust" (:customer-id r)))
          (is (true? (:legacy? r))))))))

(deftest parameters
  (testing "can store on customer level"
    (h/with-memory-store st
      (let [cid (sut/new-id)
            params [{:parameters
                     [{:name "param-1"
                       :value "value 1"}
                      {:name "param-2"
                       :value "value 2"}]
                     :label-filters
                     [[{:label "test-label" :value "test-value"}]]}]]
        (is (sid/sid? (sut/save-params st cid params)))
        (is (= params (sut/find-params st cid))))))

  (testing "can store and fetch single param"
    (h/with-memory-store st
      (let [param (h/gen-customer-params)
            cid (:customer-id param)]
        (is (some? cid))
        (is (sid/sid? (sut/save-param st param)))
        (is (= param (sut/find-param st (sut/params-sid cid (:id param))))))))

  (testing "can delete param"
    (h/with-memory-store st
      (let [param (h/gen-customer-params)
            cid (:customer-id param)
            sid (sut/params-sid cid (:id param))]
        (is (some? cid))
        (is (sid/sid? (sut/save-param st param)))
        (is (true? (sut/delete-param st sid)))
        (is (nil? (sut/find-param st sid)))))))

(deftest list-build-ids
  (testing "lists all build ids for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:customer-id :repo-id :build-id] sid))))))
        (let [l (sut/list-build-ids st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds l)))))))

(deftest list-builds
  (testing "lists and fetches all builds for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:customer-id :repo-id :build-id] sid))))))
        (let [l (sut/list-builds st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds (map :build-id l))))))))

(deftest find-latest-build
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc :repos {(:id repo) repo}))
          repo-sid [(:id cust) (:id repo)]
          build-idxs (range 2)
          builds (->> build-idxs
                      (map (fn [idx]
                             (-> (zipmap [:customer-id :repo-id] repo-sid)
                                 (assoc :build-id (format "build-%d" idx)
                                        :idx idx)))))]
      (is (sid/sid? (sut/save-customer st cust)))
      (doseq [b builds]
        (is (sid/sid? (sut/save-build st b))))
      
      (testing "retrieves latest build by build id"
        (let [l (sut/find-latest-build st repo-sid)]
          (is (= (last builds) l))))

      (testing "can fetch for customer"
        (is (= [(last builds)]
               (sut/find-latest-builds st (first repo-sid))))))))

(deftest list-builds-since
  (testing "retrieves builds since given timestamp"
    (h/with-memory-store st
      (let [cust-id (sut/new-id)
            repo-id (sut/new-id)
            old-build {:customer-id cust-id
                       :repo-id repo-id
                       :start-time 100}
            new-build {:customer-id cust-id
                       :repo-id repo-id
                       :start-time 200}]
        (is (sid/sid? (sut/save-customer st {:id cust-id
                                             :repos {repo-id {:id repo-id}}})))
        (is (sid/sid? (sut/save-build st old-build)))
        (is (sid/sid? (sut/save-build st new-build)))
        (is (= [new-build] (sut/list-builds-since st cust-id 150)))))))

(deftest find-next-build-idx
  (testing "max build idx plus one for this repo"
    (h/with-memory-store st
      (let [repo-sid (sid/->sid (repeatedly 2 cuid/random-cuid))
            build-ids (->> (range)
                           (map (partial format "build-%d"))
                           (take 2))
            builds (->> (range)
                        (map (fn [idx]
                               (-> (zipmap [:customer-id :repo-id :build-id]
                                           (conj repo-sid (format "build-%d" idx)))
                                   (assoc :idx (inc idx)))))
                        (take 10))]
        (doseq [b builds]
          (is (sid/sid? (sut/save-build st b))))
        (is (= (->> builds
                    (map :idx)
                    sort
                    last
                    inc)
               (sut/find-next-build-idx st repo-sid)))))))

(deftest jobs
  (h/with-memory-store st
    (testing "can save and retrieve"
      (let [[cust-id repo-id build-id :as sid] (repeatedly 3 cuid/random-cuid)
            build (-> (h/gen-build)
                      (assoc :customer-id cust-id
                             :repo-id repo-id
                             :build-id build-id
                             :script {}))
            job {:id "test-job"}]
        (is (sid/sid? (sut/save-build st build)))
        (is (sid/sid? (sut/save-job st sid job)))
        (is (= job (sut/find-job st (concat sid [(:id job)]))))
        (is (= job (-> (sut/find-build st sid)
                       (get-in [:script :jobs (:id job)])))
            "updates job in build")))))

(deftest save-ssh-keys
  (testing "writes ssh keys object"
    (h/with-memory-store st
      (let [cid (sut/new-id)
            ssh-keys [{:id (sut/new-id)
                       :description "test ssh key"
                       :private-key "test-key"}]]
        (is (sid/sid? (sut/save-ssh-keys st cid ssh-keys)))
        (is (= ssh-keys (sut/find-ssh-keys st cid)))))))

(deftest users
  (let [u {:type "github"
               :type-id 1234
               :id (sut/new-id)
               :name "test user"
           :email "test@monkeyci.com"}]
    
    (testing "can save and find github user"
      (h/with-memory-store st
        (is (sid/sid? (sut/save-user st u)))
        (is (= u (sut/find-user-by-type st [:github 1234])) "can retrieve user by github id")))

    (testing "can save and find user by cuid"
      (h/with-memory-store st
        (is (sid/sid? (sut/save-user st u)))
        (is (= u (sut/find-user st (:id u))) "can retrieve user by id")))))

(deftest update-repo
  (testing "updates repo in customer object"
    (h/with-memory-store st
      (let [[cid rid] (repeatedly sut/new-id)]
        (is (some? (sut/save-customer st {:id cid})))
        (is (some? (sut/save-repo st {:id rid
                                      :customer-id cid
                                      :url "http://test-repo"})))
        (is (some? (sut/update-repo st [cid rid] assoc :url "updated-url")))
        (is (= "updated-url" (:url (sut/find-repo st [cid rid]))))))))

(deftest delete-repo
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc-in [:repos (:id repo)] repo))
          build (-> (h/gen-build)
                    (assoc :customer-id (:id cust)
                           :repo-id (:id repo)))
          wh (-> (h/gen-webhook)
                 (assoc :customer-id (:id cust)
                        :repo-id (:id repo)))
          repo-sid [(:id cust) (:id repo)]]
      (is (sid/sid? (sut/save-customer st cust)))
      (is (sid/sid? (sut/save-build st build)))
      (is (sid/sid? (sut/save-webhook st wh)))
      
      (testing "removes repo from customer"
        (is (true? (sut/delete-repo st repo-sid)))
        (is (empty? (-> (sut/find-customer st (:id cust))
                        :repos))))

      (testing "removes all builds for repo"
        (is (empty? (sut/list-build-ids st repo-sid))))

      (testing "removes webhooks for repo"
        (is (empty? (sut/find-webhook st (:id wh))))))))

(deftest watch-github-repo
  (h/with-memory-store st
    (let [[cid rid gid] (repeatedly sut/new-id)
          repo {:id rid
                :customer-id cid
                :github-id gid
                :url "http://test-repo"}]

      (testing "new repo"
        (let [sid (sut/watch-github-repo st repo)]
          
          (testing "creates repo"
            (is (sid/sid? sid))
            (is (= repo (sut/find-repo st [cid rid]))))

          (testing "adds to watched repos"
            (is (= [repo] (sut/find-watched-github-repos st gid))))))

      (testing "sets github id in existing repo"
        (is (sid/sid? (sut/save-repo st (dissoc repo :github-id))))
        (is (sid/sid? (sut/watch-github-repo st repo)))
        (is (= gid (:github-id (sut/find-repo st [cid rid]))))))))

(deftest unwatch-github-repo
  (h/with-memory-store st
    (let [[cid rid gid] (repeatedly sut/new-id)
          repo {:id rid
                :customer-id cid
                :github-id gid
                :url "http://test-repo"}
          _ (sut/watch-github-repo st repo)
          r (sut/unwatch-github-repo st [cid rid])]

      (is (true? r))
      
      (testing "removes from watch list"
        (is (empty? (sut/find-watched-github-repos st gid))))

      (testing "removes github id from repo"
        (is (= (dissoc repo :github-id)
               (-> (sut/find-repo st [cid rid]))))))))

(deftest join-requests
  (h/with-memory-store st
    (let [req (->> (repeatedly 3 sut/new-id)
                   (zipmap [:id :user-id :customer-id]))]
      (testing "can save and find"
        (is (sid/sid? (sut/save-join-request st req)))
        (is (= req (sut/find-join-request st (:id req)))))

      (testing "can list for user"
        (is (= req (-> (sut/list-user-join-requests st (:user-id req))
                       first
                       (select-keys (keys req))))))

      (testing "can list for customer"
        (is (= [req] (sut/list-customer-join-requests st (:customer-id req))))))))

(deftest credit-subscriptions
  (h/with-memory-store st
    (let [cs    (-> (h/gen-credit-subs)
                    (assoc :valid-from 1000
                           :valid-until 2000))
          other (-> (h/gen-credit-subs)
                    (assoc :valid-from 1000)
                    (dissoc :valid-until))]
      (testing "can save and find"
        (is (sid/sid? (sut/save-credit-subscription st cs)))
        (is (= cs (sut/find-credit-subscription st [(:customer-id cs) (:id cs)]))))

      (testing "can list for customer"
        (is (= [cs] (sut/list-customer-credit-subscriptions st (:customer-id cs)))))

      (is (sid/sid? (sut/save-credit-subscription st other)))              

      (testing "can list active"
        (is (= [cs other] (sut/list-active-credit-subscriptions st 1500)))
        (is (empty (sut/list-active-credit-subscriptions st 3000)))))))

(deftest credit-consumptions
  (h/with-memory-store st
    (let [cs (h/gen-credit-cons)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-credit-consumption st cs)))
        (is (= cs (sut/find-credit-consumption st (sut/credit-cons-sid (:customer-id cs) (:id cs))))))

      (testing "can list for customer"
        (is (= [cs] (sut/list-customer-credit-consumptions st (:customer-id cs))))))))

(deftest customer-credits
  (h/with-memory-store st
    (let [now (t/now)
          cred (-> (h/gen-cust-credit)
                   (assoc :from-time now
                          :amount 100M))
          cid (:customer-id cred)
          repo (h/gen-repo)
          rid (:id repo)
          cust {:id cid
                :repos {(:id repo) repo}}]
      (is (sid/sid? (sut/save-customer st cust)))

      (testing "can save and find"
        (is (sid/sid? (sut/save-customer-credit st cred)))
        (is (= cred (sut/find-customer-credit st (:id cred)))))

      (testing "can list for customer since date"
        (is (= [cred] (sut/list-customer-credits-since st cid (- now 100)))))

      (testing "available credits"
        (is (sid/sid? (sut/save-credit-consumption
                       st
                       {:id (cuid/random-cuid)
                        :customer-id cid
                        :repo-id rid
                        :credit-id (:id cred)
                        :amount 20M})))
        
        (testing "can calculate total"
          (is (= 80M (sut/calc-available-credits st cid))))

        (testing "can list credit entities"
          (let [avail (sut/list-available-credits st cid)]
            (is (= 1 (count avail)))
            (is (= cred (first avail))))
          
          (is (empty? (sut/list-available-credits st (cuid/random-cuid)))
              "empty if no matching records")

          (is (sid/sid? (sut/save-credit-consumption
                         st
                         {:id (cuid/random-cuid)
                          :customer-id cid
                          :repo-id rid
                          :credit-id (:id cred)
                          :amount 80M})))
          (is (empty? (sut/list-available-credits st cid))
              "empty if all is consumed"))))))

(deftest bitbucket-webhooks
  (h/with-memory-store st
    (let [wh (h/gen-webhook)
          bb (-> (h/gen-bb-webhook)
                 (assoc :webhook-id (:id wh)))]
      (is (sid/sid? (sut/save-webhook st wh)))
      
      (testing "can save and find"
        (is (sid/sid? (sut/save-bb-webhook st bb)))
        (is (= bb (sut/find-bb-webhook st (:id bb)))))

      (testing "can find for webhook id"
        (is (= bb (sut/find-bb-webhook-for-webhook st (:webhook-id bb)))))

      (testing "can search using filter"
        (is (= [(merge bb (select-keys wh [:customer-id :repo-id]))]
               (sut/search-bb-webhooks st (select-keys bb [:webhook-id])))
            "search by webhook id")
        (is (= [(:id bb)] (->> (sut/search-bb-webhooks st (select-keys wh [:customer-id]))
                               (map :id)))
            "search by customer id")
        (is (empty? (sut/search-bb-webhooks st {:customer-id "nonexisting"})))))))

(deftest crypto
  (h/with-memory-store st
    (let [crypto (h/gen-crypto)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-crypto st crypto)))
        (is (= crypto (sut/find-crypto st (:customer-id crypto))))))))

(deftest sysadmin
  (h/with-memory-store st
    (let [sysadmin {:user-id (cuid/random-cuid)
                    :password "test-sysadmin"}]
      (testing "can save and find"
        (is (sid/sid? (sut/save-sysadmin st sysadmin)))
        (is (= sysadmin (sut/find-sysadmin st (:user-id sysadmin))))))))

(deftest invoices
  (h/with-memory-store st
    (let [cust (h/gen-cust)
          inv (-> (h/gen-invoice)
                  (assoc :customer-id (:id cust)))]
      (testing "can save and find by id"
        (is (sid/sid? (sut/save-invoice st inv)))
        (is (= inv (sut/find-invoice st [(:id cust) (:id inv)]))))

      (testing "can list for customer"
        (is (= [inv] (sut/list-invoices-for-customer st (:id cust))))))))

(deftest runner-details
  (h/with-memory-store st
    (let [build (h/gen-build)
          build->sid sut/ext-build-sid
          details {:build-id (:build-id build)
                   :runner :test-runner
                   :details {:test :details}}]
      (is (sid/sid? (sut/save-build st build)))
      
      (testing "can save"
        (is (sid/sid? (sut/save-runner-details st (build->sid build) details))))

      (testing "can find by build sid"
        (is (= details (sut/find-runner-details st (build->sid build))))))))
