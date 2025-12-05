(ns monkey.ci.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [sid :as sid]
             [storage :as sut]
             [time :as t]]
            [monkey.ci.test.helpers :as h]))

(deftest transaction
  (testing "executes target"
    (is (= ::result (sut/transact (sut/make-memory-storage) (constantly ::result))))))

(deftest orgs
  (h/with-memory-store st
    (let [orgs (repeatedly 3 h/gen-org)]
      (doseq [c orgs]
        (sut/save-org st c))
      (testing "can find multiple"
        (let [r (sut/find-orgs st (->> orgs
                                       (take 2)
                                       (map :id)))]
          (is (= (take 2 orgs) r))))

      (testing "can count"
        (is (= 3 (sut/count-orgs st))))

      (testing "can delete"
        (let [org (first orgs)]
          (is (true? (sut/delete-org st (:id org))))
          (is (nil? (sut/find-org st (:id org)))))))

    (testing "can find by display id"
      (let [org {:id (cuid/random-cuid)
                 :name "test org"
                 :display-id "test-org"}]
        (is (sid/sid? (sut/save-org st org)))
        (is (= org (sut/find-org-by-display-id st "test-org")))))))

(deftest init-org
  (h/with-memory-store st
    (let [org {:id (sut/new-id)
               :name "test org"}
          user {:id (sut/new-id)
                :name "testuser"
                :type "github"
                :type-id "1243"}]
      (is (some? (sut/save-user st user)))

      (let [res (sut/init-org st {:org org
                                  :user-id (:id user)
                                  :credits [{:amount 1000
                                             :from (t/now)}]
                                  :dek "test-dek"})]
        (is (sid/sid? res))

        (let [m (sut/find-org st (:id org))]
          (testing "creates new org"
            (is (= (:id org) (:id m))))

          (testing "assigns display id"
            (is (= "test-org" (:display-id m)))))

        (testing "links org to user"
          (is (= [(:id org)] (->> (sut/list-user-orgs st (:id user))
                                  (map :id)))))

        (testing "creates credit subscriptions"
          (let [cs (sut/list-org-credit-subscriptions st (:id org))]
            (is (= 1 (count cs)))
            (is (= 1000 (-> cs first :amount)))))

        (testing "creates starting credit"
          (let [c (sut/list-org-credits st (:id org))]
            (is (= 1 (count c)))
            (is (= 1000 (-> c first :amount)))))

        (testing "creates crypto with dek"
          (let [c (sut/find-crypto st (:id org))]
            (is (some? c))
            (is (= "test-dek" (:dek c)))))))))

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
        (is (= [wh] (sut/find-webhooks-for-repo st [(:org-id wh) (:repo-id wh)]))))

      (testing "can delete"
        (is (true? (sut/delete-webhook st (:id wh))))
        (is (nil? (sut/find-webhook st (:id wh))))))))

(deftest build-metadata
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :org-id "test-cust"}]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-sid
  (testing "starts with builds"
    (is (= "builds" (first (sut/build-sid {:org-id "cust"
                                           :repo-id "repo"
                                           :build-id "test-build"}))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :org-id "test-cust"}]
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
            build (-> (zipmap [:org-id :repo-id :build-id] sid)
                      (assoc :start-time 100))]
        (is (sid/sid? (sut/save-build st build)))
        (is (true? (sut/build-exists? st sid)))
        (is (= build (sut/find-build st sid)))))))

(deftest find-build
  (testing "`nil` if sid is `nil`"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (zipmap [:org-id :repo-id :build-id] sid)]
        (is (sid/sid? (sut/save-build st build)))
        (is (nil? (sut/find-build st nil))))))

  (testing "retrieves regular build"
    (h/with-memory-store st
      (let [build {:build-id "test-build"
                   :org-id "test-cust"
                   :repo-id "test-repo"}]
        (is (sid/sid? (sut/save-build st build)))
        (is (= build (sut/find-build st (sut/ext-build-sid build)))))))

  (testing "retrieves legacy build"
    (h/with-memory-store st
      (let [md {:org-id "test-cust"
                :repo-id "test-repo"
                :build-id "test-build"}
            results {:jobs {"test-job" {:status :success}}}
            sid (sut/ext-build-sid md)]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (sid/sid? (sut/save-build-results st sid results)))
        (let [r (sut/find-build st (sut/ext-build-sid md))]
          (is (some? (:jobs r)))
          (is (= "test-cust" (:org-id r)))
          (is (true? (:legacy? r))))))))

