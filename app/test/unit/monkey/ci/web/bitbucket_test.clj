(ns monkey.ci.web.bitbucket-test
  (:require [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.test
             [aleph-test :as at]
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web
             [auth :as auth]
             [bitbucket :as sut]
             [response :as r]]))

(deftest login
  (testing "when token request fails, returns 400 status"
    (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                        {:status 401}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status)))))

  (testing "generates new token and returns it"
    (let [user-id (str (random-uuid))]
      (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                          {:status 200
                           :body (h/to-json {:access-token "test-token"})
                           :headers {"Content-Type" "application/json"}}
                          "https://api.bitbucket.org/2.0/user"
                          {:status 200
                           :body (h/to-json {:uuid user-id})
                           :headers {"Content-Type" "application/json"}}]
        (let [kp (auth/generate-keypair)
              req (-> (trt/test-runtime)
                      (assoc :jwk {:priv (.getPrivate kp)})
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              token (-> req
                        (sut/login)
                        :body
                        :token)]
          (is (string? token))
          (let [u (jwt/unsign token (.getPublic kp) {:alg :rs256})]
            (is (map? u))
            (is (= (str "bitbucket/" user-id) (:sub u)))))))))

(deftest refresh
  (testing "refreshes token and returns it"
    (let [user-id (str (random-uuid))]
      (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                          (fn [req]
                            (if (not= "old-refresh-token" (get-in req [:form-params :refresh_token]))
                              {:status 400
                               :body (h/to-json {:message "Expected refresh token"})
                               :headers {"Content-Type" "application/json"}}
                              {:status 200
                               :body (h/to-json {:access-token "test-token"})
                               :headers {"Content-Type" "application/json"}}))
                          "https://api.bitbucket.org/2.0/user"
                          {:status 200
                           :body (h/to-json {:uuid user-id})
                           :headers {"Content-Type" "application/json"}}]
        (let [kp (auth/generate-keypair)
              req (-> (trt/test-runtime)
                      (assoc :jwk {:priv (.getPrivate kp)})
                      (h/->req)
                      (assoc :parameters
                             {:body
                              {:refresh-token "old-refresh-token"}}))
              token (-> req
                        (sut/refresh)
                        :body
                        :token)]
          (is (string? token))
          (let [u (jwt/unsign token (.getPublic kp) {:alg :rs256})]
            (is (map? u))
            (is (= (str "bitbucket/" user-id) (:sub u)))))))))

(deftest watch-repo
  (h/with-memory-store st
    (let [org (h/gen-org)
          ws "test-workspace"
          slug "test-bb-repo"
          bb-uuid (str (random-uuid))
          bb-requests (atom [])]
      (at/with-fake-http [{:url (format "https://api.bitbucket.org/2.0/repositories/%s/%s/hooks"
                                        ws slug)
                           :request-method :post}
                          (fn [req]
                            (if (some? (get-in req [:headers "Authorization"]))
                              (do
                                (swap! bb-requests conj (h/parse-json (:body req)))
                                {:status 201
                                 :headers {"Content-Type" "application/json"}
                                 :body (h/to-json {:uuid bb-uuid})})
                              {:status 401}))]
        (is (some? (st/save-org st org)))
        (let [r (-> {:storage st
                     :config
                     {:api
                      {:ext-url "http://api.monkeyci.test"}}}
                    (h/->req)
                    (assoc :uri "/org/test-org"
                           :scheme :http
                           :headers {"host" "localhost"}
                           :parameters
                           {:path
                            {:org-id (:id org)}
                            :body
                            {:org-id (:id org)
                             :workspace ws
                             :repo-slug slug
                             :url "http://bitbucket.org/test-repo"
                             :name "test repo"
                             :token "test-bb-token"}})
                    (sut/watch-repo))]
          (is (= 201 (:status r)))
          
          (testing "creates repo in db"
            (is (some? (st/find-repo st [(:id org) (get-in r [:body :id])]))))

          (let [whs (->> (p/list-obj st (st/webhook-sid))
                         (map (partial st/find-webhook st)))
                wh (->> whs
                        (filter (comp (partial = (get-in r [:body :id])) :repo-id))
                        (first))]
            (testing "creates webhook in db"
              (is (not-empty whs))
              (is (some? wh))
              (is (string? (:secret-key wh))))
            
            (testing "creates bitbucket webhook in repo"
              (let [bb-ws (->> (p/list-obj st (st/bb-webhook-sid))
                               (map (partial st/find-bb-webhook st)))
                    bb-wh (->> bb-ws
                               (filter (comp (partial = (:id wh)) :webhook-id))
                               (first))]
                (is (not-empty bb-ws))
                (is (some? bb-wh))
                (is (= bb-uuid (:bitbucket-id bb-wh)))
                (is (= 1 (count @bb-requests)))
                (is (= (str "http://api.monkeyci.test/webhook/bitbucket/" (:id wh))
                       (:url (first @bb-requests))))
                (is (= slug (:repo-slug bb-wh)))
                (is (= ws (:workspace bb-wh)))))))))

    (testing "404 if org not found"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc-in [:parameters :path :org-id] "invalid-org")
                     (assoc :scheme :http
                            :headers {"host" "localhost"}
                            :uri "/org")
                     (sut/watch-repo)
                     :status))))))

