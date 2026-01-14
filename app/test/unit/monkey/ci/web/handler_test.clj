(ns monkey.ci.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure
             [string :as cs]
             [test :refer [deftest is testing]]]
            [manifold.deferred :as md]
            [meta-merge.core :as mm]
            [monkey.ci
             [artifacts :as a]
             [build :as b]
             [cuid :as cuid]
             [logging :as l]
             [storage :as st]
             [time :as t]
             [utils :as u]
             [version :as v]]
            [monkey.ci.metrics.core :as m]
            [monkey.ci.test
             [aleph-test :as at]
             [helpers :as h]
             [mailman :as tmm]
             [runtime :as trt]]
            [monkey.ci.web
             [auth :as auth]
             [handler :as sut]]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]))

(deftest make-app
  (testing "creates a fn"
    (is (fn? (sut/make-app {})))))

(defn- test-rt [& [opts]]
  (-> (trt/test-runtime)
      (trt/set-encrypter (fn [x _ _] x))
      (trt/set-decrypter (fn [x _ _] x))
      (merge {:config {:dev-mode true}
              :vault (h/dummy-vault)}
       opts)
      (update :storage #(or % (st/make-memory-storage)))))

(defn- make-test-app
  ([storage]
   (sut/make-app (test-rt {:storage storage})))
  ([]
   (sut/make-app (test-rt))))

(def test-app (make-test-app))

(defn- verify-entity-endpoints [{:keys [path base-entity updated-entity name
                                        creator can-update? can-delete?]
                                 :or {can-update? true can-delete? false}}]
  (let [st (st/make-memory-storage)
        app (make-test-app st)
        path (or path (str "/" name))]
    
    (testing (format "`%s`" path)
      (testing (str "`POST` creates new " name)
        (let [r (-> (h/json-request :post path base-entity)
                    (app))]
          (is (= 201 (:status r)))))

      (testing "`/:id`"
        (testing (format "`GET` retrieves %s info" name)
          (let [id (st/new-id)
                entity (assoc base-entity :id id)
                _ (creator st entity)
                r (-> (mock/request :get (str path "/" id))
                      (mock/header :accept "application/json")
                      (app))]
            (is (= 200 (:status r)))
            (is (= entity (h/reply->json r)))))

        (when can-update?
          (testing (str "`PUT` updates existing " name)
            (let [id (st/new-id)
                  _ (creator st (assoc base-entity :id id))
                  upd (cond-> base-entity
                        updated-entity (merge updated-entity))
                  r (-> (h/json-request :put (str path "/" id) upd)
                        (app))]
              (is (= 200 (:status r))
                  upd))))

        (when can-delete?
          (testing (str "`DELETE` deletes existing " name)
            (let [id (st/new-id)
                  _ (creator st (assoc base-entity :id id))
                  r (-> (mock/request :delete (str path "/" id))
                        (app))]
              (is (= 204 (:status r))))))))))

(deftest http-routes
  (testing "health check at `/health`"
    (is (= 200 (-> (mock/request :get "/health")
                   (test-app)
                   :status))))

  (testing "version at `/version`"
    (let [r (-> (mock/request :get "/version")
                (test-app))]
      (is (= 200 (:status r)))
      (is (= (v/version) (:body r)))))

  (testing "handles `nil` bodies"
    (is (= 200 (-> (mock/request :get "/health")
                   (mock/body nil)
                   (test-app)
                   :status))))  
  (testing "404 when not found"
    (is (= 404 (-> (mock/request :get "/nonexisting")
                   (test-app)
                   :status)))))

(defn- secure-app-req
  "Creates a secure app using the given storage, with a generated token.
   Creates a random user in storage with access to the given org.
   Invokes the request after adding the token as authorization.  Returns
   the http response."
  [req {:keys [org-id update-token user] st :storage
        :or {update-token identity}}]
  (let [kp (auth/generate-keypair)
        rt (test-rt {:storage st
                     :jwk (auth/keypair->rt kp)
                     :config {:dev-mode false}})
        user (or user
                 (when org-id
                   {:type "github"
                    :type-id (int (* (rand) 10000))
                    :orgs [org-id]}))
        token-info {:sub (str (:type user) "/" (:type-id user))
                    :role auth/role-user
                    :exp (+ (u/now) 10000)}
        make-token (fn [ti]
                     (auth/sign-jwt ti (.getPrivate kp)))
        token (some-> token-info
                      (update-token)
                      (make-token))
        app (sut/make-app rt)]
    (when user
      (st/save-user st user))
    (cond-> req
      token (mock/header "authorization" (str "Bearer " token))
      true (app))))

(deftest webhook-routes
  (verify-entity-endpoints {:name "webhook"
                            :base-entity {:org-id "test-cust"
                                          :repo-id "test-repo"}
                            :updated-entity {:repo-id "updated-repo"}
                            :creator st/save-webhook
                            :can-delete? true})

  (testing "when no org permissions"
    (let [[org-id repo-id] (repeatedly st/new-id)
          st (st/make-memory-storage)]
        (is (some? (st/save-org st {:id org-id})))
        
        (testing "cannot create"
          (is (= 403 (-> (h/json-request :post
                                         "/webhook"
                                         {:org-id org-id
                                          :repo-id repo-id})
                         (secure-app-req {:storage st
                                          :org-id (st/new-id)})
                         :status))))

        (testing "cannot delete"
          (let [wh-id (st/new-id)]
            (is (some? (st/save-webhook st {:id wh-id
                                            :org-id org-id
                                            :repo-id repo-id})))
            (is (= 403 (-> (mock/request :delete (str "/webhook/" wh-id))
                           (secure-app-req {:storage st
                                            :org-id (st/new-id)})
                           :status)))))))

  (testing "can create using org display id"
    (let [[org-id repo-id] (repeatedly st/new-id)
          st (st/make-memory-storage)]
      (is (some? (st/save-org st {:id org-id :display-id "test-org"})))
      (let [r (-> (h/json-request :post
                                     "/webhook"
                                     {:org-id "test-org"
                                      :repo-id repo-id})
                     (secure-app-req {:storage st
                                      :org-id org-id}))
            b (h/reply->json r)]
        (is (= 201 (:status r)))
        (is (= [org-id repo-id]
               (-> (st/find-webhook st (:id b))
                   (select-keys [:org-id :repo-id])
                   (vals)))))))
  
  (testing "`GET /health` returns 200"
    (is (= 200 (-> (mock/request :get "/webhook/health")
                   (test-app)
                   :status)))))

(deftest webhook-github-routes
  (testing "`POST /webhook/github/:id`"
    (testing "accepts with valid security header"
      (let [github-secret "github-secret"
            payload (h/to-json {:head-commit {:message "test"}})
            signature (-> (mac/hash payload {:key github-secret
                                             :alg :hmac+sha256})
                          (codecs/bytes->hex))
            hook-id (st/new-id)
            st (st/make-memory-storage)
            app (sut/make-app (test-rt {:storage st
                                        :runner (constantly nil)
                                        :config {:dev-mode false}}))]
        (is (st/sid? (st/save-webhook st {:id hook-id
                                          :secret-key github-secret})))
        (is (= 202 (-> (mock/request :post (str "/webhook/github/" hook-id))
                       (mock/body payload)
                       (mock/header :x-hub-signature-256 (str "sha256=" signature))
                       (mock/header :x-github-event "push")
                       (mock/content-type "application/json")
                       (mock/content-length (count payload))
                       (app)
                       :status)))))

    (testing "returns 401 if invalid security"
      (let [app (sut/make-app (test-rt {:config {:dev-mode false}}))]
        (is (= 401 (-> (mock/request :post "/webhook/github/test-hook")
                       (app)
                       :status)))))

    (testing "disables security check when in dev mode"
      (let [dev-app (sut/make-app {:config {:dev-mode true}
                                   :runner (constantly nil)})]
        (is (= 204 (-> (mock/request :post "/webhook/github/test-hook")
                       (dev-app)
                       :status))))))

  (testing "`/webhook/github`"
    (letfn [(validate-github [path]
              (testing (format "`POST %s`" path)
                (testing "accepts request with valid security"
                  (let [secret (str (random-uuid))
                        payload (h/to-json {:message "test from github"})
                        signature (-> (mac/hash payload {:key secret
                                                         :alg :hmac+sha256})
                                      (codecs/bytes->hex))
                        app (sut/make-app (test-rt {:config
                                                    {:github
                                                     {:webhook-secret secret}}}))]
                    (is (= 204 (-> (mock/request :post (str "/webhook/github" path))
                                   (mock/body payload)
                                   (mock/content-type "application/json")
                                   (mock/header :x-hub-signature-256 (str "sha256=" signature))
                                   (app)
                                   :status)))))

                (testing "401 on invalid security"
                  (let [secret (str (random-uuid))
                        payload (h/to-json {:message "test from github"})
                        app (sut/make-app (test-rt {:config
                                                    {:github
                                                     {:webhook-secret secret}}}))]
                    (is (= 401 (-> (mock/request :post (str "/webhook/github" path))
                                   (mock/body payload)
                                   (mock/content-type "application/json")
                                   (app)
                                   :status)))))

                (testing "no security check in dev mode"
                  (let [secret (str (random-uuid))
                        payload (h/to-json {:message "test from github"})
                        app (sut/make-app (test-rt {}))]
                    (is (= 204 (-> (mock/request :post (str "/webhook/github" path))
                                   (mock/body payload)
                                   (mock/content-type "application/json")
                                   (app)
                                   :status)))))))]
      
      (validate-github "/app")
      (validate-github ""))))

(deftest webhook-bitbucket-routes
  (testing "`POST /webhook/bitbucket/:id`"
    (testing "accepts with valid security header"
      (let [bitbucket-secret "bitbucket-secret"
            payload (h/to-json {:head-commit {:message "test"}})
            signature (-> (mac/hash payload {:key bitbucket-secret
                                             :alg :hmac+sha256})
                          (codecs/bytes->hex))
            hook-id (st/new-id)
            st (st/make-memory-storage)
            app (sut/make-app (test-rt {:storage st
                                        :runner (constantly nil)
                                        :config {:dev-mode false}}))]
        (is (st/sid? (st/save-webhook st {:id hook-id
                                          :secret-key bitbucket-secret})))
        (is (= 200 (-> (mock/request :post (str "/webhook/bitbucket/" hook-id))
                       (mock/body payload)
                       (mock/header :x-hub-signature (str "sha256=" signature))
                       (mock/header :x-bitbucket-event "push")
                       (mock/content-type "application/json")
                       (mock/content-length (count payload))
                       (app)
                       :status)))))

    (testing "returns 401 if invalid security"
      (let [app (sut/make-app (test-rt {:config {:dev-mode false}}))]
        (is (= 401 (-> (mock/request :post "/webhook/bitbucket/test-hook")
                       (app)
                       :status)))))

    (testing "disables security check when in dev mode"
      (let [dev-app (sut/make-app {:config {:dev-mode true}
                                   :runner (constantly nil)})]
        (is (= 200 (-> (mock/request :post "/webhook/bitbucket/test-hook")
                       (dev-app)
                       :status)))))))

(deftype TestLogRetriever [logs]
  l/LogRetriever
  (list-logs [_ sid]
    (keys logs))

  (fetch-log [_ sid p]
    (some->> p
             (get logs)
             (.getBytes)
             (java.io.ByteArrayInputStream.))))

(deftest org-endpoints
  (verify-entity-endpoints {:name "org"
                            :base-entity {:name "test org"}
                            :updated-entity {:name "updated org"}
                            :creator st/save-org
                            :can-delete? true})

  (testing "`GET /org` searches for org"
    (h/with-memory-store st
      (let [app (make-test-app st)]
        (is (= 200 (-> (mock/request :get "/org" {:name "test"})
                       (app)
                       :status))))))

  (testing "`/:id`"
    (testing "`/join-request`"
      (h/with-memory-store st
        (let [app (make-test-app st)
              org (h/gen-org)
              user (h/gen-user)
              jr {:id (st/new-id)
                  :status "pending"
                  :org-id (:id org)
                  :user-id (:id user)}]
          (is (some? (st/save-org st org)))
          (is (some? (st/save-user st user)))
          (is (some? (st/save-join-request st jr)))

          (testing "`POST` returns 400 when user has already joined that org")
          
          (testing "`GET` retrieves join requests for org"
            (is (= [jr]
                   (-> (mock/request :get (str "/org/" (:id org) "/join-request"))
                       (app)
                       (h/reply->json)))))

          (testing "allows filtering by status")
          
          (testing "`POST /:request-id/approve`"
            (testing "approves join request with message"
              (is (= 200
                     (-> (h/json-request
                          :post (str "/org/" (:id org) "/join-request/" (:id jr) "/approve")
                          {:message "Very well"})
                         (app)
                         :status)))
              (let [m (st/find-join-request st (:id jr))]
                (is (= :approved (:status m)))
                (is (= "Very well" (:response-msg m)))))
            
            (testing "returns 404 not found for non existing request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (:id org) "/join-request/" (st/new-id) "/approve")
                          {})
                         (app)
                         :status))))

            (testing "returns 404 not found when org id does not match request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (st/new-id) "/join-request/" (:id jr) "/approve")
                          {})
                         (app)
                         :status)))))

          (testing "`POST /:request-id/reject`"
            (testing "rejects join request with message"
              (is (= 200
                     (-> (h/json-request
                          :post (str "/org/" (:id org) "/join-request/" (:id jr) "/reject")
                          {:message "No way"})
                         (app)
                         :status)))
              (let [m (st/find-join-request st (:id jr))]
                (is (= :rejected (:status m)))
                (is (= "No way" (:response-msg m)))))
            
            (testing "returns 404 not found for non existing request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (:id org) "/join-request/" (st/new-id) "/reject")
                          {})
                         (app)
                         :status))))

            (testing "returns 404 not found when org id does not match request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (st/new-id) "/join-request/" (:id jr) "/reject")
                          {})
                         (app)
                         :status))))))))

    (h/with-memory-store st
      (let [org (h/gen-org)
            app (make-test-app st)]
        (is (some? (st/save-org st org)))
        
        (testing "`/builds`"
          (testing "`/recent` retrieves builds from latest 24h"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id org) "/builds/recent"))
                           (app)
                           :status))))

          (testing "`/latest` retrieves latest builds for each repo"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id org) "/builds/latest"))
                           (app)
                           :status)))))

        (testing "`GET /stats` retrieves org statistics"
          (is (= 200 (-> (mock/request :get (str "/org/" (:id org) "/stats"))
                         (app)
                         :status))))

        (testing "`/credits`"
          (testing "`GET` retrieves org credit details"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id org) "/credits"))
                           (app)
                           :status)))))

        (testing "`/webhook/bitbucket`"
          (let [repo (h/gen-repo)
                org (assoc org :repos {(:id repo) repo})
                wh {:id (cuid/random-cuid)
                    :org-id (:id org)
                    :repo-id (:id repo)
                    :secret "test secret"}
                bb {:webhook-id (:id wh)
                    :workspace "test-ws"
                    :repo-slug "test-repo"
                    :bitbucket-id (str (random-uuid))}
                search (fn [path]
                         (-> (mock/request :get (str (format "/org/%s/webhook/bitbucket" (:id org)) path))
                             (app)
                             :body
                             slurp
                             (h/parse-json)))
                select-props #(select-keys % (keys bb))]
            (is (some? (st/save-org st org)))
            (is (some? (st/save-webhook st wh)))
            (is (some? (st/save-bb-webhook st bb)))
            
            (testing "`GET` lists bitbucket webhooks for org"
              (is (= [bb] (->> (search "")
                               (map select-props)))))

            (testing "contains org and repo id"
              (let [r (-> (search "") first)]
                (is (= (:id org) (:org-id r)))
                (is (= (:id repo) (:repo-id r)))))
            
            (testing "allows filtering by query params"
              (is (= [bb] (->> (search "?workspace=test-ws")
                               (map select-props))))
              (is (empty? (search "?repo-id=nonexisting"))))))

        (testing "`/invoice`"
          (let [base-path (str "/org/" (:id org) "/invoice")]
            (is (some? (st/save-org-invoicing st {:org-id (:id org)
                                                  :ext-id "1"})))
            (let [rt (-> (test-rt)
                         (trt/set-storage st)
                         (trt/set-invoicing-client
                          (fn [req]
                            (condp = (:path req)
                              "/customer"
                              (md/success-deferred
                               {:body [{:id 1
                                        :name (:name org)}]})
                              "/invoice"
                              (md/success-deferred
                               {:body {:id 2
                                       :invoice-nr "INV001"}})))))
                  app (sut/make-app rt)
                  inv (-> (h/gen-invoice)
                          (assoc :org-id (:id org))
                          (dissoc :org-id :id :invoice-nr :ext-id))
                  r (-> (h/json-request :post base-path inv)
                        (app))
                  b (h/reply->json r)]

              (testing "`POST` creates new invoice"
                (is (= 201 (:status r)) b)
                (is (some? (:id b)))
                (is (= 1 (count (st/list-invoices-for-org st (:id org)))))
                (is (some? (st/find-invoice st [(:id org) (:id b)]))))
              
              (testing "`GET` searches invoices"
                (is (= 200 (-> (mock/request :get base-path)
                               (app)
                               :status))))

              (testing "`GET /:id` retrieves by id"
                (is (= 200 (-> (mock/request :get (str base-path "/" (:id b)))
                               (app)
                               :status)))))

            (testing "`/settings`"
              (let [base-path (str base-path "/settings")]
                (testing "`PUT` creates or updates invoicing settings"
                  (is (= 200 (-> (h/json-request :put base-path
                                                 {:vat-nr "1234"
                                                  :currency "EUR"
                                                  :address ["test address"]
                                                  :country "BEL"})
                                 (app)
                                 :status)))
                  (is (= "EUR" (-> (st/find-org-invoicing st (:id org))
                                   :currency)))
                  (is (= 200 (-> (h/json-request :put base-path
                                                 {:vat-nr "1234"
                                                  :currency "USD"
                                                  :address ["test address"]
                                                  :country "USA"})
                                 (app)
                                 :status)))
                  (is (= "USD" (-> (st/find-org-invoicing st (:id org))
                                   :currency))))

                (testing "`GET` retrieves invoicing settings"
                  (let [r (-> (mock/request :get base-path)
                              (app))
                        b (h/reply->json r)]
                    (is (= 200 (:status r)))
                    (is (= (:id org) (:org-id b))))))))))))

  (h/with-memory-store st
    (let [org-id (st/new-id)]
      (is (some? (st/save-org st {:id org-id
                                  :name "test org"})))

      (testing "ok if user has access to org"
        (is (= 200 (-> (mock/request :get (str "/org/" org-id))
                       (secure-app-req {:storage st :org-id org-id})
                       :status))))

      (testing "unauthorized if user does not have access to org"
        (is (= 403 (-> (mock/request :get (str "/org/" (st/new-id)))
                       (secure-app-req {:storage st :org-id org-id})
                       :status))))
      
      (testing "unauthenticated if no user credentials"
        (is (= 401 (-> (mock/request :get (str "/org/" org-id))
                       (secure-app-req {:storage st
                                        :org-id org-id
                                        :update-token (constantly nil)})
                       :status))))

      (testing "unauthenticated if token expired"
        (is (= 401 (-> (mock/request :get (str "/org/" org-id))
                       (secure-app-req {:storage st
                                        :org-id org-id
                                        :update-token #(assoc % :exp (- (u/now) 1000))})
                       :status)))))))

(deftest repository-endpoints
  (let [org-id (st/new-id)
        repo {:name "test repo"
              :org-id org-id
              :url "http://test-repo"
              :labels [{:name "app" :value "test-app"}]}]
    (verify-entity-endpoints {:name "repository"
                              :path (format "/org/%s/repo" org-id)
                              :base-entity repo
                              :updated-entity {:name "updated repo"}
                              :creator st/save-repo
                              :can-delete? true})

    (testing "security"
      (testing "public repo"
        (h/with-memory-store st
          (let [repo (assoc repo :public true :id "test-repo")
                path (str "/org/" org-id "/repo/" (:id repo))]
            (is (some? (st/save-repo st repo)))
            
            (testing "can be viewed"
              (is (= 200
                     (-> (mock/request :get path)
                         (secure-app-req {:storage st :org-id (st/new-id)})
                         :status))))

            (testing "can not be modified"
              (is (= 403
                     (-> (h/json-request :put
                                         path
                                         (assoc repo :name "updated repo"))
                         (secure-app-req {:storage st :org-id (st/new-id)})
                         :status)))))))

      (testing "with org grant"
        (testing "can edit repo"
          (h/with-memory-store st
            (let [[org-id repo-id] (repeatedly st/new-id)
                  repo {:id repo-id
                        :org-id org-id}]
              (is (some? (st/save-org st {:id org-id})))
              (is (some? (st/save-repo st repo)))
              (is (= 200 (-> (h/json-request
                              :put
                              (format "/org/%s/repo/%s" org-id repo-id)
                              (assoc repo :name "test repo" :url "http://test-url"))
                             (secure-app-req {:storage st :org-id org-id})
                             :status))))))))

    (testing "`/org/:id/repo`"
      (testing "`/github`"
        (testing "`/watch` starts watching repo"
          (is (= 200 (-> (h/json-request :post
                                         (str "/org/" org-id "/repo/github/watch")
                                         {:github-id 12324
                                          :org-id org-id
                                          :name "test-repo"
                                          :url "http://test"})
                         (test-app)
                         :status))))

        (testing "`/unwatch` stops watching repo"
          (let [st (st/make-memory-storage)
                app (make-test-app st)
                repo-id (st/new-id)
                _ (st/watch-github-repo st {:org-id org-id
                                            :id repo-id
                                            :github-id 1234})]
            (is (= 200 (-> (mock/request :post
                                         (format "/org/%s/repo/%s/github/unwatch" org-id repo-id))
                           (app)
                           :status))))))

      (testing "`/bitbucket`"
        (at/with-fake-http [(constantly true) {:status 201
                                               :headers {"Content-Type" "application/json"}
                                               :body "{}"}]
          (let [st (st/make-memory-storage)
                app (make-test-app st)]
            (is (some? (st/save-org st {:id org-id})))
            
            (testing "`/watch` starts watching bitbucket repo"
              (is (= 201 (-> (h/json-request :post
                                             (str "/org/" org-id "/repo/bitbucket/watch")
                                             {:org-id org-id
                                              :name "test-repo"
                                              :url "http://test"
                                              :workspace "test-ws"
                                              :repo-slug "test-repo"
                                              :token "test-token"})
                             (app)
                             :status))))

            (testing "`/unwatch` stops watching repo"
              (let [repo-id (st/new-id)
                    wh-id (st/new-id)
                    _ (st/save-org st {:id org-id
                                       :repos {repo-id {:id repo-id}}})
                    _ (st/save-webhook st {:org-id org-id
                                           :repo-id repo-id
                                           :id wh-id
                                           :secret (str (random-uuid))})
                    _ (st/save-bb-webhook st {:webhook-id wh-id
                                              :bitbucket-id (str (random-uuid))})]
                (is (= 200
                       (-> (h/json-request :post
                                           (format "/org/%s/repo/%s/bitbucket/unwatch" org-id repo-id)
                                           {:token "test-token"})
                           (app)
                           :status)))))))))

    (testing "`GET /webhooks`"
      (let [st (st/make-memory-storage)
            app (make-test-app st)
            {:keys [repo-id] :as repo} (-> (h/gen-repo)
                                           (assoc :org-id org-id))]
        (is (some? (st/save-repo st repo)))
        (let [r (-> (mock/request :get (format "/org/%s/repo/%s/webhooks" org-id repo-id))
                    (app))]

          (testing "returns status 200"
            (is (= 200 (:status r))))
          
          (testing "lists webhooks for repo"
            (is (some? (:body r)))))

        (testing "can not be accessed for public repos"
          (let [[org-id repo-id] (repeatedly st/new-id)]
            (is (some? (st/save-org st {:id org-id
                                        :repos {repo-id {:id repo-id
                                                         :public true}}})))
            (is (= 403 (-> (mock/request :get (format "/org/%s/repo/%s/webhooks" org-id repo-id))
                           (secure-app-req {:storage st :org-id (st/new-id)})
                           :status)))))))))

(deftest user-endpoints
  (testing "/user"
    (let [user {:type "github"
                :type-id 456
                :email "testuser@monkeyci.com"}
          user->sid (juxt (comp keyword :type) :type-id)
          st (st/make-memory-storage)          
          app (make-test-app st)]
      
      (testing "`POST` creates new user"
        (let [r (-> (h/json-request :post "/user" user)
                    (app))]
          (is (= 201 (:status r)))
          (is (= user (-> (st/find-user-by-type st (user->sid user))
                          (select-keys (keys user)))))))

      (testing "`/:user-id`"
        (testing "`DELETE` deletes user"
          (let [u (h/gen-user)]
            (is (some? (st/save-user st u)))
            (is (= u (st/find-user st (:id u))))
            (is (= 204 (-> (mock/request :delete (str "/user/" (:id u)))
                           (app)
                           :status)))
            (is (nil? (st/find-user st (:id u))))))

        (let [user (st/find-user-by-type st (user->sid user))
              user-id (:id user)]
          (testing "`GET /orgs` retrieves orgs for user"
            (let [org {:id (st/new-id)
                       :name "test org"}]
              (is (some? (st/save-org st org)))
              (is (some? (st/save-user st (assoc user :orgs [(:id org)]))))
              (is (= [org] (-> (mock/request :get (str "/user/" user-id "/orgs"))
                               (app)
                               (h/reply->json))))))

          (testing "`GET /settings` retrieves user settings"
            (let [s {:user-id user-id
                     :receive-mailings true}]
              (is (some? (st/save-user-settings st s)))
              (let [r (-> (mock/request :get (str "/user/" user-id "/settings"))
                          (app))]
                (is (= 200 (:status r)))
                (is (= s (h/reply->json r))))))))

      (testing "`GET /:type/:id` retrieves existing user"
        (let [r (-> (mock/request :get (str "/user/github/" (:type-id user)))
                    (app))]
          (is (= 200 (:status r)))
          (is (= (:type-id user) (some-> r (h/reply->json) :type-id)))))

      (testing "`PUT /:type/:id` updates existing user"
        (let [r (-> (h/json-request :put (str "/user/github/" (:type-id user))
                                    (assoc user :email "updated@monkeyci.com"))
                    (app))]
          (is (= 200 (:status r)))
          (is (= "updated@monkeyci.com" (some-> r (h/reply->json) :email)))))

      (let [user (st/find-user-by-type st (user->sid user))
            user-id (:id user)]
        (testing "/join-request"
          (let [org {:id (st/new-id)
                     :name "joining org"}
                base-path (str "/user/" user-id "/join-request")]

            (is (some? (st/save-org st org)))
            
            (testing "`POST` create new join request"
              (let [r (-> (h/json-request :post base-path 
                                          {:org-id (:id org)})
                          (app))]
                (is (= 201 (:status r)))
                (let [created (h/reply->json r)]
                  (is (some? (:id created)))
                  (is (= user-id (:user-id created)))
                  (is (= "pending" (:status created)) "marks created request as pending"))))

            (testing "`GET` lists join requests for user"
              (let [r (-> (mock/request :get base-path)
                          (app))]
                (is (= 200 (:status r)))
                (is (not-empty (h/reply->json r)))))

            (testing "`DELETE /:id` deletes join request by id"
              (let [req (-> (st/list-user-join-requests st user-id)
                            first)
                    r (-> (mock/request :delete (str base-path "/" (:id req)))
                          (app))]
                (is (= 204 (:status r)))
                (is (empty? (st/list-user-join-requests st user-id)))))))

        (testing "security"
          (let [secure-ctx {:storage st
                            :org-id (st/new-id)
                            :user user}]
            (testing "does not allow creating new user"
              (is (= 403 (-> (h/json-request :post "/user"
                                             user)
                             (secure-app-req secure-ctx)
                             :status))))

            (testing "allows retrieving users"
              (is (= 200 (-> (mock/request
                              :get (format "/user/%s/%d" (:type user) (:type-id user)))
                             (secure-app-req secure-ctx)
                             :status))))

            (testing "allows retrieving user orgs"
              (is (= 200 (-> (mock/request
                              :get (format "/user/%s/orgs" user-id))
                             (secure-app-req secure-ctx)
                             :status))))

            (testing "does not allow updating users"
              (is (= 403 (-> (h/json-request
                              :put (format "/user/%s/%d" (:type user) (:type-id user))
                              user)
                             (secure-app-req secure-ctx)
                             :status))))

            (testing "allows listing own join requests"
              (is (= 200 (-> (mock/request :get (format "/user/%s/join-request" user-id))
                             (secure-app-req secure-ctx)
                             :status))))

            (testing "does not allow listing other users' join requests"
              (is (= 403 (-> (mock/request :get (format "/user/%s/join-request" (st/new-id)))
                             (secure-app-req secure-ctx)
                             :status))))))))))

(defn- verify-label-filter-like-endpoints [path desc entity prep-match]
  (let [st (st/make-memory-storage)
        app (make-test-app st)
        get-entity
        (fn [path]
          (some-> (mock/request :get path)
                  (app)
                  (h/reply->json)))
        save-entity
        (fn [path params]
          (-> (h/json-request :put path entity)
              (app)))]

    (testing "/org/:org-id"
      
      (testing path
        (let [org-id (st/new-id)
              full-path (format "/org/%s%s" org-id path)]
          
          (testing (str "empty when no " desc)
            (is (empty? (get-entity full-path))))

          (let [r (save-entity full-path entity)]
            (testing (str "can write " desc)
              (is (= 200 (:status r))))
            
            (testing "returns ids on newly created entities"
              (is (every? (comp some? :id) (h/reply->json r)))))

          (testing (str "can partially update using `PATCH`"))
          
          (testing (str "can read " desc)
            (is (get-entity full-path)
                entity))))

      (testing (str "/repo/:repo-id" path)
        (let [[org-id repo-id] (repeatedly st/new-id)
              full-path (format "/org/%s/repo/%s%s" org-id repo-id path)
              _ (st/save-org st {:id org-id
                                 :repos {repo-id {:name "test repo"}}})]
          
          (testing (str "empty when no " desc)
            (is (empty? (get-entity full-path))))
          
          (testing (str "can not write " desc)
            (is (= 405 (:status (save-entity full-path entity)))))
          
          (testing (str "can read " desc)
            (is (get-entity full-path)
                (prep-match entity))))))))

(deftest parameter-endpoints
  (verify-label-filter-like-endpoints
   "/param"
   "params"
   [{:parameters
     [{:name "test-param"
       :value "test value"}]
     :description "test params"
     :label-filters []}]
   :parameters)

  (let [org-id (st/new-id)
        param {:parameters [{:name "test-param"
                             :value "test value"}]
               :description "original desc"
               :org-id org-id
               :label-filters []}]
    (verify-entity-endpoints
     {:name "org param"
      :path (format "/org/%s/param" org-id)
      :base-entity param
      :updated-entity {:description "updated description"}
      :creator (fn [s p]
                 (st/save-param s (assoc p :org-id org-id)))
      :can-delete? true}))

  (h/with-memory-store st
    (testing "can not be accessed for public repos"
      (let [[org-id repo-id] (repeatedly st/new-id)]
        (is (some? (st/save-org st {:id org-id
                                    :repos {repo-id {:id repo-id
                                                     :public true}}})))
        (is (= 403 (-> (mock/request :get (format "/org/%s/repo/%s/param" org-id repo-id))
                       (secure-app-req {:storage st :org-id (st/new-id)})
                       :status)))))))

(deftest ssh-keys-endpoints
  (verify-label-filter-like-endpoints
   "/ssh-keys"
   "ssh keys"
   [{:private-key "private-test-key"
     :public-key "public-test-key"
     :description "test ssh key"
     :label-filters []}]
   :private-key)

  ;; In case we would want to add endpoints for single ssh keys
  #_(let [org-id (st/new-id)
          ssh-key {:private-key "test-private-key"
                   :public-key "test-public-key"
                   :description "original desc"
                   :org-id org-id
                   :label-filters []}]
      (verify-entity-endpoints
       {:name "org ssh key"
        :path (format "/org/%s/ssh-keys" org-id)
        :base-entity ssh-key
        :updated-entity {:description "updated description"}
        :creator (fn [s p]
                   (st/save-ssh-key s (assoc p :org-id org-id)))
        :can-delete? true}))

  (h/with-memory-store st
    (testing "can not be accessed for public repos"
      (let [[org-id repo-id] (repeatedly st/new-id)]
        (is (some? (st/save-org st {:id org-id
                                    :repos {repo-id {:id repo-id
                                                     :public true}}})))
        (is (= 403 (-> (mock/request :get (format "/org/%s/repo/%s/ssh-keys" org-id repo-id))
                       (secure-app-req {:storage st :org-id (st/new-id)})
                       :status)))))

    (testing "`PUT` with empty body deletes all"
      (let [org (h/gen-org)
            sk (-> (h/gen-ssh-key)
                   (assoc :org-id (:id org)))]
        (is (some? (st/save-org st org)))
        (is (some? (st/save-ssh-keys st (:id org) [sk])))
        (is (= 200 (-> (h/json-request :put (format "/org/%s/ssh-keys" (:id org)) [])
                       (secure-app-req {:storage st :org-id (:id org)})
                       :status)))
        (is (empty? (st/find-ssh-keys st (:id org))))))))