(deftest parameters
  (testing "can store on org level"
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
      (let [param (h/gen-org-params)
            cid (:org-id param)]
        (is (some? cid))
        (is (sid/sid? (sut/save-param st param)))
        (is (= param (sut/find-param st (sut/params-sid cid (:id param))))))))

  (testing "can delete param"
    (h/with-memory-store st
      (let [param (h/gen-org-params)
            cid (:org-id param)
            sid (sut/params-sid cid (:id param))]
        (is (some? cid))
        (is (sid/sid? (sut/save-param st param)))
        (is (true? (sut/delete-param st sid)))
        (is (nil? (sut/find-param st sid)))))))

(deftest list-build-ids
  (testing "lists all build ids for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-org" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:org-id :repo-id :build-id] sid))))))
        (let [l (sut/list-build-ids st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds l)))))))

(deftest list-builds
  (testing "lists and fetches all builds for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-org" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:org-id :repo-id :build-id] sid))))))
        (let [l (sut/list-builds st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds (map :build-id l))))))))

(deftest find-latest-build
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-org)
                   (assoc :repos {(:id repo) repo}))
          repo-sid [(:id cust) (:id repo)]
          build-idxs (range 2)
          builds (->> build-idxs
                      (map (fn [idx]
                             (-> (zipmap [:org-id :repo-id] repo-sid)
                                 (assoc :build-id (format "build-%d" idx)
                                        :idx idx)))))]
      (is (sid/sid? (sut/save-org st cust)))
      (doseq [b builds]
        (is (sid/sid? (sut/save-build st b))))
      
      (testing "retrieves latest build by build id"
        (let [l (sut/find-latest-build st repo-sid)]
          (is (= (last builds) l))))

      (testing "can fetch for org"
        (is (= [(last builds)]
               (sut/find-latest-builds st (first repo-sid))))))))

(deftest find-latest-n-builds
  (h/with-memory-store st
    (let [repos (repeatedly 2 h/gen-repo)
          cust (-> (h/gen-org)
                   (assoc :repos (->> repos
                                      (map (fn [r] [(:id r) r]))
                                      (into {}))))
          now (t/now)
          start (- now (t/hours->millis 100))]
      (is (sid/sid? (sut/save-org st cust)))
      ;; Create 10 builds for each repo
      (doseq [n (range 10)]
        (doseq [r repos]
          (is (sid/sid? (sut/save-build st {:org-id (:id cust)
                                            :repo-id (:id r)
                                            :build-id (format "build-%d" n)
                                            :idx n
                                            :start-time (+ start (* n (t/hours->millis 1)))})))))
      (let [m (sut/find-latest-n-builds st (:id cust) 10)]
        (is (= 10 (count m)))
        (is (every? (comp (partial = 5) count)
                    (vals (group-by :repo-id m))))))))

