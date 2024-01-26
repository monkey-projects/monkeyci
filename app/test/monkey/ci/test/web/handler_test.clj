(ns monkey.ci.test.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.string :as cs]
            [monkey.ci
             [config :as config]
             [events :as events]
             [logging :as l]
             [storage :as st]]
            [monkey.ci.web
             [auth :as auth]
             [handler :as sut]]
            [monkey.ci.test.helpers :refer [try-take] :as h]
            [org.httpkit
             [fake :as hf]
             [server :as http]]
            [reitit
             [core :as rc]
             [ring :as ring]]
            [ring.mock.request :as mock]))

(deftest make-app
  (testing "creates a fn"
    (is (fn? (sut/make-app {})))))

(def github-secret "github-secret")

(defn- test-ctx [& [opts]]
  (-> (merge {:event-bus (events/make-bus)
              :dev-mode true}
             opts)
      (update :storage #(or % (st/make-memory-storage)))))

(defn- make-test-app
  ([storage]
   (sut/make-app (test-ctx {:storage storage})))
  ([]
   (sut/make-app (test-ctx))))

(def test-app (make-test-app))

(deftest start-server
  (with-redefs [http/run-server (fn [h opts]
                                  {:handler h
                                   :opts opts})]
    
    (testing "starts http server with default port"
      (is (number? (-> (sut/start-server {})
                       :opts
                       :port))))

    (testing "passes args as opts"
      (is (= 1234 (-> (sut/start-server {:http {:port 1234}})
                      :opts
                      :port))))

    (testing "handler is a fn"
      (is (fn? (:handler (sut/start-server {})))))))

(deftest stop-server
  (with-redefs [http/server-stop! (constantly ::stopped)]
    (testing "stops the server"
      (is (= ::stopped (sut/stop-server :dummy-server))))

    (testing "does nothing when server is `nil`"
      (is (nil? (sut/stop-server nil))))))

(deftest http-routes
  (testing "health check at `/health`"
    (is (= 200 (-> (mock/request :get "/health")
                   (test-app)
                   :status))))

  (testing "version at `/version`"
    (let [r (-> (mock/request :get "/version")
                   (test-app))]
      (is (= 200 (:status r)))
      (is (= (config/version) (:body r)))))

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
  (testing "`POST /webhook/github/:id`"
    (testing "accepts with valid security header"
      (let [payload "test body"
            signature (-> (mac/hash payload {:key github-secret
                                             :alg :hmac+sha256})
                          (codecs/bytes->hex))
            hook-id (st/new-id)
            st (st/make-memory-storage)
            app (sut/make-app (test-ctx {:storage st :dev-mode false}))]
        (is (st/sid? (st/save-webhook-details st {:id hook-id
                                                  :secret-key github-secret})))
        (is (= 200 (-> (mock/request :post (str "/webhook/github/" hook-id))
                       (mock/body payload)
                       (mock/header :x-hub-signature-256 (str "sha256=" signature))
                       (app)
                       :status)))))

    (testing "returns 401 if invalid security"
      (let [app (sut/make-app (test-ctx {:dev-mode false}))]
        (is (= 401 (-> (mock/request :post "/webhook/github/test-hook")
                       (app)
                       :status)))))

    (testing "disables security check when in dev mode"
      (let [dev-app (sut/make-app {:dev-mode true
                                   :event-bus (events/make-bus)})]
        (is (= 200 (-> (mock/request :post "/webhook/github/test-hook")
                       (dev-app)
                       :status)))))

    (testing "passes id as path parameter"
      (h/with-bus
        (fn [bus]
          (let [dev-app (sut/make-app {:dev-mode true
                                       :event-bus bus})
                l (events/wait-for bus :webhook/github (map :id))]
            (is (= 200 (-> (h/json-request :post "/webhook/github/test-hook"
                                           {:head-commit {:id "test-commit"}})
                           (dev-app)
                           :status)))
            (is (= "test-hook" (h/try-take l 200 :timeout)))))))))

(defn- verify-entity-endpoints [{:keys [path base-entity updated-entity name creator]}]
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
            (is (= entity (some-> r
                                  :body
                                  slurp
                                  h/parse-json)))))

        (testing (str "`PUT` updates existing " name)
          (let [id (st/new-id)
                _ (creator st (assoc base-entity :id id))
                r (-> (h/json-request :put (str path "/" id)
                                      (cond-> base-entity
                                        updated-entity (merge updated-entity)))
                      (app))]
            (is (= 200 (:status r)))))))))

(deftype TestLogRetriever [logs]
  l/LogRetriever
  (list-logs [_ sid]
    (keys logs))

  (fetch-log [_ sid p]
    (some->> p
             (get logs)
             (.getBytes)
             (java.io.ByteArrayInputStream.))))