(defn- generate-build-sid []
  (->> (repeatedly st/new-id)
       (take 3)
       (st/->sid)))

(defn- repo-path [sid]
  (str (->> sid
            (drop-last)
            (interleave ["/org" "repo"])
            (cs/join "/"))
       "/builds"))

(defn- build-path [sid]
  (str (repo-path sid) "/" (last sid)))

(defn- with-repo [f & [rt]]
  (let [{st :storage :as trt} (-> (trt/test-runtime)
                                  (assoc :config {:dev-mode true})
                                  (mm/meta-merge rt))
        app (sut/make-app trt)
        [_ repo-id _ :as sid] (generate-build-sid)
        build (-> (zipmap [:org-id :repo-id :build-id] sid)
                  (assoc :status :success
                         :message "test msg"
                         :git {:message "test meta"}))
        path (repo-path sid)]
    (is (st/sid? (st/save-org st {:id (first sid)
                                  :repos {repo-id {:id repo-id
                                                   :name "test repo"}}})))
    (is (st/sid? (st/save-build st build)))
    (is (st/sid? (st/save-org-credit st {:org-id (first sid)
                                         :amount 1000})))
    (f (assoc trt
              :sid sid
              :path path
              :app app))))

(deftest build-endpoints
  (testing "`GET` lists repo builds"
    (with-repo
      (fn [{:keys [path app] [_ _ build-id] :sid}]
        (let [l (-> (mock/request :get path)
                    (app))
              b (h/reply->json l)]
          (is (= 200 (:status l)))
          (is (= 1 (count b)))
          (is (some? (first b)))
          (is (= build-id (:build-id (first b))) "should contain build id")))))
  
  (testing "`POST /trigger`"
    (testing "posts `build/triggered` event"
      (with-repo
        (fn [{:keys [app path] :as ctx}]
          (is (= 202 (-> (h/json-request :post (str path "/trigger") {})
                         (app)
                         :status)))
          (is (= [:build/triggered]
                 (->> ctx
                      (trt/get-mailman)
                      (tmm/get-posted)
                      (map :type)))))))

    (testing "allows query params"
      (with-repo
        (fn [{:keys [app path]}]
          (is (= 202 (-> (h/json-request :post (str path "/trigger?branch=main") {})
                         (app)
                         :status))))))

    (testing "allows body"
      (with-repo
        (fn [{:keys [app path]}]
          (is (= 202 (-> (h/json-request :post (str path "/trigger")
                                         {:branch "main"
                                          :params {"test-key" "test-value"}})
                         (app)
                         :status)))))))
  
  (testing "`GET /latest`"
    (with-repo
      (fn [{:keys [path app] [_ _ build-id :as sid] :sid st :storage}]
        (testing "retrieves latest build for repo"
          (let [l (-> (mock/request :get (str path "/latest"))
                      (app))
                b (h/reply->json l)]
            (is (= 200 (:status l)))
            (is (map? b))
            (is (= build-id (:build-id b)) "should contain build id")
            (is (= "test meta" (get-in b [:git :message])) "should contain git data")))

        (testing "204 when there are no builds"
          (let [sid (generate-build-sid)
                path (repo-path sid)
                l (-> (mock/request :get (str path "/latest"))
                      (app))]
            (is (empty? (st/list-build-ids st (drop-last sid))))
            (is (= 204 (:status l)))
            (is (nil? (:body l)))))

        (testing "404 when repo does not exist"))))

  (testing "`/:build-id`"
    (with-repo
      (fn [{:keys [app] [_ _ build-id :as sid] :sid}]
        (testing "`GET`"
          (testing "retrieves build with given id"
            (let [l (-> (mock/request :get (build-path sid))
                        (app))
                  b (some-> l :body slurp h/parse-json)]
              (is (not-empty l))
              (is (= 200 (:status l)))
              (is (= build-id (:build-id b)))))

          (testing "retrieves build by cuid when no build-id given")

          (testing "404 when build does not exist"
            (let [sid (generate-build-sid)
                  l (-> (mock/request :get (build-path sid))
                        (app))]
              (is (= 404 (:status l)))
              (is (nil? (:body l))))))

        (testing "`POST /retry` re-triggers build"
          (is (= 202 (-> (mock/request :post (str (build-path sid) "/retry"))
                         (app)
                         :status))))

        (testing "`POST /cancel` cancels build"
          (is (= 202 (-> (mock/request :post (str (build-path sid) "/cancel"))
                         (app)
                         :status))))

        (testing "/logs"
          (testing "`GET` retrieves list of available logs for build"
            (let [app (sut/make-app (test-rt {:logging {:retriever (->TestLogRetriever {})}}))
                  l (->> (str (build-path sid) "/logs")
                         (mock/request :get)
                         (app))]
              (is (= 200 (:status l)))))

          (testing "`GET /download`"
            (testing "downloads log file by query param"
              (let [app (sut/make-app (test-rt
                                       {:logging
                                        {:retriever
                                         (->TestLogRetriever {"out.txt" "test log file"})}}))
                    l (->> (str (build-path sid) "/logs/download?path=out.txt")
                           (mock/request :get)
                           (app))]
                (is (= 200 (:status l)))))

            (testing "404 when log file not found"
              (let [app (sut/make-app (test-rt
                                       {:logging
                                        {:retriever
                                         (->TestLogRetriever {})}}))
                    l (->> (str (build-path sid) "/logs/download?path=out.txt")
                           (mock/request :get)
                           (app))]
                (is (= 404 (:status l))))))))))

  (testing "can list if access granted to org"
    (h/with-memory-store st
      (let [[org-id repo-id :as sid] (generate-build-sid)
            org (-> (h/gen-org)
                    (assoc :id org-id :repos {}))
            repo (assoc (h/gen-repo) :id repo-id :org-id org-id)]
        (is (some? (st/save-org st org)))
        (is (some? (st/save-repo st repo)))
        (is (= 200 (-> (mock/request :get (repo-path sid))
                       (secure-app-req {:storage st :org-id org-id})
                       :status)))))))