(deftest list-builds-since
  (testing "retrieves builds since given timestamp"
    (h/with-memory-store st
      (let [cust-id (sut/new-id)
            repo-id (sut/new-id)
            old-build {:org-id cust-id
                       :repo-id repo-id
                       :start-time 100}
            new-build {:org-id cust-id
                       :repo-id repo-id
                       :start-time 200}]
        (is (sid/sid? (sut/save-org st {:id cust-id
                                             :repos {repo-id {:id repo-id}}})))
        (is (sid/sid? (sut/save-build st old-build)))
        (is (sid/sid? (sut/save-build st new-build)))
        (is (= [new-build] (->> (sut/list-builds-since st cust-id 150)
                                (map #(select-keys % (keys new-build))))))))))

(deftest find-next-build-idx
  (testing "max build idx plus one for this repo"
    (h/with-memory-store st
      (let [repo-sid (sid/->sid (repeatedly 2 cuid/random-cuid))
            build-ids (->> (range)
                           (map (partial format "build-%d"))
                           (take 2))
            builds (->> (range)
                        (map (fn [idx]
                               (-> (zipmap [:org-id :repo-id :build-id]
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
                      (assoc :org-id cust-id
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

    (h/with-memory-store st
      (testing "can save and find github user"
        (is (sid/sid? (sut/save-user st u)))
        (is (= u (sut/find-user-by-type st [:github 1234])) "can retrieve user by github id"))

      (testing "can find user by cuid"
        (is (= u (sut/find-user st (:id u))) "can retrieve user by id"))

      (testing "can find by email"
        (is (= [u] (sut/find-users-by-email st (:email u)))))

      (testing "can count"
        (is (= 1 (sut/count-users st))))

      (testing "can delete"
        (is (true? (sut/delete-user st (:id u))))
        (is (= 0 (sut/count-users st)))))))

(deftest update-repo
  (testing "updates repo in org object"
    (h/with-memory-store st
      (let [[cid rid] (repeatedly sut/new-id)]
        (is (some? (sut/save-org st {:id cid})))
        (is (some? (sut/save-repo st {:id rid
                                      :org-id cid
                                      :url "http://test-repo"})))
        (is (some? (sut/update-repo st [cid rid] assoc :url "updated-url")))
        (is (= "updated-url" (:url (sut/find-repo st [cid rid]))))))))

(deftest delete-repo
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          cust (-> (h/gen-org)
                   (assoc-in [:repos (:id repo)] repo))
          build (-> (h/gen-build)
                    (assoc :org-id (:id cust)
                           :repo-id (:id repo)))
          wh (-> (h/gen-webhook)
                 (assoc :org-id (:id cust)
                        :repo-id (:id repo)))
          repo-sid [(:id cust) (:id repo)]]
      (is (sid/sid? (sut/save-org st cust)))
      (is (sid/sid? (sut/save-build st build)))
      (is (sid/sid? (sut/save-webhook st wh)))
      
      (testing "removes repo from org"
        (is (true? (sut/delete-repo st repo-sid)))
        (is (empty? (-> (sut/find-org st (:id cust))
                        :repos))))

      (testing "removes all builds for repo"
        (is (empty? (sut/list-build-ids st repo-sid))))

      (testing "removes webhooks for repo"
        (is (empty? (sut/find-webhook st (:id wh))))))))

(deftest count-repo
  (testing "counts repos in storage"
    (h/with-memory-store st
      (let [[oid rid] (repeatedly sut/new-id)]
        (is (some? (sut/save-org st {:id oid})))
        (is (= 0 (sut/count-repos st)))
        (is (some? (sut/save-repo st {:id rid
                                      :org-id oid
                                      :url "http://test-repo"})))
        (is (= 1 (sut/count-repos st)))))))

(deftest watch-github-repo
  (h/with-memory-store st
    (let [[cid rid gid] (repeatedly sut/new-id)
          repo {:id rid
                :org-id cid
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
                :org-id cid
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
                   (zipmap [:id :user-id :org-id]))]
      (testing "can save and find"
        (is (sid/sid? (sut/save-join-request st req)))
        (is (= req (sut/find-join-request st (:id req)))))

      (testing "can list for user"
        (is (= req (-> (sut/list-user-join-requests st (:user-id req))
                       first
                       (select-keys (keys req))))))

      (testing "can list for org"
        (is (= [req] (sut/list-org-join-requests st (:org-id req))))))))

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
        (is (= cs (sut/find-credit-subscription st [(:org-id cs) (:id cs)]))))

      (testing "can list for org"
        (is (= [cs] (sut/list-org-credit-subscriptions st (:org-id cs)))))

      (is (sid/sid? (sut/save-credit-subscription st other)))              

      (testing "can list active"
        (is (= [cs other] (sut/list-active-credit-subscriptions st 1500)))
        (is (empty (sut/list-active-credit-subscriptions st 3000)))))))

(deftest credit-consumptions
  (h/with-memory-store st
    (let [cs (h/gen-credit-cons)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-credit-consumption st cs)))
        (is (= cs (sut/find-credit-consumption st (sut/credit-cons-sid (:org-id cs) (:id cs))))))

      (testing "can list for org"
        (is (= [cs] (sut/list-org-credit-consumptions st (:org-id cs))))))))

(deftest org-credits
  (h/with-memory-store st
    (let [now (t/now)
          cred (-> (h/gen-org-credit)
                   (assoc :from-time now
                          :amount 100M))
          cid (:org-id cred)
          repo (h/gen-repo)
          rid (:id repo)
          cust {:id cid
                :repos {(:id repo) repo}}]
      (is (sid/sid? (sut/save-org st cust)))

      (testing "can save and find"
        (is (sid/sid? (sut/save-org-credit st cred)))
        (is (= cred (sut/find-org-credit st (:id cred)))))

      (testing "can list for org since date"
        (is (= [cred] (sut/list-org-credits-since st cid (- now 100)))))

      (testing "available credits"
        (is (sid/sid? (sut/save-credit-consumption
                       st
                       {:id (cuid/random-cuid)
                        :org-id cid
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
                          :org-id cid
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
        (is (= [(merge bb (select-keys wh [:org-id :repo-id]))]
               (sut/search-bb-webhooks st (select-keys bb [:webhook-id])))
            "search by webhook id")
        (is (= [(:id bb)] (->> (sut/search-bb-webhooks st (select-keys wh [:org-id]))
                               (map :id)))
            "search by org id")
        (is (empty? (sut/search-bb-webhooks st {:org-id "nonexisting"})))))))

(deftest crypto
  (h/with-memory-store st
    (let [crypto (h/gen-crypto)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-crypto st crypto)))
        (is (= crypto (sut/find-crypto st (:org-id crypto))))))))

(deftest sysadmin
  (h/with-memory-store st
    (let [sysadmin {:user-id (cuid/random-cuid)
                    :password "test-sysadmin"}]
      (testing "can save and find"
        (is (sid/sid? (sut/save-sysadmin st sysadmin)))
        (is (= sysadmin (sut/find-sysadmin st (:user-id sysadmin))))))))

(deftest invoices
  (h/with-memory-store st
    (let [cust (h/gen-org)
          inv (-> (h/gen-invoice)
                  (assoc :org-id (:id cust)))]
      (testing "can save and find by id"
        (is (sid/sid? (sut/save-invoice st inv)))
        (is (= inv (sut/find-invoice st [(:id cust) (:id inv)]))))

      (testing "can list for org"
        (is (= [inv] (sut/list-invoices-for-org st (:id cust))))))))

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

(deftest queued-tasks
  (h/with-memory-store st
    (let [task {:id (cuid/random-cuid)
                :details ::test-task
                :creation-time (t/now)}]
      (testing "can save"
        (is (sid/sid? (sut/save-queued-task st task))))

      (testing "can list"
        (is (= [task] (sut/list-queued-tasks st))))

      (testing "can delete"
        (is (true? (sut/delete-queued-task st (:id task))))
        (is (empty? (sut/list-queued-tasks st)))))))

(deftest job-events
  (h/with-memory-store st
    (let [job (h/gen-job)
          evt (-> (h/gen-job-evt)
                  (assoc :job-id (:id job))
                  (merge (select-keys job [:org-id :repo-id :build-id])))
          job-sid (conj (vec (b/sid job)) (:id job))]
      (is (= 4 (count job-sid)))
      (is (= 5 (count (sut/job-event->sid evt))))
      
      (testing "can save and list for job"
        (is (sid/sid? (sut/save-job-event st evt)))
        (is (= [evt] (sut/list-job-events st job-sid)))))))

(deftest user-tokens
  (h/with-memory-store st
    (let [t (h/gen-user-token)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-user-token st t)))
        (is (= t (sut/find-user-token st [(:user-id t) (:id t)]))))

      (testing "can list for user"
        (is (= [t] (sut/list-user-tokens st (:user-id t)))))

      (testing "can find by token"
        (is (= t (sut/find-user-token-by-token st (:token t)))))

      (testing "can delete"
        (is (true? (sut/delete-user-token st [(:user-id t) (:id t)])))))))

(deftest org-tokens
  (h/with-memory-store st
    (let [t (h/gen-org-token)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-org-token st t)))
        (is (= t (sut/find-org-token st [(:org-id t) (:id t)]))))

      (testing "can list for org"
        (is (= [t] (sut/list-org-tokens st (:org-id t)))))

      (testing "can find by token"
        (is (= t (sut/find-org-token-by-token st (:token t)))))

      (testing "can delete"
        (is (true? (sut/delete-org-token st [(:org-id t) (:id t)])))))))

(deftest mailings
  (h/with-memory-store st
    (let [m (h/gen-mailing)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-mailing st m)))
        (is (= m (sut/find-mailing st (:id m)))))

      (testing "can list"
        (is (= [m] (sut/list-mailings st))))

      (testing "can delete"
        (is (true? (sut/delete-mailing st (:id m)))))

      (testing "sent mailings"
        (is (sid/sid? (sut/save-mailing st m)))
        (let [sm {:id (cuid/random-cuid)
                  :mailing-id (:id m)
                  :sent-at (t/now)}]
          (testing "can save and find"
            (let [sid (sut/save-sent-mailing st sm)]
              (is (sid/sid? sid))
              (is (= sm (sut/find-sent-mailing st sid)))))

          (testing "can list for mailing"
            (is (= [sm] (sut/list-sent-mailings st (:id m))))))))))

(deftest email-registrations
  (h/with-memory-store st
    (let [r (h/gen-email-registration)]
      (testing "can save and find"
        (is (sid/sid? (sut/save-email-registration st r)))
        (is (= r (sut/find-email-registration st (:id r)))))

      (testing "can list"
        (is (= [r] (sut/list-email-registrations st))))

      (testing "can find by email"
        (is (= r (sut/find-email-registration-by-email st (:email r))))))))

(deftest user-settings
  (h/with-memory-store st
    (let [u (h/gen-user)
          s (-> (h/gen-user-settings)
                (assoc :user-id (:id u)))]
      (is (sid/sid? (sut/save-user st u)))

      (testing "can save and find for user"
        (is (sid/sid? (sut/save-user-settings st s)))
        (is (= s (sut/find-user-settings st (:id u))))))))
