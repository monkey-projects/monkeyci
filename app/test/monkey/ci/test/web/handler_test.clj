(ns monkey.ci.test.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [monkey.ci
             [events :as events]
             [storage :as st]]
            [monkey.ci.web.handler :as sut]
            [monkey.ci.test.helpers :refer [try-take] :as h]
            [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]))

(deftest make-app
  (testing "creates a fn"
    (is (fn? (sut/make-app {})))))

(def github-secret "github-secret")

(defn- make-test-app
  ([storage]
   (sut/make-app
    {:github
     {:secret github-secret}
     :event-bus (events/make-bus)
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
                          (codecs/bytes->hex))]
        (is (= 200 (-> (mock/request :post "/webhook/github/test-hook")
                       (mock/body payload)
                       (mock/header :x-hub-signature-256 (str "sha256=" signature))
                       (test-app)
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
            (is (= 200 (-> (mock/request :post "/webhook/github/test-hook")
                           (dev-app)
                           :status)))
            (is (= "test-hook" (h/try-take l 200 :timeout)))))))))

(deftest api-routes
  (let [st (st/make-memory-storage)
        app (make-test-app st)]
    
    (testing "`/customer/:customer-id`"
      (testing "`GET` retrieves customer info"
        (let [id (st/new-id)
              cust {:id id
                    :name "Test customer"}
              _ (st/create-customer st cust)
              r (-> (mock/request :get (str "/customer/" id))
                    (mock/header :accept "application/json")
                    (app))]
          (is (= 200 (:status r)))
          (is (= cust (h/parse-json (slurp (:body r))))))))))

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
                              :body))))))
