(ns monkey.ci.test.web.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [events :as e]
             [storage :as st]]
            [monkey.ci.web.api :as sut]
            [monkey.ci.test.helpers :as h]
            [org.httpkit.server :as http]))

(defn- ->req [ctx]
  {:reitit.core/match
   {:data
    {:monkey.ci.web.common/context ctx}}})

(defn- with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn- with-path-params [r p]
  (update-in r [:parameters :path] merge p))

(defn- with-body [r v]
  (assoc-in r [:parameters :body] v))

(defn- test-ctx []
  {:storage (st/make-memory-storage)})

(deftest get-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as ctx} (test-ctx)
          req (-> ctx
                  (->req)
                  (with-path-param :customer-id (:id cust)))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= cust (:body (sut/get-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (test-ctx)
                   (->req)
                   (with-path-param :customer-id "nonexisting")
                   (sut/get-customer)
                   :status))))

  (testing "converts repo map into list"
    (let [cust {:id (st/new-id)
                :name "Customer with projects"}
          repo {:id "test-repo"
                :name "Test repository"
                :customer-id (:id cust)}
          {st :storage :as ctx} (test-ctx)]
      (is (st/sid? (st/save-customer st cust)))
      (is (st/sid? (st/save-repo st repo)))
      (let [r (-> ctx
                  (->req)
                  (with-path-param :customer-id (:id cust))
                  (sut/get-customer)
                  :body)
            repos (-> r :repos)]
        (is (some? repos))
        (is (not (map? repos)))
        (is (= (select-keys repo [:id :name])
               (first repos)))))))

(deftest create-customer
  (testing "returns created customer with id"
    (let [r (-> (test-ctx)
                (->req)
                (with-body {:name "new customer"})
                (sut/create-customer)
                :body)]
      (is (= "new customer" (:name r)))
      (is (string? (:id r))))))

(deftest update-customer
  (testing "returns customer in body"
    (let [cust {:id "test-customer"
                :name "Test customer"}
          {st :storage :as ctx} (test-ctx)
          req (-> ctx
                  (->req)
                  (with-path-param :customer-id (:id cust))
                  (with-body {:name "updated"}))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= {:id (:id cust)
              :name "updated"}
             (:body (sut/update-customer req))))))

  (testing "404 not found when no match"
    (is (= 404 (-> (test-ctx)
                   (->req)
                   (with-path-param :customer-id "nonexisting")
                   (sut/update-customer)
                   :status)))))

(deftest create-repo
  (testing "generates id from repo name"
    (let [repo {:name "Test repo"
                :customer-id (st/new-id)}
          {st :storage :as ctx} (test-ctx)
          r (-> ctx
                (->req)
                (with-body repo)
                (sut/create-repo)
                :body)]
      (is (= "test-repo" (:id r)))))

  (testing "on id collision, appends index"
    (let [repo {:name "Test repo"
                :customer-id (st/new-id)}
          {st :storage :as ctx} (test-ctx)
          _ (st/save-repo st (-> repo
                                 (select-keys [:customer-id])
                                 (assoc :id "test-repo"
                                        :name "Existing repo")))
          r (-> ctx
                (->req)
                (with-body repo)
                (sut/create-repo)
                :body)]
      (is (= "test-repo-2" (:id r))))))

(deftest get-customer-params
  (testing "empty vector if no params"
    (is (= [] (-> (test-ctx)
                  (->req)
                  (with-path-params {:customer-id (st/new-id)})
                  (sut/get-customer-params)
                  :body))))

  (testing "returns stored parameters"
    (let [{st :storage :as ctx} (test-ctx)
          cust-id (st/new-id)
          params [{:parameters [{:name "test-param"
                                 :value "test-value"}]
                   :label-filters [[{:label "test-label"
                                     :value "test-value"}]]}]
          _ (st/save-params st cust-id params)]
      (is (= params
             (-> ctx
                 (->req)
                 (with-path-params {:customer-id cust-id})
                 (sut/get-customer-params)
                 :body))))))

