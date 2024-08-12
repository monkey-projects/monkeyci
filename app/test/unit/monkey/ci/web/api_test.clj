(ns monkey.ci.web.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [manifold.stream :as ms]
            [monkey.ci
             [storage :as st]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web.api :as sut]
            [monkey.ci.helpers :as h]))

(defn- parse-edn [s]
  (with-open [r (java.io.StringReader. s)]
    (u/parse-edn r)))

(defn- parse-event [evt]
  (let [prefix "data: "]
    (if (.startsWith evt prefix)
      (parse-edn (subs evt (count prefix)))
      (throw (ex-info "Invalid event payload" {:event evt})))))

(deftest get-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as rt} (h/test-rt)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :customer-id (:id cust)))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= cust (:body (sut/get-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (h/test-rt)
                   (h/->req)
                   (h/with-path-param :customer-id "nonexisting")
                   (sut/get-customer)
                   :status))))

  (testing "converts repo map into list"
    (let [cust {:id (st/new-id)
                :name "Customer with projects"}
          repo {:id "test-repo"
                :name "Test repository"
                :customer-id (:id cust)}
          {st :storage :as rt} (h/test-rt)]
      (is (st/sid? (st/save-customer st cust)))
      (is (st/sid? (st/save-repo st repo)))
      (let [r (-> rt
                  (h/->req)
                  (h/with-path-param :customer-id (:id cust))
                  (sut/get-customer)
                  :body)
            repos (-> r :repos)]
        (is (some? repos))
        (is (not (map? repos)))
        (is (= (select-keys repo [:id :name])
               (first repos)))))))

(deftest create-customer
  (testing "returns created customer with id"
    (let [r (-> (h/test-rt)
                (h/->req)
                (h/with-body {:name "new customer"})
                (sut/create-customer)
                :body)]
      (is (= "new customer" (:name r)))
      (is (string? (:id r)))))

  (testing "links current user to customer"
    (let [user (-> (h/gen-user)
                   (dissoc :customers))
          {st :storage :as rt} (h/test-rt)
          r (-> rt
                (h/->req)
                (h/with-body {:name "another customer"})
                (h/with-identity user)
                (sut/create-customer)
                :body)]
      (is (some? r))
      (is (= [(:id r)] (-> (st/find-user st (:id user))
                           :customers)))
      (is (= [r] (st/list-user-customers st (:id user)))))))

(deftest update-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as rt} (h/test-rt)
          req (-> rt
                  (h/->req)
                  (h/with-path-param :customer-id (:id cust))
                  (h/with-body {:name "updated"}))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= {:id (:id cust)
              :name "updated"}
             (:body (sut/update-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (h/test-rt)
                   (h/->req)
                   (h/with-path-param :customer-id "nonexisting")
                   (sut/update-customer)
                   :status)))))

(deftest search-customers
  (let [{st :storage :as rt} (h/test-rt)
        cust {:id (st/new-id)
              :name "Test customer"}
        sid (st/save-customer st cust)]
    (testing "retrieves customer by id"
      (is (= [cust]
             (-> rt
                 (h/->req)
                 (h/with-query-param :id (:id cust))
                 (sut/search-customers)
                 :body))))
    
    (testing "searches customers by name"
      (is (= [cust]
             (-> rt
                 (h/->req)
                 (h/with-query-param :name "Test")
                 (sut/search-customers)
                 :body))))
    
    (testing "fails if no query params given"
      (is (= 400
             (-> rt
                 (h/->req)
                 (sut/search-customers)
                 :status))))))

(deftest create-repo
  (testing "generates id from repo name"
    (let [repo {:name "Test repo"
                :customer-id (st/new-id)}
          {st :storage :as rt} (h/test-rt)
          r (-> rt
                (h/->req)
                (h/with-body repo)
                (sut/create-repo)
                :body)]
      (is (= "test-repo" (:id r)))))

  (testing "on id collision, appends index"
    (let [repo {:name "Test repo"
                :customer-id (st/new-id)}
          {st :storage :as rt} (h/test-rt)
          _ (st/save-repo st (-> repo
                                 (select-keys [:customer-id])
                                 (assoc :id "test-repo"
                                        :name "Existing repo")))
          r (-> rt
                (h/->req)
                (h/with-body repo)
                (sut/create-repo)
                :body)]
      (is (= "test-repo-2" (:id r))))))

