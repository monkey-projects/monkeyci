(ns monkey.ci.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure
             [string :as cs]
             [test :refer [deftest is testing]]]
            [meta-merge.core :as mm]
            [monkey.ci
             [artifacts :as a]
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
  (-> (merge {:config {:dev-mode true}
              :vault (h/dummy-vault)}
             opts)
      (update :storage #(or % (st/make-memory-storage)))))

(defn- make-test-app
  ([storage]
   (sut/make-app (test-rt {:storage storage})))
  ([]
   (sut/make-app (test-rt))))

(def test-app (make-test-app))

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

(deftest webhook-routes
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

(defn- verify-entity-endpoints [{:keys [path base-entity updated-entity name creator can-update? can-delete?]
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
                            :creator st/save-org})

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
              cust (h/gen-cust)
              user (h/gen-user)
              jr {:id (st/new-id)
                  :status "pending"
                  :org-id (:id cust)
                  :user-id (:id user)}]
          (is (some? (st/save-org st cust)))
          (is (some? (st/save-user st user)))
          (is (some? (st/save-join-request st jr)))

          (testing "`POST` returns 400 when user has already joined that org")
          
          (testing "`GET` retrieves join requests for org"
            (is (= [jr]
                   (-> (mock/request :get (str "/org/" (:id cust) "/join-request"))
                       (app)
                       (h/reply->json)))))

          (testing "allows filtering by status")
          
          (testing "`POST /:request-id/approve`"
            (testing "approves join request with message"
              (is (= 200
                     (-> (h/json-request
                          :post (str "/org/" (:id cust) "/join-request/" (:id jr) "/approve")
                          {:message "Very well"})
                         (app)
                         :status)))
              (let [m (st/find-join-request st (:id jr))]
                (is (= :approved (:status m)))
                (is (= "Very well" (:response-msg m)))))
            
            (testing "returns 404 not found for non existing request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (:id cust) "/join-request/" (st/new-id) "/approve")
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
                          :post (str "/org/" (:id cust) "/join-request/" (:id jr) "/reject")
                          {:message "No way"})
                         (app)
                         :status)))
              (let [m (st/find-join-request st (:id jr))]
                (is (= :rejected (:status m)))
                (is (= "No way" (:response-msg m)))))
            
            (testing "returns 404 not found for non existing request"
              (is (= 404
                     (-> (h/json-request
                          :post (str "/org/" (:id cust) "/join-request/" (st/new-id) "/reject")
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
      (let [app (make-test-app st)
            cust (h/gen-cust)]
        (is (some? (st/save-org st cust)))
        
        (testing "`/builds`"
          (testing "`/recent` retrieves builds from latest 24h"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/builds/recent"))
                           (app)
                           :status))))

          (testing "`/latest` retrieves latest builds for each repo"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/builds/latest"))
                           (app)
                           :status)))))

        (testing "`GET /stats` retrieves org statistics"
          (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/stats"))
                         (app)
                         :status))))

        (testing "`/credits`"
          (testing "`GET` retrieves org credit details"
            (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/credits"))
                           (app)
                           :status)))))

        (testing "`/webhook/bitbucket`"
          (let [repo (h/gen-repo)
                cust (assoc cust :repos {(:id repo) repo})
                wh {:id (cuid/random-cuid)
                    :org-id (:id cust)
                    :repo-id (:id repo)
                    :secret "test secret"}
                bb {:webhook-id (:id wh)
                    :workspace "test-ws"
                    :repo-slug "test-repo"
                    :bitbucket-id (str (random-uuid))}
                search (fn [path]
                         (-> (mock/request :get (str (format "/org/%s/webhook/bitbucket" (:id cust)) path))
                             (app)
                             :body
                             slurp
                             (h/parse-json)))
                select-props #(select-keys % (keys bb))]
            (is (some? (st/save-org st cust)))
            (is (some? (st/save-webhook st wh)))
            (is (some? (st/save-bb-webhook st bb)))
            
            (testing "`GET` lists bitbucket webhooks for org"
              (is (= [bb] (->> (search "")
                               (map select-props)))))

            (testing "contains org and repo id"
              (let [r (-> (search "") first)]
                (is (= (:id cust) (:org-id r)))
                (is (= (:id repo) (:repo-id r)))))
            
            (testing "allows filtering by query params"
              (is (= [bb] (->> (search "?workspace=test-ws")
                               (map select-props))))
              (is (empty? (search "?repo-id=nonexisting"))))))

        (testing "`/invoice`"
          (let [inv (-> (h/gen-invoice)
                        (assoc :org-id (:id cust)))]
            (is (some? (st/save-invoice st inv)))
            
            (testing "`GET` searches invoices"
              (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/invoice"))
                             (app)
                             :status))))

            (testing "`GET /:id` retrieves by id"
              (is (= 200 (-> (mock/request :get (str "/org/" (:id cust) "/invoice/" (:id inv)))
                             (app)
                             :status)))))))))

  (h/with-memory-store st
    (let [kp (auth/generate-keypair)
          rt (test-rt {:storage st
                       :jwk (auth/keypair->rt kp)
                       :config {:dev-mode false}})
          cust-id (st/new-id)
          github-id 6453
          app (sut/make-app rt)
          token-info {:sub (str "github/" github-id)
                      :role auth/role-user
                      :exp (+ (u/now) 10000)}
          make-token (fn [ti]
                       (auth/sign-jwt ti (.getPrivate kp)))
          token (make-token token-info)
          _ (st/save-org st {:id cust-id
                                  :name "test org"})
          _ (st/save-user st {:type "github"
                              :type-id github-id
                              :orgs [cust-id]})]

      (testing "ok if user has access to org"
        (is (= 200 (-> (mock/request :get (str "/org/" cust-id))
                       (mock/header "authorization" (str "Bearer " token))
                       (app)
                       :status))))

      (testing "unauthorized if user does not have access to org"
        (is (= 403 (-> (mock/request :get (str "/org/" (st/new-id)))
                       (mock/header "authorization" (str "Bearer " token))
                       (app)
                       :status))))
      
      (testing "unauthenticated if no user credentials"
        (is (= 401 (-> (mock/request :get (str "/org/" cust-id))
                       (app)
                       :status))))

      (testing "unauthenticated if token expired"
        (is (= 401 (-> (mock/request :get (str "/org/" cust-id))
                       (mock/header "authorization" (str "Bearer " (make-token
                                                                    (assoc token-info :exp (- (u/now) 1000)))))
                       (app)
                       :status)))))))