(deftest get-repo-params
  (let [{st :storage :as ctx} (test-ctx)
        [cust-id repo-id] (repeatedly st/new-id)
        _ (st/save-customer st {:id cust-id
                                :repos {repo-id
                                        {:id repo-id
                                         :name "test repo"
                                         :labels [{:name "test-label"
                                                   :value "test-value"}]}}})]

    (testing "empty list if no params"
      (is (= [] (-> ctx
                    (->req)
                    (with-path-params {:customer-id cust-id
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
               (-> ctx
                   (->req)
                   (with-path-params {:customer-id cust-id
                                      :repo-id repo-id})
                   (sut/get-repo-params)
                   :body)))))

    (testing "returns `404 not found` if repo does not exist"
      (is (= 404
             (-> ctx
                 (->req)
                 (with-path-params {:customer-id cust-id
                                    :repo-id "other-repo"})
                 (sut/get-repo-params)
                 :status))))))

(deftest apply-label-filters
  (testing "matches params with empty filter"
    (is (true? (sut/apply-label-filters
                {"test-label" "test-value"}
                {:label-filters []}))))

  (testing "matches params with single matching filter"
    (is (true? (sut/apply-label-filters
                {"test-label" "test-value"}
                {:label-filters [[{:label "test-label"
                                   :value "test-value"}]]}))))

  (testing "does not match when no matching filter"
    (is (not (sut/apply-label-filters
              {"test-label" "test-value"}
              {:label-filters [[{:label "test-label"
                                 :value "other-value"}]]}))))

  (testing "matches conjunction filter"
    (is (true? (sut/apply-label-filters
                {"first-label" "first-value"
                 "second-label" "second-value"}
                {:label-filters [[{:label "first-label"
                                   :value "first-value"}
                                  {:label "second-label"
                                   :value "second-value"}]]}))))

  (testing "does not conjunction filter when only one value matches"
    (is (not (sut/apply-label-filters
              {"first-label" "first-value"
               "second-label" "other-value"}
              {:label-filters [[{:label "first-label"
                                 :value "first-value"}
                                {:label "second-label"
                                 :value "second-value"}]]}))))
  
  (testing "matches disjunction filter"
    (is (true? (sut/apply-label-filters
                {"first-label" "first-value"
                 "second-label" "second-value"}
                {:label-filters [[{:label "first-label"
                                   :value "first-value"}]
                                 [{:label "other-label"
                                   :value "other-value"}]]})))))

(deftest create-webhook
  (testing "assigns secret key"
    (let [{st :storage :as ctx} (test-ctx)
          r (-> ctx
                (->req)
                (with-body {:customer-id "test-customer"})
                (sut/create-webhook))]
      (is (= 201 (:status r)))
      (is (string? (get-in r [:body :secret-key]))))))

(deftest get-webhook
  (testing "does not return the secret key"
    (let [{st :storage :as ctx} (test-ctx)
          wh {:id (st/new-id)
              :secret-key "very secret key"}
          _ (st/save-webhook-details st wh)
          r (-> ctx
                (->req)
                (with-path-param :webhook-id (:id wh))
                (sut/get-webhook))]
      (is (= 200 (:status r)))
      (is (nil? (get-in r [:body :secret-key]))))))

(deftest get-latest-build
  (testing "converts pipelines to list sorted by index"
    (let [{st :storage :as ctx} (test-ctx)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:pipelines {0 {:name "pipeline 1"}
                                                1 {:name "pipeline 2"}}})
          r (-> ctx
                (->req)
                (with-path-params md)
                (sut/get-latest-build))]
      (is (= 200 (:status r)))
      (is (= 2 (-> r :body :pipelines count)))
      (is (= [{:index 0
               :name "pipeline 1"}
              {:index 1
               :name "pipeline 2"}]
             (-> r :body :pipelines)))))

  (testing "converts pipeline steps to list sorted by index"
    (let [{st :storage :as ctx} (test-ctx)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:pipelines {0
                                                {:name "pipeline 1"
                                                 :steps
                                                 {0 {:name "step 1"}
                                                  1 {:name "step 2"}}}}})
          r (-> ctx
                (->req)
                (with-path-params md)
                (sut/get-latest-build))]
      (is (= 200 (:status r)))
      (is (= 2 (-> r :body :pipelines first :steps count)))
      (is (= [{:index 0
               :name "step 1"}
              {:index 1
               :name "step 2"}]
             (-> r :body :pipelines first :steps))))))

(defrecord FakeChannel [messages]
  http/Channel
  (send! [this msg close?]
    (swap! messages conj {:msg msg :closed? close?})))

(defn- make-fake-channel []
  (->FakeChannel (atom [])))

(deftest event-stream
  (testing "returns stream reply"
    (with-redefs [http/as-channel (constantly (make-fake-channel))]
      (is (some? (sut/event-stream {})))))

  (testing "sends received events on open"
    (let [sent (atom [])
          ch (->FakeChannel sent)]
      (h/with-bus
        (fn [bus]
          (with-redefs [http/as-channel (fn [_ {:keys [on-open]}]
                                          on-open)]
            (let [f (sut/event-stream (->req {:event-bus bus}))]
              (is (some? (f ch)))
              (is (true? (e/post-event bus {:type :script/start})))
              (is (true? (h/wait-until #(pos? (count @sent)) 500)))
              (is (string? (-> @sent
                               first
                               :msg
                               :body))))))))))

(deftest trigger-build-event
  (testing "adds ref to build from branch query param"
    (is (= "refs/heads/test-branch"
           (-> {:query
                {:branch "test-branch"}}
               (sut/trigger-build-event "test-build" {})
               :build
               :git
               :ref))))

  (testing "adds ref to build from tag query param"
    (is (= "refs/tags/test-tag"
           (-> {:query
                {:tag "test-tag"}}
               (sut/trigger-build-event "test-build" {})
               :build
               :git
               :ref)))))