(deftest create-webhook
  (testing "assigns secret key"
    (let [{st :storage :as rt} (h/test-rt)
          r (-> rt
                (h/->req)
                (h/with-body {:customer-id "test-customer"})
                (sut/create-webhook))]
      (is (= 201 (:status r)))
      (is (string? (get-in r [:body :secret-key]))))))

(deftest get-webhook
  (testing "does not return the secret key"
    (let [{st :storage :as rt} (h/test-rt)
          wh {:id (st/new-id)
              :secret-key "very secret key"}
          _ (st/save-webhook st wh)
          r (-> rt
                (h/->req)
                (h/with-path-param :webhook-id (:id wh))
                (sut/get-webhook))]
      (is (= 200 (:status r)))
      (is (nil? (get-in r [:body :secret-key]))))))

(deftest get-latest-build
  (testing "returns latest build"
    (let [{st :storage :as rt} (h/test-rt)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          create-build (fn [ts]
                         (let [id (str "build-" ts)
                               sid (st/->sid (concat (vals md) [id]))]
                           (st/save-build st (merge md {:build-id id
                                                        :timestamp ts
                                                        :jobs []}))))
          _ (create-build 200)
          _ (create-build 100)
          r (-> rt
                (h/->req)
                (h/with-path-params md)
                (sut/get-latest-build))]
      (is (= "build-200" (-> r :body :build-id))))))

(deftest get-build
  (testing "retrieves build by id"
    (let [{st :storage :as rt} (h/test-rt)
          id (st/new-id)
          build {:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id id
                 :status :success}
          sid (st/ext-build-sid build)
          _ (st/save-build st build)
          r (-> rt
                (h/->req)
                (h/with-path-params build)
                (sut/get-build))]
      (is (= 200 (:status r)))
      (is (= build (:body r))))))