(deftest customer-endpoints
  (verify-entity-endpoints {:name "customer"
                            :base-entity {:name "test customer"}
                            :updated-entity {:name "updated customer"}
                            :creator st/save-customer})

  (h/with-memory-store st
    (let [kp (auth/generate-keypair)
          ctx (test-ctx {:storage st
                         :dev-mode false
                         :jwk (auth/keypair->ctx kp)})
          cust-id (st/new-id)
          github-id 6453
          app (sut/make-app ctx)
          token (auth/sign-jwt {:sub (str "github/" github-id)} (.getPrivate kp))
          _ (st/save-customer st {:id cust-id
                                  :name "test customer"})
          _ (st/save-user st {:type "github"
                              :type-id github-id
                              :customers [cust-id]})]

      (testing "ok if user has access to customer"
        (is (= 200 (-> (mock/request :get (str "/customer/" cust-id))
                       (mock/header "authorization" (str "Bearer " token))
                       (app)
                       :status))))

      (testing "unauthorized if user does not have access to customer"
        (is (= 403 (-> (mock/request :get (str "/customer/" (st/new-id)))
                       (mock/header "authorization" (str "Bearer " token))
                       (app)
                       :status))))
      
      (testing "unauthenticated if no user credentials"
        (is (= 401 (-> (mock/request :get (str "/customer/" cust-id))
                       (app)
                       :status)))))))

(deftest repository-endpoints
  (let [cust-id (st/new-id)]
    (verify-entity-endpoints {:name "repository"
                              :path (format "/customer/%s/repo" cust-id)
                              :base-entity {:name "test repo"
                                            :customer-id cust-id
                                            :url "http://test-repo"
                                            :labels [{:name "app" :value "test-app"}]}
                              :updated-entity {:name "updated repo"}
                              :creator st/save-repo})))

(deftest webhook-endpoints
  (verify-entity-endpoints {:name "webhook"
                            :base-entity {:customer-id "test-cust"
                                          :repo-id "test-repo"}
                            :updated-entity {:repo-id "updated-repo"}
                            :creator st/save-webhook-details}))

(deftest user-endpoints
  (testing "/user"
    (let [user {:type "github"
                :type-id 456
                :email "testuser@monkeyci.com"}]
      
      (testing "`POST` creates new user"
        (let [st (st/make-memory-storage)
              app (make-test-app st)
              r (-> (h/json-request :post "/user" user)
                    (app))]
          (is (= 201 (:status r)))
          (is (= user (-> (st/find-user st [:github 456])
                          (select-keys (keys user)))))))

      (testing "`GET /:type/:id` retrieves existing user"
        (let [st (st/make-memory-storage)
              _ (st/save-user st user)
              app (make-test-app st)
              r (-> (mock/request :get (str "/user/github/" (:type-id user)))
                    (app))]
          (is (= 200 (:status r)))
          (is (= (:type-id user) (some-> r :body slurp (h/parse-json) :type-id)))))

      (testing "`PUT /:type/:id` updates existing user"
        (let [st (st/make-memory-storage)
              _ (st/save-user st user)
              app (make-test-app st)
              r (-> (h/json-request :put (str "/user/github/" (:type-id user))
                                    (assoc user :email "updated@monkeyci.com"))
                    (app))]
          (is (= 200 (:status r)))
          (is (= "updated@monkeyci.com" (some-> r :body slurp (h/parse-json) :email))))))))