(deftest job-endpoints
  (h/with-memory-store st
    (let [repo (h/gen-repo)
          org (-> (h/gen-org)
                  (assoc :repos {(:id repo) repo}))
          build (-> (h/gen-build)
                    (assoc :org-id (:id org)
                           :repo-id (:id repo)))
          job {:type :container
               :id "test-job"}]
      (is (some? (st/save-org st org)))
      (is (some? (st/save-build st build)))
      (is (some? (st/save-job st (b/sid build) job)))
      
      (testing "`/:job-id`"
        (testing "`GET`"
          (testing "retrieves job details"
            (let [r (-> (mock/request :get (str (build-path (b/sid build)) "/job/" (:id job)))
                        (secure-app-req {:storage st :org-id (:id org)}))]
              (is (= 200 (:status r)))
              (is (= {:id "test-job"
                      :type "container"}
                     (h/reply->json r)))))

          (testing "`404` when job does not exist"
            (is (= 404 (-> (mock/request :get (str (build-path (b/sid build)) "/job/nonexisting"))
                           (secure-app-req {:storage st :org-id (:id org)})
                           :status)))))

        (testing "`POST /unblock` unblocks job"
          (is (some? (st/save-job st (b/sid build) (assoc job :blocked true))))
          (let [r (-> (mock/request :post (str (build-path (b/sid build)) "/job/" (:id job) "/unblock"))
                      (secure-app-req {:storage st :org-id (:id org)}))]
            (is (= 202 (:status r)))))))))