(deftest build->out
  (testing "regular build"
    (testing "converts jobs to sequential"
      (is (sequential? (-> (sut/build->out {:script {:jobs {"test-job" {:id "test-job"}}}})
                           :script
                           :jobs))))

    (testing "assigns ids to jobs when none in job"
      (is (= "test-job"
             (-> (sut/build->out {:script {:jobs {"test-job" {:status :success}}}})
                 :script
                 :jobs
                 first
                 :id))))

    (testing "contains other properties"
      (let [build {:customer-id "test-cust"
                   :status :success}]
        (is (= build (sut/build->out build))))))

  (testing "legacy build"
    (testing "converts jobs"
      (is (= [{:id "test-job"}]
             (-> {:legacy? true
                  :jobs {"test-job" {:id "test-job"}}}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "converts pipelines with jobs"
      (is (= [{:id "test-job"
               :labels {"pipeline" "test-pipeline"}}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :jobs {0 {:name "test-job"}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "converts pipelines with steps"
      (is (= [{:id "test-job"
               :labels {"pipeline" "test-pipeline"}}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :steps {0 {:name "test-job"}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "assigns id to job"
      (is (= [{:id "test-pipeline-0"
               :labels {"pipeline" "test-pipeline"}
               :status :success}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :jobs {0 {:status :success}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "renames `timestamp` to `start-time`"
      (let [ts (u/now)]
        (is (= ts (-> {:legacy? true
                       :timestamp ts}
                      (sut/build->out)
                      :start-time)))))

    (testing "renames `result` to `status`"
      (is (= :success
             (-> {:legacy? true
                  :result :success}
                 (sut/build->out)
                 :status))))))

(defn- make-events []
  (ec/make-events {:events {:type :sync}}))

(deftest event-stream
  (testing "returns stream reply"
    (is (ms/source? (-> (sut/event-stream (h/->req {:events (make-events)}))
                        :body))))

  (testing "sends ping on open"
    (let [sent (atom [])
          evt (make-events)
          f (sut/event-stream (h/->req {:events evt}))
          _ (ms/consume (partial swap! sent conj) (:body f))]
      (is (some? (ec/post-events evt {:type :script/start})))
      (is (not= :timeout (h/wait-until #(not-empty @sent) 1000)))
      (is (= :ping (-> @sent
                       first
                       (parse-event)
                       :type)))))

  (testing "only sends events for customer specified in path"
    (let [sent (atom [])
          cid "test-customer"
          evt (make-events)
          f (-> (h/->req {:events evt})
                (assoc-in [:parameters :path :customer-id] cid)
                (sut/event-stream))
          _ (ms/consume (fn [evt]
                          (swap! sent #(conj % (parse-event evt))))
                        (:body f))]
      (is (some? (ec/post-events evt {:type :script/start
                                      :sid ["other-customer" "test-repo" "test-build"]})))
      (is (some? (ec/post-events evt {:type :script/start
                                      :sid [cid "test-repo" "test-build"]})))
      (is (not= :timeout (h/wait-until #(some (comp (partial = "test-customer")
                                                    first
                                                    :sid)
                                              @sent)
                                       1000)))))

  (testing "sets `x-accel-buffering` header for nginx proxying"
    (let [evt (make-events)
          r (-> (h/->req {:events evt})
                (sut/event-stream))]
      (is (= "no" (get-in r [:headers "x-accel-buffering"]))))))

(deftest make-build-ctx
  (testing "adds ref to build from branch query param"
    (is (= "refs/heads/test-branch"
           (-> (h/test-rt)
               (h/->req)
               (assoc-in [:parameters :query :branch] "test-branch")
               (sut/make-build-ctx)
               :git
               :ref))))

  (testing "adds ref to build from tag query param"
    (is (= "refs/tags/test-tag"
           (-> (h/test-rt)
               (h/->req)
               (assoc-in [:parameters :query :tag] "test-tag")
               (sut/make-build-ctx)
               :git
               :ref))))

  (testing "adds configured ssh keys"
    (let [{st :storage :as rt} (h/test-rt)
          [cid rid] (repeatedly st/new-id)
          ssh-key {:private-key "private-key"
                   :public-key "public-key"}]
      (is (st/sid? (st/save-ssh-keys st cid [ssh-key])))
      (is (= [ssh-key]
             (-> (h/->req rt)
                 (assoc-in [:parameters :path] {:customer-id cid
                                                :repo-id rid})
                 (sut/make-build-ctx)
                 :git
                 :ssh-keys)))))

  (testing "adds main branch from repo"
    (let [{st :storage :as rt} (h/test-rt)
          [cid rid] (repeatedly st/new-id)]
      (is (st/sid? (st/save-repo st {:customer-id cid
                                     :id rid
                                     :main-branch "test-branch"})))
      (is (= "test-branch"
             (-> (h/->req rt)
                 (assoc-in [:parameters :path] {:customer-id cid
                                                :repo-id rid})
                 (sut/make-build-ctx)
                 :git
                 :main-branch)))))

  (testing "sets cleanup flag when not in dev mode"
    (is (true? (:cleanup? (sut/make-build-ctx (-> (h/test-rt)
                                                  (assoc-in [:config :dev-mode] false)
                                                  (h/->req)))))))

  (testing "does not set cleanup flag when in dev mode"
    (is (false? (:cleanup? (sut/make-build-ctx (-> (h/test-rt)
                                                   (assoc-in [:config :dev-mode] true)
                                                   (h/->req))))))))

(deftest update-user
  (testing "updates user in storage"
    (let [{st :storage :as rt} (h/test-rt)]
      (is (st/sid? (st/save-user st {:type "github"
                                     :type-id 543
                                     :name "test user"})))
      (is (= 200 (-> (h/->req rt)
                     (assoc :parameters {:path
                                         {:user-type "github"
                                          :type-id 543}
                                         :body
                                         {:name "updated user"}})
                     (sut/update-user)
                     :status)))
      (is (= "updated user" (-> (st/find-user-by-type st [:github 543])
                                :name))))))

(deftest create-email-registration
  (testing "does not create same email twice"
    (let [{st :storage :as rt} (h/test-rt)
          email "duplicate@monkeyci.com"
          req (-> rt
                  (h/->req)
                  (h/with-body {:email email}))]
      (is (some? (st/save-email-registration st {:email email})))
      (is (= 200 (:status (sut/create-email-registration req))))
      (is (= 1 (count (st/list-email-registrations st)))))))
