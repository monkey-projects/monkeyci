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
      (is (string? (:id r))))))

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

(deftest get-customer-params
  (testing "empty vector if no params"
    (is (= [] (-> (h/test-rt)
                  (h/->req)
                  (h/with-path-params {:customer-id (st/new-id)})
                  (sut/get-customer-params)
                  :body))))

  (testing "returns stored parameters"
    (let [{st :storage :as rt} (h/test-rt)
          cust-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st cust-id params)]
      (is (= params
             (-> rt
                 (h/->req)
                 (h/with-path-params {:customer-id cust-id})
                 (sut/get-customer-params)
                 :body))))))

(deftest get-repo-params
  (let [{st :storage :as rt} (h/test-rt)
        [cust-id repo-id] (repeatedly st/new-id)
        _ (st/save-customer st {:id cust-id
                                :repos {repo-id
                                        {:id repo-id
                                         :name "test repo"
                                         :labels [{:name "test-label"
                                                   :value "test-value"}]}}})]

    (testing "empty list if no params"
      (is (= [] (-> rt
                    (h/->req)
                    (h/with-path-params {:customer-id cust-id
                                       :repo-id repo-id})
                    (sut/get-repo-params)
                    :body))))

    (testing "returns matching parameters according to label filters"
      (let [params [{:parameters [{:name "test-param"
                                   :value "test-value"}]
                     :label-filters [[{:label "test-label"
                                       :value "test-value"}]]}]
            _ (st/save-params st cust-id params)]

        (is (= [{:name "test-param" :value "test-value"}]
               (-> rt
                   (h/->req)
                   (h/with-path-params {:customer-id cust-id
                                      :repo-id repo-id})
                   (sut/get-repo-params)
                   :body)))))

    (testing "returns `404 not found` if repo does not exist"
      (is (= 404
             (-> rt
                 (h/->req)
                 (h/with-path-params {:customer-id cust-id
                                    :repo-id "other-repo"})
                 (sut/get-repo-params)
                 :status))))))

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
          _ (st/save-webhook-details st wh)
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

(deftest fetch-build-details
  (testing "retrieves regular build"
    (h/with-memory-store st
      (let [build {:build-id "test-build"
                   :customer-id "test-cust"
                   :repo-id "test-repo"}]
        (is (st/sid? (st/save-build st build)))
        (is (= build (sut/fetch-build-details st (st/ext-build-sid build)))))))

  (testing "retrieves legacy build"
    (h/with-memory-store st
      (let [md {:customer-id "test-cust"
                :repo-id "test-repo"
                :build-id "test-build"}
            results {:jobs {"test-job" {:status :success}}}
            sid (st/ext-build-sid md)]
        (is (st/sid? (st/create-build-metadata st md)))
        (is (st/sid? (st/save-build-results st sid results)))
        (let [r (sut/fetch-build-details st (st/ext-build-sid md))]
          (is (some? (:jobs r)))
          (is (= "test-cust" (:customer-id r)))
          (is (true? (:legacy? r))))))))

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

(deftest event-stream
  (testing "returns stream reply"
    (is (ms/source? (-> (sut/event-stream (h/->req {:events {:receiver (ec/make-sync-events)}}))
                        :body))))

  (testing "sends ping on open"
    (let [sent (atom [])
          evt (ec/make-sync-events)
          f (sut/event-stream (h/->req {:events evt}))
          _ (ms/consume (partial swap! sent conj) (:body f))]
      (is (some? (ec/post-events evt {:type :script/start})))
      (is (not= :timeout (h/wait-until #(not-empty @sent) 1000)))
      (is (= :ping (-> @sent
                       first
                       (parse-event)
                       :type)))))

  (testing "sends received events on open"
    (let [sent (atom [])
          evt (ec/make-sync-events)
          f (sut/event-stream (h/->req {:events evt}))
          _ (ms/consume (fn [evt]
                          (swap! sent #(conj % (parse-event evt))))
                        (:body f))]
      (is (some? (ec/post-events evt {:type :script/start})))
      (is (not= :timeout (h/wait-until #(some (comp (partial = :script/start) :type) @sent)
                                       1000)))))

  (testing "only sends events for customer specified in path"
    (let [sent (atom [])
          cid "test-customer"
          evt (ec/make-sync-events)
          f (-> (h/->req {:events evt})
                (assoc-in [:parameters :path :customer-id] cid)
                (sut/event-stream))
          _ (ms/consume (fn [evt]
                          (swap! sent #(conj % (parse-event evt))))
                        (:body f))]
      (is (some? (ec/post-events evt {:type :script/start
                                      :sid ["other-customer" "test-repo" "test-build"]})))
      (is (some? (ec/post-events evt {:type :script/start
                                      :sid ["test-customer" "test-repo" "test-build"]})))
      (is (not= :timeout (h/wait-until #(some (comp (partial = "test-customer")
                                                    first
                                                    :sid)
                                              @sent)
                                       1000))))))

(deftest make-build-ctx
  (testing "adds ref to build from branch query param"
    (is (= "refs/heads/test-branch"
           (-> (h/test-rt)
               (h/->req)
               (assoc-in [:parameters :query :branch] "test-branch")
               (sut/make-build-ctx "test-build")
               :git
               :ref))))

  (testing "adds ref to build from tag query param"
    (is (= "refs/tags/test-tag"
           (-> (h/test-rt)
               (h/->req)
               (assoc-in [:parameters :query :tag] "test-tag")
               (sut/make-build-ctx "test-build")
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
                 (sut/make-build-ctx "test-build")
                 :git
                 :ssh-keys))))))

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
      (is (= "updated user" (-> (st/find-user st [:github 543])
                                :name))))))