(deftest event-stream
  (testing "`GET /org/:org-id/events` exists"
    (is (not= 404
              (-> (mock/request :get "/org/test-cust/events")
                  (test-app)
                  :status)))))

(deftest github-endpoints
  (testing "`/github`"
    (testing "`POST /login` requests token from github and fetches user info"
      (at/with-fake-http [{:url "https://github.com/login/oauth/access_token"
                           :request-method :post}
                          (fn [req]
                            (if (= {:client_id "test-client-id"
                                    :client_secret "test-secret"
                                    :code "1234"}
                                   (:query-params req))
                              {:status 200
                               :body (h/to-raw-json {:access_token "test-token"})
                               :headers {"Content-Type" "application/json"}}
                              {:status 400
                               :body (str "invalid query params:" (:query-params req))
                               :headers ["Content-Type" "text/plain"]}))
                          {:url "https://api.github.com/user"
                           :request-method :get}
                          (fn [req]
                            (let [auth (get-in req [:headers "Authorization"])]
                              (if (= "Bearer test-token" auth)
                                {:status 200
                                 :body (h/to-raw-json {:id 4567
                                                       :name "test-user"
                                                       :other-key "other-value"})
                                 :headers {"Content-Type" "application/json"}}
                                {:status 400
                                 :body (str "invalid auth header: " auth)
                                 :headers {"Content-Type" "text/plain"}})))]
        (let [app (-> (test-rt {:config {:github {:client-id "test-client-id"
                                                  :client-secret "test-secret"}}
                                :jwk (auth/keypair->rt (auth/generate-keypair))})
                      (sut/make-app))
              r (-> (mock/request :post "/github/login?code=1234")
                    (app))]
          (is (= 200 (:status r)) (:body r))
          (is (= "test-token"
                 (some-> (:body r)
                         (slurp)
                         (h/parse-json)
                         :github-token))))))

    (testing "`GET /config` returns client id"
      (let [app (-> (test-rt {:config {:github {:client-id "test-client-id"}}})
                    (sut/make-app))
            r (-> (mock/request :get "/github/config")
                  (app))]
        (is (= 200 (:status r)))
        (is (= "test-client-id" (some-> r :body slurp h/parse-json :client-id)))))

    (testing "`POST /refresh` refreshes access token"
      (at/with-fake-http ["https://github.com/login/oauth/access_token"
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (h/to-json {:access-token "new-token"
                                             :refresh-token "new-refresh"})}
                          "https://api.github.com/user"
                          {:status 200
                           :headers {"Content-Type" "application/json"}}]
        (let [app (-> (test-rt {:config {:github {:client-id "test-client-id"
                                                  :client-secret "test-secret"}}})
                      (sut/make-app))
              r (-> (h/json-request :post "/github/refresh"
                                    {:refresh-token "old-token"})
                    (app))]
          (is (= 200 (:status r)))
          (is (= "new-token"
                 (-> r
                     (h/reply->json)
                     :github-token))))))))

