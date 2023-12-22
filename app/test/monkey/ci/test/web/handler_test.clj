(ns monkey.ci.test.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.string :as cs]
            [monkey.ci
             [events :as events]
             [storage :as st]]
            [monkey.ci.web.handler :as sut]
            [monkey.ci.test.helpers :refer [try-take] :as h]
            [org.httpkit.server :as http]
            [reitit
             [core :as rc]
             [ring :as ring]]
            [ring.mock.request :as mock]))

(deftest make-app
  (testing "creates a fn"
    (is (fn? (sut/make-app {})))))

(def github-secret "github-secret")

(defn- make-test-app
  ([storage]
   (sut/make-app
    {:event-bus (events/make-bus)
     :storage storage}))
  ([]
   (make-test-app (st/make-memory-storage))))

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
            app (make-test-app st)]
        (is (st/sid? (st/save-webhook-details st {:id hook-id
                                                  :secret-key github-secret})))
        (is (= 200 (-> (mock/request :post (str "/webhook/github/" hook-id))
                       (mock/body payload)
                       (mock/header :x-hub-signature-256 (str "sha256=" signature))
                       (app)
                       :status)))))

    (testing "returns 401 if invalid security"
      (is (= 401 (-> (mock/request :post "/webhook/github/test-hook")
                     (test-app)
                     :status))))

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

(deftest api-routes
  (verify-entity-endpoints {:name "customer"
                            :base-entity {:name "test customer"}
                            :updated-entity {:name "updated customer"}
                            :creator st/save-customer})

  (let [cust-id (st/new-id)]
    (verify-entity-endpoints {:name "project"
                              :path (format "/customer/%s/project" cust-id)
                              :base-entity {:name "test project"
                                            :customer-id cust-id}
                              :updated-entity {:name "updated project"}
                              :creator st/save-project}))
  
  (let [[cust-id p-id] (repeatedly st/new-id)]
    (verify-entity-endpoints {:name "repository"
                              :path (format "/customer/%s/project/%s/repo" cust-id p-id)
                              :base-entity {:name "test repo"
                                            :customer-id cust-id
                                            :project-id p-id
                                            :url "http://test-repo"}
                              :updated-entity {:name "updated repo"}
                              :creator st/save-repo}))
  
  (verify-entity-endpoints {:name "webhook"
                            :base-entity {:customer-id "test-cust"
                                          :project-id "test-project"
                                          :repo-id "test-repo"}
                            :updated-entity {:repo-id "updated-repo"}
                            :creator st/save-webhook-details})

  (letfn [(get-params [path]
            (some-> (mock/request :get path)
                    (test-app)
                    :body
                    slurp
                    (h/parse-json)))
          (save-params [path params]
            (-> (h/json-request :put path params)
                (test-app)))
          (verify-param-endpoints [desc path]
            (testing (format "parameter endpoints at `%s/param`" desc)
              (let [params [{:name "test-param"
                             :value "test value"}]
                    path (str path "/param")]
                (testing "empty when no parameters"
                  (is (empty? (get-params path))))
                (testing "can write params"
                  (is (= 200 (:status (save-params path params)))))
                (testing "can read params"
                  (is (= params (get-params path)))))))]
    
    (verify-param-endpoints
     "/customer/:customer-id"
     (str "/customer/" (st/new-id)))

    (verify-param-endpoints
     "/customer/:customer-id/project/:project-id"
     (->> (repeatedly st/new-id)
          (interleave ["/customer/" "/project/"])
          (apply str)))

    (verify-param-endpoints
     "/customer/:customer-id/project/:project-id/repo/:repo-id"
     (->> (repeatedly st/new-id)
          (interleave ["/customer/" "/project/" "/repo/"])
          (apply str))))

  (testing "/customer/:customer-id/project/:project-id/repo/:repo-id"

    (testing "/builds"
      (h/with-memory-store st
        (let [generate-build-sid (fn []
                                   (->> (repeatedly st/new-id)
                                        (take 4)
                                        (st/->sid)))
              repo-path (fn [sid]
                          (str (->> sid
                                    (drop-last)
                                    (interleave ["/customer" "project" "repo"])
                                    (cs/join "/"))
                               "/builds"))
              build-path (fn [sid]
                           (str (repo-path sid) "/" (last sid)))
              app (make-test-app st)
              [customer-id project-id repo-id build-id :as sid] (generate-build-sid)
              path (repo-path sid)]
          (is (st/sid? (st/create-build-results st sid {:exit 0 :status :success})))
          (is (st/sid? (st/create-build-metadata st sid {:message "test meta"})))
          
          (testing "`GET` lists repo builds"
            (let [l (-> (mock/request :get path)
                        (app))
                  b (-> l
                        :body
                        slurp
                        h/parse-json)]
              (is (= 200 (:status l)))
              (is (= 1 (count b)))
              (is (= build-id (:id (first b))) "should contain build id")
              (is (= "test meta" (:message (first b))) "should contain build metadata")))

          (testing "`POST /trigger`"
            (testing "triggers new build for repo"
              (h/with-bus
                (fn [bus]
                  (let [app (sut/make-app {:storage st
                                           :event-bus bus})
                        events (atom [])
                        props [:customer-id :project-id :repo-id]
                        _ (events/register-handler bus :build/triggered (partial swap! events conj))]
                    (is (= 200 (-> (mock/request :post (str path "/trigger"))
                                   (app)
                                   :status)))
                    (is (not= :timeout (h/wait-until #(pos? (count @events)) 500)))
                    (is (= (zipmap props sid)
                           (select-keys (:account (first @events)) props)))
                    (is (some? (-> @events first :build :build-id)))))))

            (testing "adds branch and commit id query params"
              (h/with-bus
                (fn [bus]
                  (let [app (sut/make-app {:storage st
                                           :event-bus bus})
                        events (atom [])
                        _ (events/register-handler bus :build/triggered (partial swap! events conj))]
                    (is (= 200 (-> (mock/request :post (str path "/trigger?commitId=test-id&branch=test-branch"))
                                   (app)
                                   :status)))
                    (is (not= :timeout (h/wait-until #(pos? (count @events)) 500)))
                    (is (= "test-id"
                           (-> @events first :build :git :commit-id)))
                    (is (= "test-branch"
                           (-> @events first :build :git :branch)))))))

            (testing "adds `sid` to build props"
              (h/with-bus
                (fn [bus]
                  (let [app (sut/make-app {:storage st
                                           :event-bus bus})
                        events (atom [])
                        _ (events/register-handler bus :build/triggered (partial swap! events conj))]
                    (is (= 200 (-> (mock/request :post (str path "/trigger"))
                                   (app)
                                   :status)))
                    (is (not= :timeout (h/wait-until #(pos? (count @events)) 500)))
                    (let [bsid (get-in (first @events) [:build :sid])]
                      (is (= 4 (count bsid)) "expected sid to contain repo path and build id")
                      (is (= (take 3 sid) (take 3 bsid)))
                      (is (= (get-in (first @events) [:build :build-id])
                             (last bsid))))))))
            
            (testing "returns build id")

            (testing "returns 404 (not found) when repo does not exist"))

          (testing "`GET /latest`"
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

            (testing "404 when repo does not exist"))

          (testing "`GET /:build-id`"
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
                (is (nil? (:body l)))))))))))

(deftest event-stream
  (testing "'GET /events' exists"
    (is (not= 404
              (-> (mock/request :get "/events")
                  (test-app)
                  :status)))))

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