(deftest repository-endpoints
  (let [cust-id (st/new-id)]
    (verify-entity-endpoints {:name "repository"
                              :path (format "/org/%s/repo" cust-id)
                              :base-entity {:name "test repo"
                                            :org-id cust-id
                                            :url "http://test-repo"
                                            :labels [{:name "app" :value "test-app"}]}
                              :updated-entity {:name "updated repo"}
                              :creator st/save-repo
                              :can-delete? true})
    
    (testing "`/org/:id`"
      (testing "`/github`"
        (testing "`/watch` starts watching repo"
          (is (= 200 (-> (h/json-request :post
                                         (str "/org/" cust-id "/repo/github/watch")
                                         {:github-id 12324
                                          :org-id cust-id
                                          :name "test-repo"
                                          :url "http://test"})
                         (test-app)
                         :status))))

        (testing "`/unwatch` stops watching repo"
          (let [st (st/make-memory-storage)
                app (make-test-app st)
                repo-id (st/new-id)
                _ (st/watch-github-repo st {:org-id cust-id
                                            :id repo-id
                                            :github-id 1234})]
            (is (= 200 (-> (mock/request :post
                                         (format "/org/%s/repo/%s/github/unwatch" cust-id repo-id))
                           (app)
                           :status))))))

      (testing "`/bitbucket`"
        (at/with-fake-http [(constantly true) {:status 201
                                               :headers {"Content-Type" "application/json"}
                                               :body "{}"}]
          (testing "`/watch` starts watching bitbucket repo"
            (is (= 201 (-> (h/json-request :post
                                           (str "/org/" cust-id "/repo/bitbucket/watch")
                                           {:org-id cust-id
                                            :name "test-repo"
                                            :url "http://test"
                                            :workspace "test-ws"
                                            :repo-slug "test-repo"
                                            :token "test-token"})
                           (test-app)
                           :status))))

          (testing "`/unwatch` stops watching repo"
            (let [st (st/make-memory-storage)
                  app (make-test-app st)
                  repo-id (st/new-id)
                  wh-id (st/new-id)
                  _ (st/save-org st {:id cust-id
                                          :repos {repo-id {:id repo-id}}})
                  _ (st/save-webhook st {:org-id cust-id
                                         :repo-id repo-id
                                         :id wh-id
                                         :secret (str (random-uuid))})
                  _ (st/save-bb-webhook st {:webhook-id wh-id
                                            :bitbucket-id (str (random-uuid))})]
              (is (= 200 (-> (h/json-request :post
                                             (format "/org/%s/repo/%s/bitbucket/unwatch" cust-id repo-id)
                                             {:token "test-token"})
                             (app)
                             :status))))))))))