(defn- matches-basic-auth? [req user pass]
  (= [user pass] (:basic-auth req)))

(deftest bitbucket-endpoints
  (testing "`/bitbucket`"
    (testing "`POST /login` requests token from bitbucket and fetches user info"
      (at/with-fake-http [{:url "https://bitbucket.org/site/oauth2/access_token"
                           :request-method :post}
                          (fn [req]
                            (cond
                              (not= {:grant_type "authorization_code"
                                     :code "1234"}
                                    (:form-params req))
                              {:status 400 :body (str "Invalid form params: " (:form-params req))}
                              (not (matches-basic-auth? req "test-client-id" "test-secret"))
                              {:status 400 :body (str "Invalid auth code: " (:basic-auth req))}
                              :else
                              {:status 200
                               :body (h/to-raw-json {:access_token "test-token"})
                               :headers {"Content-Type" "application/json"}}))
                          {:url "https://api.bitbucket.org/2.0/user"
                           :request-method :get}
                          (fn [req]
                            (let [auth (get-in req [:headers "Authorization"])]
                              (if (= "Bearer test-token" auth)
                                {:status 200
                                 :body (h/to-raw-json {:name "test-user"
                                                       :other-key "other-value"})
                                 :headers {"Content-Type" "application/json"}}
                                {:status 400 :body (str "invalid auth header: " auth)})))]
        
        (let [app (-> (test-rt {:config {:bitbucket {:client-id "test-client-id"
                                                     :client-secret "test-secret"}}
                                :jwk (auth/keypair->rt (auth/generate-keypair))})
                      (sut/make-app))
              r (-> (mock/request :post "/bitbucket/login?code=1234")
                    (app))]
          (is (= 200 (:status r)))
          (is (= "test-token"
                 (some-> (:body r)
                         (slurp)
                         (h/parse-json)
                         :bitbucket-token))))))

    (testing "`POST /refresh` refreshes token from bitbucket and fetches user info"
      (at/with-fake-http [{:url "https://bitbucket.org/site/oauth2/access_token"
                           :request-method :post}
                          (fn [req]
                            (cond
                              (not= {:grant_type "refresh_token"
                                     :refresh_token "test-token"}
                                    (:form-params req))
                              {:status 400
                               :body (str "Invalid form params: " (:form-params req))
                               :headers {"Content-Type" "text/plain"}}
                              (not (matches-basic-auth? req "test-client-id" "test-secret"))
                              {:status 400
                               :body (str "Invalid auth code: " (:basic-auth req))
                               :headers {"Content-Type" "text/plain"}}
                              :else
                              {:status 200
                               :body (h/to-raw-json {:access_token "test-token"})
                               :headers {"Content-Type" "application/json"}}))
                          {:url "https://api.bitbucket.org/2.0/user"
                           :request-method :get}
                          (fn [req]
                            (let [auth (get-in req [:headers "Authorization"])]
                              (if (= "Bearer test-token" auth)
                                {:status 200
                                 :body (h/to-raw-json {:name "test-user"
                                                       :other-key "other-value"})
                                 :headers {"Content-Type" "application/json"}}
                                {:status 400 :body (str "invalid auth header: " auth)})))]
        
        (let [app (-> (test-rt {:config {:bitbucket {:client-id "test-client-id"
                                                     :client-secret "test-secret"}}
                                :jwk (auth/keypair->rt (auth/generate-keypair))})
                      (sut/make-app))
              r (-> (h/json-request :post "/bitbucket/refresh"
                                    {:refresh-token "test-token"})
                    (app))]
          (is (= 200 (:status r)))
          (is (= "test-token"
                 (some-> (:body r)
                         (slurp)
                         (h/parse-json)
                         :bitbucket-token))))))

    (testing "`GET /config` returns client id"
      (let [app (-> (test-rt {:config {:bitbucket {:client-id "test-client-id"}}})
                    (sut/make-app))
            r (-> (mock/request :get "/bitbucket/config")
                  (app))]
        (is (= 200 (:status r)))
        (is (= "test-client-id" (some-> r :body slurp h/parse-json :client-id)))))))

