(ns monkey.ci.storage.sql-test
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is testing]]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [protocols :as p]
             [sid :as sid]
             [storage :as st]
             [time :as t]]
            [monkey.ci.entities
             [core :as ec]
             [helpers :as eh]]
            [monkey.ci.spec.gen :as sg]
            [monkey.ci.storage.sql :as sut]
            [monkey.ci.test.helpers :as h]
            [monkey.ci.web.auth :as auth]))

(defmacro with-storage [conn s & body]
  `(eh/with-prepared-db ~conn
     (let [~s (sut/make-storage (constantly ~conn))]
       ~@body)))

(deftest ^:sql orgs
  (with-storage conn s
    (testing "can write and read"
      (let [org (h/gen-org)]
        (is (sid/sid? (st/save-org s org)))
        (is (= 1 (count (ec/select-orgs conn [:is :id [:not nil]]))))
        (is (some? (ec/select-org conn (ec/by-cuid (:id org)))))
        (is (= (assoc org :repos {})
               (st/find-org s (:id org))))))

    (testing "can operate within a transaction"
      (st/with-transaction s tx
        (let [org (h/gen-org)]
          (is (sid/sid? (st/save-org tx org)))
          (is (some? (ec/select-org ((:get-conn tx) tx) (ec/by-cuid (:id org)))))
          (is (= (assoc org :repos {})
                 (st/find-org tx (:id org)))))))

    (testing "copies overrides in trx"
      (st/with-transaction s tx
        (is (= (:overrides s) (:overrides tx)))))

    (testing "can write and read with repos"
      (let [org (h/gen-org)
            repo {:name "test repo"
                  :org-id (:id org)
                  :id "test-repo"
                  :url "http://test-repo"}]
        (is (sid/sid? (st/save-org s org)))
        (is (sid/sid? (st/save-repo s repo)))
        (is (= (assoc org :repos {(:id repo) (dissoc repo :org-id)})
               (st/find-org s (:id org))))))

    (testing "can delete with repos"
      (let [org (h/gen-org)
            repo {:name "test repo"
                  :org-id (:id org)
                  :id "test-repo"
                  :url "http://test-repo"}]
        (is (sid/sid? (st/save-org s org)))
        (is (sid/sid? (st/save-repo s repo)))
        (is (some? (st/find-org s (:id org))))
        (is (true? (st/delete-org s (:id org)))
            "expected to delete org record")
        (is (nil? (st/find-org s (:id org)))
            "did not expect to find org after deletion")))

    (testing "can search"
      (let [org (-> (h/gen-org)
                    (assoc :name "test org"))]
        (is (sid/sid? (st/save-org s org)))
        
        (testing "by name"
          (is (= [org] (st/search-orgs s {:name "test"}))))

        (testing "by id"
          (is (= [org] (st/search-orgs s {:id (:id org)}))))))

    (testing "can find multiple by id"
      (let [orgs (repeatedly 3 h/gen-org)]
        (doseq [c orgs]
          (st/save-org s c))
        (let [r (st/find-orgs s (->> orgs
                                     (take 2)
                                     (map :id)))]
          (is (= (->> (take 2 orgs)
                      (map :id)
                      (set))
                 (->> (map :id r)
                      (set)))))))

    (testing "can count"
      (is (pos? (st/count-orgs s))))

    (let [org {:name "another org"
               :display-id "another-org"
               :id (cuid/random-cuid)}]
      (is (sid/sid? (st/save-org s org)))
      
      (testing "can find by display-id"
        (is (= (:id org) (:id (st/find-org-by-display-id s "another-org")))))

      (testing "can find id by display id"
        (is (= (:id org) (st/find-org-id-by-display-id s "another-org")))))))

(deftest ^:sql init-org
  (with-storage conn s
    (let [org (-> (h/gen-org)
                  (assoc :name "test org"))
          user {:id (cuid/random-cuid)
                :type "github"
                :type-id "12342"}
          _ (st/save-user s user)
          res (st/init-org s {:org org
                              :user-id (:id user)
                              :credits {:amount 1000
                                        :from (t/now)}
                              :dek "test-dek"})]
      
      (testing "creates new org"
        (is (sid/sid? res))
        (let [m (st/find-org s (last res))]
          (is (= (:id org) (:id m)))
          (is (= "test-org" (:display-id m)))))

      (testing "links org to user"
        (is (= 1 (count (st/list-user-orgs s (:id user))))))

      (testing "creates credit subscription"
        (let [cs (st/list-org-credit-subscriptions s (:id org))]
          (is (= 1 (count cs)))
          (is (= 1000M (-> cs first :amount)))))

      (testing "creates initial credits"
        (let [c (st/list-org-credits s (:id org))]
          (is (= 1 (count c)))
          (is (= 1000M (-> c first :amount)))))

      (testing "creates crypto with dek"
        (is (= "test-dek" (-> (st/find-crypto s (:id org))
                              :dek)))))))

(deftest ^:sql repos
  (with-storage conn s
    (let [repo {:name "test repo"
                :id "test-repo"}
          lbl (str "test-label-" (cuid/random-cuid))
          org (-> (h/gen-org)
                  (assoc-in [:repos (:id repo)] repo))
          sid [(:id org) (:id repo)]]
      
      (testing "saved with org"
        (is (sid/sid? (st/save-org s org)))
        (is (= (assoc repo :org-id (:id org))
               (st/find-repo s sid))))

      (testing "saved with `save-repo`"
        (let [r (assoc repo :org-id (:id org))
              sid (vec (take-last 2 (st/save-repo s r)))]
          (is (sid/sid? sid))
          (is (= [(:id org) (:id repo)] sid))
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
                                         :org-id (:id org)
                                         :labels labels})
              sid [(:id org) "new-repo"]]
          (is (= sid (take-last 2 saved-sid)))
          (is (= 1 (count (ec/select-repo-labels conn [:= :name "test-label"]))))
          (is (= "new-repo" (get-in (st/find-org s (:id org)) [:repos "new-repo" :id])))
          (let [repo (st/find-repo s sid)]
            (is (some? repo))
            (is (= labels (:labels repo))))))

      (testing "lists display ids"
        (is (= ["test-repo" "new-repo"] (st/list-repo-display-ids s (:id org)))))

      (testing "inserts repo-idx"
        (let [r (ec/select-repo conn [:= :display-id (:id repo)])
              ri (ec/select-repo-idx conn [:= :repo-id (:id r)])]
          (is (some? ri))
          (is (= 1 (:next-idx ri)))))

      (testing "delete repo"
        (is (true? (st/delete-repo s sid)))
        
        (testing "removes repo from org"
          (is (nil? (st/find-repo s sid)))
          (is (not (contains? (-> (st/find-org s (:id org))
                                  :repos)
                              (:id repo))))))

      (testing "can count"
        (is (= 1 (st/count-repos s)))))))

(deftest ^:sql watched-github-repos
  (with-storage conn s
    (let [org (h/gen-org)
          github-id 64253
          repo {:name "github test"
                :id "github-test"
                :url "http://github.com/test"
                :org-id (:id org)
                :github-id github-id}]
      (is (sid/sid? (st/save-org s org)))
      
      (testing "can find watched repos"
        (let [repo-sid (st/watch-github-repo s repo)]
          (is (sid/sid? repo-sid))
          (is (= repo (st/find-repo s repo-sid)))
          (is (= [repo] (st/find-watched-github-repos s github-id)))))

      (testing "can unwatch"
        (is (true? (st/unwatch-github-repo s [(:id org) (:id repo)])))
        (is (empty? (st/find-watched-github-repos s github-id)))
        (is (nil? (-> (st/find-repo s ((juxt :org-id :id) repo))
                      :github-id))))

      (testing "empty if no matches"
        (is (empty? (st/find-watched-github-repos s 12432)))))))

(deftest ^:sql ssh-keys
  (with-storage conn s
    (testing "ssh keys"
      (let [{org-id :id :as org} (h/gen-org)
            k (assoc (h/gen-ssh-key) :org-id org-id)]
        (is (sid/sid? (st/save-org s org)))
        
        (testing "can create and retrieve"
          (let [ce (ec/select-org conn (ec/by-cuid org-id))]
            (is (sid/sid? (st/save-ssh-keys s org-id [k])))
            (is (= 1 (count (ec/select-ssh-keys conn (ec/by-org (:id ce))))))
            (is (= k (->> (st/find-ssh-keys s org-id)
                          (first)
                          (mc/remove-vals nil?))))))

        (testing "can update label filters"
          (let [lf [[{:label "test-label"
                      :value "test-value"}]]]
            (is (sid/sid? (st/save-ssh-keys s org-id [(assoc k :label-filters lf)])))
            (let [matches (st/find-ssh-keys s org-id)]
              (is (= 1 (count matches)))
              (is (= lf (-> matches first :label-filters))))))))))

(deftest ^:sql webhooks
  (with-storage conn s
    (testing "webhooks"
      (let [org (h/gen-org)
            repo (-> (h/gen-repo)
                     (assoc :org-id (:id org)))
            wh (-> (h/gen-webhook)
                   (assoc :org-id (:id org)
                          :repo-id (:id repo)
                          :secret-key (auth/generate-secret-key)
                          :creation-time 100
                          :last-inv-time 200))]
        (is (some? (st/save-org s (assoc-in org [:repos (:id repo)] repo))))
        
        (testing "can create and retrieve"
          (is (sid/sid? (st/save-webhook s wh)))
          (is (= wh (st/find-webhook s (:id wh)))))

        (testing "can find by repo"
          (is (= [wh] (st/find-webhooks-for-repo s [(:id org) (:id repo)]))))

        (testing "saves last invocation time on update"
          (let [upd (assoc wh :last-inv-time (t/now))]
            (is (sid/sid? (st/save-webhook s upd)))
            (is (= (:last-inv-time upd)
                   (-> (st/find-webhook s (:id upd))
                       :last-inv-time)))))        

        (testing "can delete"
          (is (true? (st/delete-webhook s (:id wh))))
          (is (nil? (st/find-webhook s (:id wh)))))))))

(deftest ^:sql org-params
  (with-storage conn s
    (let [{org-id :id :as org} (h/gen-org)
          params (assoc (h/gen-org-params) :org-id org-id)
          sid (st/params-sid org-id (:id params))]
      (is (sid/sid? (st/save-org s org)))
      
      (testing "can create and retrieve multiple"
        (let [ce (ec/select-org conn (ec/by-cuid org-id))]
          (is (sid/sid? (st/save-params s org-id [params])))
          (is (= [params] (st/find-params s org-id)))))

      (testing "can create and retrieve single"
        (let [ce (ec/select-org conn (ec/by-cuid org-id))]
          (is (sid/sid? (st/save-param s params)))
          (is (= params (st/find-param s sid)))))
      
      (testing "can update label filters"
        (let [lf [[{:label "test-label"
                    :value "test-value"}]]]
          (is (sid/sid? (st/save-params s org-id [(assoc params :label-filters lf)])))
          (let [matches (st/find-params s org-id)]
            (is (= 1 (count matches)))
            (is (= lf (-> matches first :label-filters))))))

      (testing "can update parameter values"
        (let [pv [{:name "new-param"
                   :value "new value"}]]
          (is (sid/sid? (st/save-params s org-id [(assoc params :parameters pv)])))
          (let [matches (st/find-params s org-id)]
            (is (= 1 (count matches)))
            (is (= pv (-> matches first :parameters))))))

      (testing "empty for nonexisting org"
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
                   (dissoc :orgs :orgomers))
          user->id (juxt :type :type-id)]
      (testing "can save and find"
        (is (sid/sid? (st/save-user s user)))
        (is (= user (st/find-user-by-type s (user->id user)))))

      (testing "can find by cuid"
        (is (= user (st/find-user s (:id user)))))

      (testing "can link to org"
        (let [org (h/gen-org)
              user (assoc user :orgs [(:id org)])]
          (is (sid/sid? (st/save-org s org)))
          (is (sid/sid? (st/save-user s user)))
          (is (= (:orgs user)
                 (-> (st/find-user s (:id user)) :orgs)))))

      (testing "can find orgs"
        (is (not-empty (st/list-user-orgs s (:id user)))))
      
      (testing "can unlink from org"
        (is (sid/sid? (st/save-user s (dissoc user :orgs))))
        (is (empty? (-> (st/find-user s (:id user)) :orgs))))

      (testing "can count"
        (is (= 1 (st/count-users s))))

      (testing "can delete"
        (is (true? (st/delete-user s (:id user))))
        (is (= 0 (st/count-users s)))))))

(deftest ^:sql builds
  (with-storage conn s
    (let [repo (h/gen-repo)
          org (-> (h/gen-org)
                   (assoc-in [:repos (:id repo)] repo))
          build (-> (h/gen-build)
                    (assoc :org-id (:id org)
                           :repo-id (:id repo)
                           :script {:script-dir "test-dir"}
                           :start-time (t/now)
                           :end-time (t/now))
                    (mc/update-existing :git dissoc :ssh-keys-dir :ssh-keys))
          build-sid (st/ext-build-sid build)]
      (is (sid/sid? (st/save-org s org)))

      (testing "can save and retrieve"
        (is (sid/sid? (st/save-build s build)))
        (is (= 1 (count (ec/select conn {:select :*
                                         :from :builds}))))
        (is (= build (-> (st/find-build s build-sid)
                         (select-keys (keys build))))))

      (testing "can list"
        (is (= [(:build-id build)]
               (st/list-build-ids s [(:id org) (:id repo)]))))

      (testing "can check for existence"
        (is (true? (st/build-exists? s build-sid))))

      (testing "can list with details, excluding jobs"
        (let [d (st/list-builds s (take 2 build-sid))]
          (is (= 1 (count d)))
          (is (= (update build :script dissoc :jobs)
                 (select-keys (first d) (keys build))))))

      (testing "removes ssh private keys"
        (let [ssh-key {:id (st/new-id)
                       :description "test key"
                       :private-key "secret private key"}
              build (assoc-in build [:git :ssh-keys] [ssh-key])
              _ (st/save-build s build)
              match (st/find-build s (st/ext-build-sid build))]
          (is (= (select-keys ssh-key [:id :description])
                 (-> match :git :ssh-keys first)))))

      (testing "can get next idx"
        (let [repo (-> (h/gen-repo)
                       (assoc :org-id (:id org)))
              repo-sid [(:id org) (:id repo)]]
          (is (some? (st/save-repo s repo)))
          (is (= 1 (st/find-next-build-idx s repo-sid))
              "initial index is one")
          (is (= 2 (st/find-next-build-idx s repo-sid))
              "increases on each invocation")))

      (testing "can list builds since timestamp"
        (let [repo (h/gen-repo)
              org (-> (h/gen-org)
                       (assoc :repos {(:id repo) repo}))
              old-build (-> (h/gen-build)
                            (assoc :org-id (:id org)
                                   :repo-id (:id repo)
                                   :start-time 100)
                            (dissoc :script))
              new-build (-> (h/gen-build)
                            (assoc :org-id (:id org)
                                   :repo-id (:id repo)
                                   :start-time 200)
                            (dissoc :script))]
          (is (sid/sid? (st/save-org s org)))
          (is (sid/sid? (st/save-build s old-build)))
          (is (sid/sid? (st/save-build s new-build)))
          (let [r (st/list-builds-since s (:id org) 150)]
            (is (= [(:build-id new-build)] (map :build-id r)))
            (is (= (:id org) (:org-id (first r))))
            (is (= (:id repo) (:repo-id (first r)))))))

      (testing "can find latest by build index"
        (let [repo (h/gen-repo)
              org (-> (h/gen-org)
                       (assoc :repos {(:id repo) repo}))
              old-build (-> (h/gen-build)
                            (assoc :org-id (:id org)
                                   :repo-id (:id repo)
                                   :start-time 100
                                   :idx 9
                                   :build-id "build-9")
                            (dissoc :script))
              new-build (-> (h/gen-build)
                            (assoc :org-id (:id org)
                                   :repo-id (:id repo)
                                   :start-time 200
                                   :idx 10
                                   :build-id "build-10")
                            (dissoc :script))]
          (is (sid/sid? (st/save-org s org)))
          (is (sid/sid? (st/save-build s old-build)))
          (is (sid/sid? (st/save-build s new-build)))
          (let [r (st/find-latest-build s [(:id org) (:id repo)])]
            (is (= (:build-id new-build) (:build-id r))))))

      (testing "can find all latest for org"
        (let [repos (repeatedly 2 h/gen-repo)
              org (-> (h/gen-org)
                       (assoc :repos (->> repos
                                          (map (fn [r] [(:id r) r]))
                                          (into {}))))
              builds (->> [{:idx 1
                            :build-id "build-1"
                            :repo-id (:id (first repos))}
                           {:idx 2
                            :build-id "build-2"
                            :repo-id (:id (first repos))}
                           {:idx 3
                            :build-id "build-3"
                            :repo-id (:id (second repos))}
                           {:idx 4
                            :build-id "build-4"
                            :repo-id (:id (second repos))}]
                          (map #(assoc % :org-id (:id org))))]
          (is (sid/sid? (st/save-org s org)))
          (doseq [b builds]
            (is (sid/sid? (st/save-build s b))))
          (let [latest (st/find-latest-builds s (:id org))]
            (is (= #{"build-2" "build-4"}
                   (->> latest
                        (map :build-id)
                        (set)))))))

      (testing "can find latest n for org"
        (let [repos (repeatedly 2 h/gen-repo)
              org (-> (h/gen-org)
                       (assoc :repos (->> repos
                                          (map (fn [r] [(:id r) r]))
                                          (into {}))))
              builds (->> [{:idx 1
                            :build-id "build-1"
                            :repo-id (:id (first repos))
                            :start-time 100}
                           {:idx 2
                            :build-id "build-2"
                            :repo-id (:id (first repos))
                            :start-time 200}
                           {:idx 3
                            :build-id "build-3"
                            :repo-id (:id (second repos))
                            :start-time 300}
                           {:idx 4
                            :build-id "build-4"
                            :repo-id (:id (second repos))
                            :start-time 400}]
                          (map #(assoc % :org-id (:id org))))]
          (is (sid/sid? (st/save-org s org)))
          (doseq [b builds]
            (is (sid/sid? (st/save-build s b))))
          (let [latest (st/find-latest-n-builds s (:id org) 2)]
            (is (= #{"build-3" "build-4"}
                   (->> latest
                        (map :build-id)
                        (set))))
            (is (= (:id (second repos))
                   (-> latest first :repo-id)))))))))

(deftest ^:sql jobs
  (with-storage conn s
    (let [org (-> (h/gen-org)
                   (assoc :repos {}))
          repo (-> (h/gen-repo)
                   (assoc :org-id (:id org)))
          build (-> (h/gen-build)
                    (assoc :script {}
                           :org-id (:id org)
                           :repo-id (:id repo)))
          sid [(:id org) (:id repo) (:build-id build)]
          job {:id "test-job"}]
      (is (some? (st/save-org s org)))
      (is (some? (st/save-repo s repo)))
      (is (some? (st/save-build s build)))
      
      (testing "can add to build and retrieve"
        (is (some? (st/save-job s sid job)))
        (is (= {"test-job" job}
               (-> (st/find-build s sid)
                   :script
                   :jobs)))
        (is (= job (st/find-job s (conj sid (:id job))))))

      (testing "can update in build"
        (let [upd (assoc job :status :success)]
          (is (some? (st/save-job s sid upd)))
          (is (= {"test-job" upd}
                 (-> (st/find-build s sid)
                     :script
                     :jobs))))))))

(deftest ^:sql join-requests
  (with-storage conn s
    (let [org (h/gen-org)
          user (h/gen-user)
          _ (st/save-org s org)
          _ (st/save-user s user)
          jr (-> (h/gen-join-request)
                 (assoc :user-id (:id user)
                        :org-id (:id org)
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
        (let [r (st/list-user-join-requests s (:id user))]
          (is (= [(:id jr)] (map :id r))))))))

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

(deftest ^:sql credit-subscriptions
  (with-storage conn s
    (let [org (h/gen-org)
          now (t/now)
          cs (-> (h/gen-credit-subs)
                 (assoc :org-id (:id org)
                        :valid-from (- now 1000)
                        :valid-until (+ now 1000)))
          sid (st/credit-sub-sid (:id org) (:id cs))]
      (is (sid/sid? (st/save-org s org)))

      (testing "can create and retrieve"
        (is (sid/sid? (st/save-credit-subscription s cs)))
        (is (= cs (st/find-credit-subscription s sid))))

      (testing "can list for org"
        (is (= [cs] (st/list-org-credit-subscriptions s (:id org)))))

      (testing "can list active"
        (is (= [cs] (st/list-active-credit-subscriptions s now)))
        (is (empty? (st/list-active-credit-subscriptions s (+ now 2000)))))

      (testing "can update"
        (is (sid/sid? (st/save-credit-subscription s (assoc cs :amount 200M))))
        (is (= 200M (-> (st/find-credit-subscription s sid)
                        :amount))))

      (testing "can delete"
        (is (true? (st/delete-credit-subscription s sid)))
        (is (nil? (st/find-credit-subscription s sid)))))))

(deftest ^:sql org-credits
  (with-storage conn s
    (let [repo (h/gen-repo)
          org (-> (h/gen-org)
                   (assoc :repos {(:id repo) repo}))
          cred (-> (h/gen-org-credit)
                   (assoc :org-id (:id org)
                          :amount 100M)
                   (dissoc :user-id :subscription-id))]
      (is (sid/sid? (st/save-org s org)))
      
      (testing "can create and retrieve"
        (is (sid/sid? (st/save-org-credit s cred)))
        (is (= cred (st/find-org-credit s (:id cred)))))
      
      (testing "for org"
        (let [other-org (h/gen-org)
              _ (st/save-org s other-org)
              sids (->> [(assoc cred :from-time 1000)
                         (-> (h/gen-org-credit)
                             (assoc :org-id (:id org)
                                    :from-time 2000
                                    :amount 200M)
                             (dissoc :user-id :subscription-id))
                         (-> (h/gen-org-credit)
                             (assoc :org-id (:id other-org)
                                    :from-time 1000)
                             (dissoc :user-id :subscription-id))]
                        (mapv (partial st/save-org-credit s)))]
          (is (some? sids))

          (testing "can list since"
            (is (= [(-> sids first last)]
                   (->> (st/list-org-credits-since s (:id org) 1100)
                        (map :id)))))

          (testing "can list"
            (is (= (map last (take 2 sids))
                   (->> (st/list-org-credits s (:id org))
                        (map :id)))))))

      (testing "calculates available credits using credit consumptions"
        (let [build (-> (h/gen-build)
                        (assoc :org-id (:id org)
                               :repo-id (:id repo)
                               :credits 25M))
              ccons {:org-id (:id org)
                     :repo-id (:id repo)
                     :build-id (:build-id build)
                     :credit-id (:id cred)
                     :amount 30M}]
          (is (sid/sid? (st/save-build s build)))
          (is (sid/sid? (st/save-credit-consumption s ccons)))
          (is (= 270M (st/calc-available-credits s (:id org))))))

      (testing "lists available credits"
        (is (= [(:id cred)]
               (->> (st/list-available-credits s (:id org))
                    (map :id))))))))

(deftest ^:sql credit-consumptions
  (with-storage conn s
    (let [now (t/now)
          repo (h/gen-repo)
          org (-> (h/gen-org)
                   (assoc :repos {(:id repo) repo}))
          build (-> (h/gen-build)
                    (assoc :repo-id (:id repo)
                           :org-id (:id org)))
          credit (-> (h/gen-org-credit)
                     (assoc :org-id (:id org))
                     (dissoc :user-id :subscription-id))
          cc (-> (h/gen-credit-cons)
                 (assoc :build-id (:build-id build)
                        :repo-id (:id repo)
                        :org-id (:id org)
                        :credit-id (:id credit)
                        :consumed-at now))]
      (is (sid/sid? (st/save-org s org)))
      (is (sid/sid? (st/save-org-credit s credit)))
      (is (sid/sid? (st/save-build s build)))

      (testing "can create and retrieve"
        (is (sid/sid? (st/save-credit-consumption s cc)))
        (is (= cc (st/find-credit-consumption s (st/credit-cons-sid (:id org) (:id cc))))))

      (testing "can list for org"
        (is (= [cc] (st/list-org-credit-consumptions s (:id org)))))

      (testing "can list for org since timestamp"
        (is (= [cc] (st/list-org-credit-consumptions-since s (:id org) (- now 100))))
        (is (empty? (st/list-org-credit-consumptions-since s (:id org) (+ now 100)))))

      (testing "can update"
        (is (sid/sid? (st/save-credit-consumption s (assoc cc :amount 200M))))
        (is (= 200M (-> (st/find-credit-consumption s (st/credit-cons-sid (:id org) (:id cc)))
                        :amount))))

      (testing "can create for user"
        (let [user (h/gen-user)
              cred (-> (h/gen-org-credit)
                       (assoc :type :user
                              :user-id (:id user)
                              :reason "testing"
                              :org-id (:id org)
                              :amount 1000M)
                       (dissoc :subscription-id))]
          (is (sid/sid? (st/save-user s user)))
          (is (sid/sid? (st/save-org-credit s cred)))
          (is (= cred (st/find-org-credit s (:id cred))))))

      (testing "can create for subscription"
        (let [cs (-> (h/gen-credit-subs)
                     (assoc :org-id (:id org)
                            :amount 1000M))
              cred (-> (h/gen-org-credit)
                       (assoc :type :subscription
                              :subscription-id (:id cs)
                              :org-id (:id org)
                              :amount 1000M)
                       (dissoc :user-id))]
          (is (sid/sid? (st/save-credit-subscription s cs)))
          (is (sid/sid? (st/save-org-credit s cred)))
          (is (= cred (st/find-org-credit s (:id cred)))))))))

(deftest ^:sql bb-webhooks
  (with-storage conn st
    (let [repo (h/gen-repo)
          org (-> (h/gen-org)
                   (assoc :repos {(:id repo) repo}))
          wh (-> (h/gen-webhook)
                 (assoc :org-id (:id org)
                        :repo-id (:id repo)))
          bb-wh (-> (h/gen-bb-webhook)
                    (assoc :webhook-id (:id wh)))]
      (testing "can save and find"
        (is (sid/sid? (st/save-org st org)))
        (is (sid/sid? (st/save-webhook st wh)))
        (is (sid/sid? (st/save-bb-webhook st bb-wh)))
        (is (= bb-wh (st/find-bb-webhook st (:id bb-wh)))))

      (testing "can find by webhook id"
        (is (= bb-wh (st/find-bb-webhook-for-webhook st (:id wh)))))

      (testing "can search by filter"
        (is (= [(merge bb-wh (select-keys wh [:org-id :repo-id]))]
               (st/search-bb-webhooks st {:org-id (:id org)})))
        (is (= [(:id bb-wh)]
               (->> (st/search-bb-webhooks st {:webhook-id (:id wh)})
                    (map :id))))
        (is (empty? (st/search-bb-webhooks st {:org-id "nonexisting"})))))))

(deftest ^:sql crypto
  (with-storage conn st
    (let [org (h/gen-org)
          crypto (-> (h/gen-crypto)
                     (assoc :org-id (:id org)))]
      (testing "can save and find by org id"
        (is (sid/sid? (st/save-org st org)))
        (is (sid/sid? (st/save-crypto st crypto)))
        (let [m (st/find-crypto st (:id org))]
          (is (= (:org-id crypto) (:org-id m)))
          (is (java.util.Arrays/equals (:iv crypto) (:iv m)))))

      (testing "can update"
        (let [upd (assoc crypto :iv (gen/generate (sg/fixed-byte-array 16)))]
          (is (sid/sid? (st/save-crypto st upd)))
          (is (java.util.Arrays/equals (:iv upd) (:iv (st/find-crypto st (:id org))))))))))

(deftest ^:sql sysadmin
  (with-storage conn st
    (let [user (h/gen-user)
          sysadmin {:user-id (:id user)
                    :password "test-password"}]
      (testing "can save and find by user id"
        (is (sid/sid? (st/save-user st user)))
        (is (sid/sid? (st/save-sysadmin st sysadmin)))
        (let [m (st/find-sysadmin st (:id user))]
          (is (= sysadmin m)))))))

(deftest ^:sql invoices
  (with-storage conn st
    (let [org (h/gen-org)
          inv (-> (h/gen-invoice)
                  (assoc :org-id (:id org)
                         :kind :invoice
                         :currency "EUR"
                         :net-amount 100M
                         :vat-perc 21M
                         :details
                         [{:net-amount 20M
                           :vat-perc 21M
                           :description "first"}
                          {:net-amount 80M
                           :vat-perc 21M
                           :description "second"}]))]
      (testing "can save"
        (is (sid/sid? (st/save-org st org)))
        (is (sid/sid? (st/save-invoice st inv))))

      (testing "can find by id"
        (is (= inv (st/find-invoice st [(:id org) (:id inv)]))))

      (testing "can find by org id"
        (let [m (st/list-invoices-for-org st (:id org))]
          (is (= [inv] m))))
      
      (testing "can update"
        (let [upd (assoc inv :currency "USD")]
          (is (some? (st/save-invoice st upd)))
          (is (= upd (st/find-invoice st [(:id org) (:id inv)]))))))))

(deftest ^:sql runner-details
  (with-storage conn st
    (let [repo (h/gen-repo)
          org (-> (h/gen-org)
                  (assoc :repos {(:id repo) repo}))
          build (-> (h/gen-build)
                    (assoc :org-id (:id org)
                           :repo-id (:id repo)
                           :status :success))
          sid (st/ext-build-sid build)]
      (is (sid/sid? (st/save-org st org)))
      (is (sid/sid? (st/save-build st build)))
      
      (testing "can save and find by build sid"
        (let [details {:runner :test-runner
                       :details {:test :details}}]
          (is (sid/sid? (st/save-runner-details st sid details)))
          (is (= details (st/find-runner-details st sid)))))

      (testing "can update"
        (let [upd {:details {:updated :details}}]
          (is (sid/sid? (st/save-runner-details st sid upd)))
          (is (= upd (-> (st/find-runner-details st sid)
                         (select-keys [:details])))))))))

(deftest ^:sql queued-tasks
  (with-storage conn st
    (let [task (h/gen-queued-task)]
      (testing "can save and list"
        (is (sid/sid? (st/save-queued-task st task)))
        (is (= [task] (st/list-queued-tasks st))))

      (testing "can delete"
        (is (true? (st/delete-queued-task st (:id task))))
        (is (empty? (st/list-queued-tasks st)))))))

(deftest ^:sql job-events
  (with-storage conn st
    (let [org (h/gen-org)
          repo (-> (h/gen-repo)
                   (assoc :org-id (:id org)))
          build (-> (h/gen-build)
                    (assoc :repo-id (:id repo)
                           :org-id (:id org)))
          job (-> (h/gen-job)
                  (assoc :org-id (:id org)
                         :repo-id (:id repo)
                         :build-id (:build-id build)))
          evt (-> (h/gen-job-evt)
                  (assoc :org-id (:id org)
                         :repo-id (:id repo)
                         :build-id (:build-id build)
                         :job-id (:id job)
                         :details {:status :running}))
          job->sid (juxt :org-id :repo-id :build-id :id)]

      (is (some? (st/save-org st org)))
      (is (some? (st/save-repo st repo)))
      (is (some? (st/save-build st build)))
      (is (some? (st/save-job st (b/sid build) job)))
      
      (testing "can save and list for job"
        (is (sid/sid? (st/save-job-event st evt)))
        (is (= [evt] (st/list-job-events st (job->sid job))))))))

(deftest pool-component
  (testing "creates sql connection pool using settings"
    (let [s (sut/pool-component {:type :sql
                                 :url (:jdbcUrl eh/h2-config)})]
      (is (some? s)))))

(deftest make-storage
  (testing "creates storage component"
    (let [conf {:type :sql
                :url (:jdbcUrl eh/h2-config)}
          s (-> (st/make-storage {:storage conf})
                (assoc :pool (sut/pool-component conf)))]
      (is (fn? (:get-conn s))))))
