(ns monkey.ci.test.web.handler-test
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [monkey.ci.web.handler :as sut]
            [org.httpkit.server :as http]
            [reitit.ring :as ring]
            [ring.mock.request :as mock]))

(deftest make-app
  (testing "creates a fn"
    (is (fn? (sut/make-app {})))))

(def github-secret "github-secret")

(defn- make-test-app []
  (sut/make-app
   {:github
    {:secret github-secret}}))

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
      (is (= 1234 (-> (sut/start-server {:port 1234})
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
                   :status))))

  (testing "`POST /webhook/github`"
    (testing "accepts with valid security header"
      (let [payload "test body"
            signature (-> (mac/hash payload {:key github-secret
                                             :alg :hmac+sha256})
                          (codecs/bytes->hex))]
        (is (= 200 (-> (mock/request :post "/webhook/github")
                       (mock/body payload)
                       (mock/header :x-hub-signature-256 (str "sha256=" signature))
                       (test-app)
                       :status)))))

    (testing "returns 401 if invalid security"
      (is (= 401 (-> (mock/request :post "/webhook/github")
                     (test-app)
                     :status))))))

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

(defn- try-take [ch timeout timeout-val]
  (let [t (ca/timeout timeout)
        [v c] (ca/alts!! [ch t])]
    (if (= t c) timeout-val v)))

(deftest wait-until-stopped
  (testing "blocks until the server has stopped"
    (let [ch (ca/chan)]
      (try
        (with-redefs [http/server-status (fn [_]
                                           (try-take ch 1000 :running))]
          (let [t (ca/thread (sut/wait-until-stopped :test-server))]
            (is (= :timeout (try-take t 200 :timeout)))
            ;; Send stop command to the channel
            (ca/>!! ch :stopped)
            ;; Thread should have stopped by now
            (is (nil? (try-take t 200 :timeout)))))
        (finally
          (ca/close! ch))))))