(deftest routing-middleware
  (testing "converts json bodies to kebab-case"
    (let [app (ring/ring-handler
               (sut/make-router
                {}
                ["/test" {:post (fn [{:keys [body-params] :as req}]
                                  {:status 200
                                   :body (:test-key body-params)})}]))]
      (is (= "test value" (-> (mock/request :post "/test")
                              (mock/body "{\"test_key\":\"test value\"}")
                              (mock/header :content-type "application/json")
                              (app)
                              :body)))))

  (testing "returns edn if accepted"
    (let [body {:key "value"}
          routes ["/" {:get (constantly {:status 200
                                         :body body})}]
          router (sut/make-router {} routes)
          app (ring/ring-handler router)]
      (is (= (pr-str body) (-> (mock/request :get "/")
                               (mock/header :accept "application/edn")
                               (app)
                               :body
                               slurp)))))

  (testing "converts json keys to camelCase"
    (let [body {:test-key "value"}
          routes ["/" {:get (constantly {:status 200
                                         :body body})}]
          router (sut/make-router {} routes)
          app (ring/ring-handler router)]
      (is (= "{\"testKey\":\"value\"}"
             (-> (mock/request :get "/")
                 (mock/header :accept "application/json")
                 (app)
                 :body
                 slurp))))))

(deftest auth-endpoints
  (testing "`GET /auth/jwks`"
    (testing "retrieves JWK structure according to context"
      (let [kp (auth/generate-keypair)
            rt {:jwk (auth/keypair->rt kp)}
            app (sut/make-app rt)
            r (-> (mock/request :get "/auth/jwks")
                  (app))
            k (some-> r
                      :body
                      (slurp)
                      (h/parse-json)
                      :keys
                      (first))]
        (is (= 200 (:status r)))
        (is (string? (:n k)))
        (is (nil? (:d k)) "should not contain private exponent")))

    (testing "404 when no jwk configured"
      (let [app (sut/make-app {})]
        (is (= 404 (-> (mock/request :get "/auth/jwks")
                       (app)
                       :status)))))))