(defn- verify-label-filter-like-endpoints [path desc entity prep-match]
  (let [st (st/make-memory-storage)
        app (make-test-app st)
        get-entity
        (fn [path]
          (some-> (mock/request :get path)
                  (app)
                  :body
                  slurp
                  (h/parse-json)))
        save-entity
        (fn [path params]
          (-> (h/json-request :put path entity)
              (app)))]

    (testing "/customer/:customer-id"
      
      (testing path
        (let [cust-id (st/new-id)
              full-path (format "/customer/%s%s" cust-id path)]
          
          (testing (str "empty when no " desc)
            (is (empty? (get-entity full-path))))
          
          (testing (str "can write " desc)
            (is (= 200 (:status (save-entity full-path entity)))))

          (testing (str "can partially update using `PATCH`"))
          
          (testing (str "can read " desc)
            (is (get-entity full-path)
                entity))))

      (testing (str "/repo/:repo-id" path)
        (let [[cust-id repo-id] (repeatedly st/new-id)
              full-path (format "/customer/%s/repo/%s%s" cust-id repo-id path)
              _ (st/save-customer st {:id cust-id
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
   :parameters))

(deftest ssh-keys-endpoints
  (verify-label-filter-like-endpoints
   "/ssh-keys"
   "ssh keys"
   [{:private-key "private-test-key"
     :public-key "public-test-key"
     :description "test ssh key"
     :label-filters []}]
   :private-key))

(defn- generate-build-sid []
  (->> (repeatedly st/new-id)
       (take 3)
       (st/->sid)))

(defn- repo-path [sid]
  (str (->> sid
            (drop-last)
            (interleave ["/customer" "repo"])
            (cs/join "/"))
       "/builds"))

(defn- build-path [sid]
  (str (repo-path sid) "/" (last sid)))

(defn- with-repo [f]
  (h/with-memory-store st
    (h/with-bus
      (fn [bus]
        (let [app (sut/make-app {:storage st
                                 :event-bus bus
                                 :dev-mode true})
              sid (generate-build-sid)
              path (repo-path sid)]
          (is (st/sid? (st/save-build-results st sid {:exit 0 :status :success})))
          (is (st/sid? (st/create-build-metadata st sid {:message "test meta"})))
          (f {:bus bus
              :storage st
              :sid sid
              :path path
              :app app}))))))

(deftest build-endpoints
  (testing "`GET` lists repo builds"
    (with-repo
      (fn [{:keys [path app] [_ _ build-id] :sid}]
        (let [l (-> (mock/request :get path)
                    (app))
              b (-> l
                    :body
                    slurp
                    h/parse-json)]
          (is (= 200 (:status l)))
          (is (= 1 (count b)))
          (is (= build-id (:id (first b))) "should contain build id")
          (is (= "test meta" (:message (first b))) "should contain build metadata")))))
  
  (testing "`POST /trigger`"
    (letfn [(catch-build-triggered-event [p f]
              (with-repo
                (fn [{:keys [bus app path] :as ctx}]
                  (let [events (atom [])
                        props [:customer-id :repo-id]
                        _ (events/register-handler bus :build/triggered (partial swap! events conj))]
                    (is (= 200 (-> (mock/request :post (str path p))
                                   (app)
                                   :status)))
                    (is (not= :timeout (h/wait-until #(pos? (count @events)) 500)))
                    (f (assoc ctx :event (first @events)))))))]
      
      (testing "triggers new build for repo"
        (catch-build-triggered-event
         "/trigger"
         (fn [{:keys [bus app path sid event]}]
           (let [props [:customer-id :repo-id]]
             (is (= (zipmap props sid)
                    (select-keys (:account event) props)))
             (is (some? (-> event :build :build-id)))))))
      
      (testing "looks up url in repo config"
        (with-repo
          (fn [{:keys [bus app path] [customer-id repo-id] :sid st :storage}]
            (let [events (atom [])
                  _ (events/register-handler bus :build/triggered (partial swap! events conj))]
              (is (some? (st/save-customer st {:id customer-id
                                               :repos
                                               {repo-id
                                                {:id repo-id
                                                 :url "http://test-url"}}})))
              (is (= 200 (-> (mock/request :post (str path "/trigger"))
                             (app)
                             :status)))
              (is (not= :timeout (h/wait-until #(pos? (count @events)) 500)))
              (is (= "http://test-url"
                     (-> @events first :build :git :url)))))))
      
      (testing "adds commit id from query params"
        (catch-build-triggered-event
         "/trigger?commitId=test-id"
         (fn [{:keys [event]}]
           (is (= "test-id"
                  (-> event :build :git :commit-id))))))

      (testing "adds branch from query params as ref"
        (catch-build-triggered-event
         "/trigger?branch=test-branch"
         (fn [{:keys [event]}]
           (is (= "refs/heads/test-branch"
                  (-> event :build :git :ref))))))

      (testing "adds tag from query params as ref"
        (catch-build-triggered-event
         "/trigger?tag=test-tag"
         (fn [{:keys [event]}]
           (is (= "refs/tags/test-tag"
                  (-> event :build :git :ref))))))

      (testing "adds `sid` to build props"
        (catch-build-triggered-event
         "/trigger"
         (fn [{:keys [sid event]}]
           (let [bsid (get-in event [:build :sid])]
             (is (= 3 (count bsid)) "expected sid to contain repo path and build id")
             (is (= (take 2 sid) (take 2 bsid)))
             (is (= (get-in event [:build :build-id])
                    (last bsid)))))))

      (testing "creates build metadata in storage"
        (catch-build-triggered-event
         "/trigger?branch=test-branch"
         (fn [{:keys [event] st :storage}]
           (let [bsid (get-in event [:build :sid])
                 md (st/find-build-metadata st bsid)]
             (is (some? md))
             (is (= "refs/heads/test-branch" (:ref md)))))))
      
      (testing "returns build id")

      (testing "returns 404 (not found) when repo does not exist")

      (testing "when no branch specified, uses default branch")))
  
  (testing "`GET /latest`"
    (with-repo
      (fn [{:keys [path app] [_ _ build-id :as sid] :sid st :storage}]
        (testing "retrieves latest build for repo"
          (let [l (-> (mock/request :get (str path "/latest"))
                      (app))
                b (some-> l
                          :body
                          slurp
                          h/parse-json)]
            (is (= 200 (:status l)))
            (is (map? b))
            (is (= build-id (:id b)) "should contain build id")
            (is (= "test meta" (:message b)) "should contain build metadata")))

        (testing "204 when there are no builds"
          (let [sid (generate-build-sid)
                path (repo-path sid)
                l (-> (mock/request :get (str path "/latest"))
                      (app))]
            (is (empty? (st/list-builds st (drop-last sid))))
            (is (= 204 (:status l)))
            (is (nil? (:body l)))))

        (testing "404 when repo does not exist"))))

  (testing "`GET /:build-id`"
    (with-repo
      (fn [{:keys [app] [_ _ build-id :as sid] :sid}]
        (testing "retrieves build with given id"
          (let [l (-> (mock/request :get (build-path sid))
                      (app))
                b (some-> l :body slurp h/parse-json)]
            (is (not-empty l))
            (is (= 200 (:status l)))
            (is (= build-id (:id b)))))

        (testing "404 when build does not exist"
          (let [sid (generate-build-sid)
                l (-> (mock/request :get (build-path sid))
                      (app))]
            (is (= 404 (:status l)))
            (is (nil? (:body l)))))

        (testing "/logs"
          (testing "`GET` retrieves list of available logs for build"
            (let [app (sut/make-app (test-ctx {:logging {:retriever (->TestLogRetriever {})}}))
                  l (->> (str (build-path sid) "/logs")
                         (mock/request :get)
                         (app))]
              (is (= 200 (:status l)))))

          (testing "`GET /download`"
            (testing "downloads log file by query param"
              (let [app (sut/make-app (test-ctx
                                       {:logging
                                        {:retriever
                                         (->TestLogRetriever {"out.txt" "test log file"})}}))
                    l (->> (str (build-path sid) "/logs/download?path=out.txt")
                           (mock/request :get)
                           (app))]
                (is (= 200 (:status l)))))

            (testing "404 when log file not found"
              (let [app (sut/make-app (test-ctx
                                       {:logging
                                        {:retriever
                                         (->TestLogRetriever {})}}))
                    l (->> (str (build-path sid) "/logs/download?path=out.txt")
                           (mock/request :get)
                           (app))]
                (is (= 404 (:status l)))))))))))

(deftest event-stream
  (testing "'GET /events' exists"
    (is (not= 404
              (-> (mock/request :get "/events")
                  (test-app)
                  :status)))))

(deftest github-endpoints
  (testing "`POST /github/login` requests token from github and fetches user info"
    (hf/with-fake-http [{:url "https://github.com/login/oauth/access_token"
                         :method :post}
                        (fn [_ req _]
                          (if (= {:client_id "test-client-id"
                                  :client_secret "test-secret"
                                  :code "1234"}
                                 (:query-params req))
                            {:status 200 :body (h/to-raw-json {:access_token "test-token"})}
                            {:status 400 :body (str "invalid query params:" (:query-params req))}))
                        {:url "https://api.github.com/user"
                         :method :get}
                        (fn [_ req _]
                          (let [auth (get-in req [:headers "Authorization"])]
                            (if (= "Bearer test-token" auth)
                              {:status 200 :body (h/to-raw-json {:name "test-user"
                                                                 :other-key "other-value"})}
                              {:status 400 :body (str "invalid auth header: " auth)})))]
      (let [app (-> (test-ctx {:github {:client-id "test-client-id"
                                        :client-secret "test-secret"}
                               :jwk (auth/keypair->ctx (auth/generate-keypair))})
                    (sut/make-app))
            r (-> (mock/request :post "/github/login?code=1234")
                  (app))]
        (is (= 200 (:status r)) (:body r))
        (is (= "test-user"
               (some-> (:body r)
                       (slurp)
                       (h/parse-json)
                       :name))))))

  (testing "`GET /github/config` returns client id"
    (let [app (-> (test-ctx {:github {:client-id "test-client-id"}})
                  (sut/make-app))
          r (-> (mock/request :get "/github/config")
                (app))]
      (is (= 200 (:status r)))
      (is (= "test-client-id" (some-> r :body slurp h/parse-json :client-id))))))

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
            ctx {:jwk (auth/keypair->ctx kp)}
            app (sut/make-app ctx)
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