(deftest unwatch-repo
  (h/with-memory-store st
    (let [ws "test-workspace"
          repo-slug "test-repo"
          wh-uuid (str (random-uuid))
          repo (h/gen-repo)
          org (-> (h/gen-org)
                  (assoc :repos {(:id repo) repo}))
          wh {:org-id (:id org)
              :repo-id (:id repo)
              :id (cuid/random-cuid)}
          bb-wh {:id (cuid/random-cuid)
                 :webhook-id (:id wh)
                 :bitbucket-id wh-uuid
                 :workspace ws
                 :repo-slug repo-slug}
          inv (atom [])]
      
      (is (some? (st/save-org st org)))
      (is (some? (st/save-webhook st wh)))
      (is (some? (st/save-bb-webhook st bb-wh)))

      (at/with-fake-http [{:url (format "https://api.bitbucket.org/2.0/repositories/%s/%s/hooks/%s"
                                        ws repo-slug wh-uuid)
                           :request-method :delete}
                          (fn [req]
                            (swap! inv conj req)
                            {:status 204
                             :headers {"Content-Type" "application/json"}})]
        (is (= 200 (-> {:storage st}
                       (h/->req)
                       (assoc :parameters
                              {:path
                               {:org-id (:id org)
                                :repo-id (:id repo)}})
                       (sut/unwatch-repo)
                       :status)))
        
        (testing "deletes webhook records from database"
          (is (nil? (st/find-webhook st (:id wh)))))
        
        (testing "deletes webhook in bitbucket"
          (is (= 1 (count @inv)))))

      (testing "404 when repo not found"
        (is (= 404 (-> {:storage st}
                       (h/->req)
                       (sut/unwatch-repo)
                       :status)))))))

(deftest webhook
  (let [rt (-> (trt/test-runtime)
               (trt/set-encrypter (constantly "encrypted"))
               (trt/set-decrypter (constantly "decrypted"))
               (assoc :config {:ssh-keys-dir "/tmp"}))
        s (:storage rt)
        vault (v/make-fixed-key-vault {})
        rt (-> rt
               (trt/set-vault vault))
        repo (-> (h/gen-repo)
                 (assoc :url "http://test-url"))
        org (-> (h/gen-org)
                (assoc :repos {(:id repo) repo}))
        _ (st/save-org s org)
        wh {:id (cuid/random-cuid)
            :repo-id (:id repo)
            :org-id (:id org)}
        _ (st/save-webhook s wh)
        _ (st/save-org-credit s {:org-id (:id org)
                                 :amount 1000})
        req (-> rt
                (h/->req)
                (assoc :headers {"x-event-key" "repo:push"}
                       :parameters
                       {:path
                        {:id (:id wh)}
                        :body
                        {:push
                         {:changes
                          [{:new
                            {:type "branch"
                             :name "main"}
                            :target
                            {:message "Test commit"}}]}}}))]

    (let [resp (sut/webhook req)]
      (is (= 202 (:status resp)))

      (testing "returns id"
        (is (cuid/cuid? (get-in resp [:body :id]))))
      
      (testing "triggers build for webhook"
        (let [evts (r/get-events resp)
              {:keys [git] :as build} (:build (first evts))]
          (is (= 1 (count evts)))
          (is (= :build/triggered (-> evts first :type)))
          (is (some? build))
          (is (some? git) "contains git info")
          (is (= "http://test-url" (:url git)))
          (is (= "refs/heads/main" (:ref git)))))

      (testing "does not create build in storage"
        (is (nil? (st/find-build s [(:id org) (:id repo) (get-in resp [:body :build-id])])))))

    (testing "adds configured encrypted ssh key matching repo labels"
      (let [iv (v/generate-iv)
            ssh-key {:id "test-key"
                     :private-key "encrypted-key"}]
        (is (st/sid? (st/save-repo s (assoc repo :labels [{:name "ssh-lbl"
                                                           :value "lbl-val"}]))))
        (is (st/sid? (st/save-ssh-keys s (:id org) [ssh-key])))
        (is (st/sid? (st/save-crypto s {:org-id (:id org)
                                        :iv iv})))
        (let [evts (-> (sut/webhook req)
                       (r/get-events))]
          (is (= 1 (count evts)))
          (is (= 1 (-> evts
                       first
                       :build
                       (get-in [:git :ssh-keys])
                       (count)))))))
    
    (testing "404 if webhook does not exist"
      (is (= 404 (-> rt
                     (h/->req)
                     (assoc :headers {"x-event-key" "repo:push"}
                            :parameters
                            {:path
                             {:id (cuid/random-cuid)}})
                     (sut/webhook)
                     :status))))

    (testing "ignoring non-push events"
      (is (= 200 (-> rt
                     (h/->req)
                     (assoc :headers {"x-event-key" "some:other"})
                     (sut/webhook)
                     :status))))))