(deftest metrics
  (testing "`GET /metrics`"
    (testing "provides metrics scrape"
      (let [rt {:metrics (m/make-registry)}
            app (sut/make-app rt)
            r (-> (mock/request :get "/metrics")
                  (app))]
        (is (= 200 (:status r)))
        (is (string? (:body r)))))

    (testing "no content when no metrics configured"
      (is (= 204 (-> (mock/request :get "/metrics")
                     (test-app)
                     :status))))))

(deftest artifacts-endpoints
  (let [[org-id repo-id build-id :as sid] (repeatedly 3 st/new-id)
        base-path (format "/org/%s/repo/%s/builds/%s" org-id repo-id build-id)
        art-id "test-artifact"
        artifacts (atom {(a/build-sid->artifact-path sid "test-artifact") "test.txt"})
        art-store (h/fake-blob-store artifacts)
        st (st/make-memory-storage)
        app (-> (test-rt {:storage st})
                (assoc :artifacts art-store)
                (sut/make-app))
        build (-> (zipmap st/build-sid-keys sid)
                  (assoc :script
                         {:jobs {"test-job" {:status :success
                                             :save-artifacts [{:id art-id
                                                               :path "/test/path"}]}}}))
        _ (st/save-build st build)]
    
    (testing "`GET /artifact`"
      (testing "no content when no artifacts"
        (is (= 200 (-> (mock/request :get (str base-path "/artifact"))
                       (app)
                       :status)))))

    (testing "`GET /:id` retrieves artifact details"
      (is (= 200 (-> (mock/request :get (str base-path (str "/artifact/" art-id)))
                     (app)
                     :status))))

    (testing "`GET /:id/download` retrieves artifact contents"
      (is (= 200 (-> (mock/request :get (format "%s/artifact/%s/download" base-path art-id))
                     (app)
                     :status))))

    (testing "`DELETE /:id` deletes artifact")))

(deftest email-registration-endpoints
  (verify-entity-endpoints {:name "email-registration"
                            :base-entity {:email "test@monkeyci.com"}
                            :creator st/save-email-registration
                            :can-update? false
                            :can-delete? true})

  (testing "POST `/email-registration/unregister`"
    (testing "returns 200 if matching user"
      (h/with-memory-store st
        (let [app (make-test-app st)
              email "existing@monkeyci.com"]
          (is (some? (st/save-email-registration st {:email email})))
          (is (= 200
                 (-> (mock/request :post (str "/email-registration/unregister?email=" email))
                     (app)
                     :status))))))))