(deftest webhook-endpoints
  (verify-entity-endpoints {:name "webhook"
                            :base-entity {:org-id "test-cust"
                                          :repo-id "test-repo"}
                            :updated-entity {:repo-id "updated-repo"}
                            :creator st/save-webhook}))

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

      (testing "`GET /orgs` retrieves orgs for user"
        (let [user (st/find-user-by-type st (user->sid user))
              user-id (:id user)
              cust {:id (st/new-id)
                    :name "test org"}]
          (is (some? (st/save-org st cust)))
          (is (some? (st/save-user st (assoc user :orgs [(:id cust)]))))
          (is (= [cust] (-> (mock/request :get (str "/user/" user-id "/orgs"))
                            (app)
                            (h/reply->json))))))

      (testing "/join-request"
        (let [user (st/find-user-by-type st (user->sid user))
              user-id (:id user)
              cust {:id (st/new-id)
                    :name "joining org"}
              base-path (str "/user/" user-id "/join-request")]

          (is (some? (st/save-org st cust)))
          
          (testing "`POST` create new join request"
            (let [r (-> (h/json-request :post base-path 
                                        {:org-id (:id cust)})
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
              (is (empty? (st/list-user-join-requests st user-id))))))))))

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
        (let [cust-id (st/new-id)
              full-path (format "/org/%s%s" cust-id path)]
          
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
        (let [[cust-id repo-id] (repeatedly st/new-id)
              full-path (format "/org/%s/repo/%s%s" cust-id repo-id path)
              _ (st/save-org st {:id cust-id
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

  (let [cust-id (st/new-id)
        param {:parameters [{:name "test-param"
                             :value "test value"}]
               :description "original desc"
               :org-id cust-id
               :label-filters []}]
    (verify-entity-endpoints
     {:name "org param"
      :path (format "/org/%s/param" cust-id)
      :base-entity param
      :updated-entity {:description "updated description"}
      :creator (fn [s p]
                 (st/save-param s (assoc p :org-id cust-id)))
      :can-delete? true})))

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
  #_(let [cust-id (st/new-id)
          ssh-key {:private-key "test-private-key"
                   :public-key "test-public-key"
                   :description "original desc"
                   :org-id cust-id
                   :label-filters []}]
      (verify-entity-endpoints
       {:name "org ssh key"
        :path (format "/org/%s/ssh-keys" cust-id)
        :base-entity ssh-key
        :updated-entity {:description "updated description"}
        :creator (fn [s p]
                   (st/save-ssh-key s (assoc p :org-id cust-id)))
        :can-delete? true})))

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
          (is (= 202 (-> (mock/request :post (str path "/trigger"))
                         (app)
                         :status)))
          (is (= [:build/triggered]
                 (->> ctx
                      (trt/get-mailman)
                      (tmm/get-posted)
                      (map :type))))))))
  
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
                (is (= 404 (:status l)))))))))))

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
      (is (= 404 (-> (mock/request :get "/auth/jwks")
                     (test-app)
                     :status))))))

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
  (let [[cust-id repo-id build-id :as sid] (repeatedly 3 st/new-id)
        base-path (format "/org/%s/repo/%s/builds/%s" cust-id repo-id build-id)
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
                            :can-delete? true}))

(deftest admin-routes
  (testing "`/admin`"
    (testing "`/credits`"
      (testing "`/:org-id`"
        (let [cust (h/gen-cust)
              make-path (fn [& [path]]
                          (cond-> (str "/admin/credits/" (:id cust))
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
                       :status)))))))
