(ns monkey.ci.web.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web.api :as sut]
            [monkey.ci.helpers :as h]
            [org.httpkit.server :as http]))

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
  (testing "converts pipelines to list sorted by index"
    (let [{st :storage :as rt} (h/test-rt)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:pipelines {0 {:name "pipeline 1"}
                                                1 {:name "pipeline 2"}}})
          r (-> rt
                (h/->req)
                (h/with-path-params md)
                (sut/get-latest-build))]
      (is (= 200 (:status r)))
      (is (= 2 (-> r :body :pipelines count)))
      (is (= [{:index 0
               :name "pipeline 1"}
              {:index 1
               :name "pipeline 2"}]
             (-> r :body :pipelines)))))

  (testing "converts pipeline jobs to list sorted by index"
    (let [{st :storage :as rt} (h/test-rt)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:pipelines {0
                                                {:name "pipeline 1"
                                                 :jobs
                                                 {0 {:name "job 1"}
                                                  1 {:name "job 2"}}}}})
          r (-> rt
                (h/->req)
                (h/with-path-params md)
                (sut/get-latest-build))]
      (is (= 200 (:status r)))
      (is (= 2 (-> r :body :pipelines first :jobs count)))
      (is (= [{:index 0
               :name "job 1"}
              {:index 1
               :name "job 2"}]
             (-> r :body :pipelines first :jobs)))))

  (testing "returns latest build"
    (let [{st :storage :as rt} (h/test-rt)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          create-build (fn [ts]
                         (let [id (str "build-" ts)
                               sid (st/->sid (concat (vals md) [id]))]
                           (st/create-build-metadata st sid md)
                           (st/save-build-results st sid
                                                  {:id id
                                                   :timestamp ts
                                                   :jobs []})))
          _ (create-build 200)
          _ (create-build 100)
          r (-> rt
                (h/->req)
                (h/with-path-params md)
                (sut/get-latest-build))]
      (is (= "build-200" (-> r :body :id))))))

(deftest get-build
  (testing "retrieves build by id, with pipelines as vector"
    (let [{st :storage :as rt} (h/test-rt)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:pipelines
                                    {0
                                     {:name "pipeline 1"
                                      :jobs
                                      {0 {:name "job 1"}
                                       1 {:name "job 2"}}}}})
          r (-> rt
                (h/->req)
                (h/with-path-params (assoc md :build-id id))
                (sut/get-build))]
      (is (= 200 (:status r)))
      (is (= [{:index 0
               :name "pipeline 1"
               :jobs
               [{:index 0 :name "job 1"}
                {:index 1 :name "job 2"}]}]
             (get-in r [:body :pipelines])))))

  (testing "converts jobs to list"
    (let [{st :storage :as rt} (h/test-rt)
          id (st/new-id)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          sid (st/->sid (concat (vals md) [id]))
          _ (st/create-build-metadata st sid md)
          _ (st/save-build-results st sid
                                   {:jobs
                                    {"job-1" {:id "job-1"}
                                     "job-2" {:id "job-2"}}})
          r (-> rt
                (h/->req)
                (h/with-path-params (assoc md :build-id id))
                (sut/get-build))]
      (is (= 200 (:status r)))
      (is (= [{:id "job-1"}
              {:id "job-2"}]
             (get-in r [:body :jobs]))))))

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
          ch (->FakeChannel sent)
          evt (ec/make-sync-events)]
      (with-redefs [http/as-channel (fn [_ {:keys [on-open]}]
                                      on-open)]
        (let [f (sut/event-stream (h/->req {:events {:receiver evt}}))]
          (is (some? (f ch)))
          (is (some? (ec/post-events evt {:type :script/start})))
          (is (not-empty @sent))
          (is (string? (-> @sent
                           first
                           :msg
                           :body))))))))

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