(deftest admin-routes
  (testing "`/admin`"
    (testing "`/credits`"
      (testing "`/:org-id`"
        (let [org (h/gen-org)
              make-path (fn [& [path]]
                          (cond-> (str "/admin/credits/" (:id org))
                            (some? path) (str path)))]
          (testing "GET `/` returns credit overview"
            (is (= 200 (-> (mock/request :get (make-path))
                           (test-app)
                           :status))))
          
          (testing "POST `/issue` issues new credits to specific org"
            (is (= 201 (-> (h/json-request :post
                                           (make-path "/issue")
                                           {:amount 100
                                            :reason "test issue"})
                           (test-app)
                           :status))))

          (testing "`/subscription`"
            (let [reply (-> (h/json-request :post
                                            (make-path "/subscription")
                                            {:amount 100
                                             :valid-from (t/now)
                                             :valid-until (+ (t/now) 1000)})
                            (test-app))
                  created (h/reply->json reply)]
              (testing "`POST /` creates new credit subscription"
                (is (= 201 (:status reply)))
                (is (not-empty created))
                (is (cuid/cuid? (:id created))))
              
              (testing "`GET /` lists subscriptions"
                (let [reply (-> (mock/request :get (make-path "/subscription"))
                                (test-app))]
                  (is (= 200 (:status reply)))
                  (is (= [created] (h/reply->json reply)))))
              
              (testing "`GET /:subscription-id` retrieves subscription by id"
                (is (= 200 (-> (mock/request
                                :get
                                (make-path (str "/subscription/" (:id created))))
                               (test-app)
                               :status))))

              (testing "`DELETE /:subscription-id` disables subscription"
                (let [reply (-> (h/json-request
                                 :delete
                                 (make-path (str "/subscription/" (:id created)))
                                 {})
                                (test-app))]
                  (is (= 200 (:status reply)))
                  (is (= (:id created)
                         (:id (h/reply->json reply))))))))))

      (testing "POST `/issue` issues new credits to all orgs with subscriptions"
        (is (= 200 (-> (h/json-request :post "/admin/credits/issue"
                                       {:date "2025-01-16"})
                       (test-app)
                       :status)))))

    (testing "`/reaper` kills dangling builds"
      (is (= 200 (-> (mock/request :post "/admin/reaper")
                     (test-app)
                     :status))))

    (testing "fails if no sysadmin token"
      (let [secure-app (-> (test-rt {:config
                                     {:dev-mode false}})
                           (sut/make-app))]
        (is (= 401 (-> (mock/request :post "/admin/reaper")
                       (secure-app)
                       :status)))))

    (testing "`/login` authenticates admin user"
      (let [{st :storage :as rt} (test-rt)
            app (sut/make-app rt)
            uid (cuid/random-cuid)]
        (is (some? (st/save-user st {:id uid
                                     :type "sysadmin"
                                     :type-id "test-admin"})))
        (is (some? (st/save-sysadmin st {:user-id uid
                                         :password (auth/hash-pw "test-pass")})))
        (is (= 200 (-> (h/json-request :post "/admin/login"
                                       {:username "test-admin"
                                        :password "test-pass"})
                       (app)
                       :status)))))

    (let [m {:subject "test mailing"}]
      (verify-entity-endpoints {:path "/admin/mailing"
                                :name "mailing"
                                :creator st/save-mailing
                                :base-entity m
                                :updated-entity (assoc m :subject "updated subject")
                                :can-delete? true}))

    (testing "`/mailing`"
      (testing "`GET` lists all mailings"
        (is (= 204 (-> (mock/request :get "/admin/mailing")
                       (test-app)
                       :status))))

      (testing "`/:mailing-id/send`"
        (let [{:keys [id] :as m} (h/gen-mailing)
              {st :storage :as rt} (test-rt)
              app (sut/make-app rt)
              path (str "/admin/mailing/" id "/send")]
          (is (some? (st/save-mailing st m)))
          (testing "`GET` lists all for mailing"
            (is (= 204 (-> (mock/request :get path)
                           (app)
                           :status)))))))))

(deftest crypto-endpoints
  (testing "`POST /:org-id/crypto/decrypt-key` decrypts encrypted key"
    (let [org (h/gen-org)
          app (-> (test-rt)
                  (trt/set-decrypter (fn [k org-id id]
                                       (when (= org-id id (:id org))
                                         "decrypted-key")))
                  (sut/make-app))
          r (-> (h/json-request :post (format "/org/%s/crypto/decrypt-key" (:id org))
                                {:enc "encrypted-key"})
                (app))]
      (is (= 200 (:status r)))
      (is (= "decrypted-key" (-> (h/reply->json r)
                                 :key))))))

(deftest user-token-endpoints
  (h/with-memory-store st
    (testing "`/user/:user-id/token`"
      (let [user (h/gen-user)
            app (make-test-app st)]
        (is (some? (st/save-user st user)))
        (testing "`POST` creates new token"
          (is (= 201 (-> (h/json-request :post (format "/user/%s/token" (:id user))
                                         {:valid-until (+ (t/now) 10000)
                                          :description "test token"})
                         (app)
                         :status)))
          (let [t (st/list-user-tokens st (:id user))]
            (is (= 1 (count t)))
            (is (string? (-> t first :token)) "generates token value")))

        (let [r (-> (mock/request :get (format "/user/%s/token" (:id user)))
                    (app))
              b (h/reply->json r)]
          (testing "`GET` lists user tokens"
            (is (= 200 (:status r)))
            (is (= 1 (count b))))

          (testing "`GET /:token-id` retrieves token by id"
            (is (= (first b) (-> (mock/request
                                  :get
                                  (format "/user/%s/token/%s" (:id user) (-> b first :id)))
                                 (app)
                                 (h/reply->json)))))

          (testing "`DELETE /:token-id` deletes token"
            (is (= 204 (-> (mock/request
                            :delete
                            (format "/user/%s/token/%s" (:id user) (-> b first :id)))
                           (app)
                           :status)))))))))

(deftest org-token-endpoints
  (h/with-memory-store st
    (testing "`/org/:org-id/token`"
      (let [org (h/gen-org)
            app (make-test-app st)]
        (is (some? (st/save-org st org)))
        (testing "`POST` creates new token"
          (let [r (-> (h/json-request :post (format "/org/%s/token" (:id org))
                                      {:valid-until (+ (t/now) 10000)
                                       :description "test token"})
                      (app))
                b (h/reply->json r)]
            (is (= 201 (:status r)))
            (is (string? (:token b)) "generates token value")
            
            (let [t (st/list-org-tokens st (:id org))]
              (is (= 1 (count t)))
              (is (not= (-> t first :token) (:token b)) "stores token hashed"))))

        (let [r (-> (mock/request :get (format "/org/%s/token" (:id org)))
                    (app))
              b (h/reply->json r)]
          (testing "`GET` lists org tokens"
            (is (= 200 (:status r)))
            (is (= 1 (count b))))

          (testing "`GET /:token-id` retrieves token by id"
            (is (= (first b) (-> (mock/request
                                  :get
                                  (format "/org/%s/token/%s" (:id org) (-> b first :id)))
                                 (app)
                                 (h/reply->json)))))

          (testing "`DELETE /:token-id` deletes token"
            (is (= 204 (-> (mock/request
                            :delete
                            (format "/org/%s/token/%s" (:id org) (-> b first :id)))
                           (app)
                           :status)))))))))

(deftest resolve-id-from-db
  (h/with-memory-store s
    (let [org {:id (cuid/random-cuid)
               :display-id "test-org"}]
      (is (some? (st/save-org s org)))

      (testing "looks up org id in storage"
        (is (= (:id org)
               (-> {:storage s}
                   (h/->req)
                   (sut/resolve-id-from-db "test-org")))))

      (testing "returns original id when no match found"
        (is (= (:id org)
               (-> {:storage s}
                   (h/->req)
                   (sut/resolve-id-from-db (:id org)))))))))

(deftest cached-id-resolver
  (let [ids (atom {"original" "resolved"})
        r (sut/cached-id-resolver (fn [_ orig]
                                    (get @ids orig)))]
    (testing "invokes target first time"
      (is (= "resolved" (r {} "original"))))

    (testing "retrieves from cache second time"
      (is (some? (reset! ids {"original" "wrong"})))
      (is (= "resolved" (r {} "original"))))))
