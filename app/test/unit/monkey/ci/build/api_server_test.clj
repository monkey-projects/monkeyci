(ns monkey.ci.build.api-server-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [monkey.ci.build.api-server :as sut]
            [monkey.ci
             [aleph-test :as at]
             [helpers :as h]]
            [monkey.ci.storage :as st]
            [ring.mock.request :as mock]))

(deftest start-server
  (testing "can start tcp server"
    (let [s (sut/start-server {})]
      (is (map? s))
      (is (some? (:server s)))
      (is (string? (:token s)))
      (is (pos? (:port s)))
      (is (nil? (.close (:server s))))))

  ;; Not supported by netty 4
  #_(testing "can start uds server"
    (let [s (sut/start-server {:type :local})]
      (is (map? s))
      (is (some? (:server s)))
      (is (string? (:token s)))
      (is (string? (:socket s)))
      (is (nil? (.close (:server s)))))))

(deftest api-server
  (let [{:keys [token] :as s} (sut/start-server {})
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))
        make-req (fn [opts]
                   (-> {:url (make-url (:path opts))
                        :headers {"Authorization" (str "Bearer " token)}}
                       (merge opts)
                       (dissoc :path)))]
    (with-open [srv (:server s)]

      (testing "provides swagger"
        (is (= 200 (-> (make-req {:path "swagger.json"
                                  :method :get})
                       (http/request)
                       deref
                       :status))))

      (testing "returns 401 if no token given"
        (is (= 401 (-> {:url (make-url "swagger.json")
                        :method :get
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))
      
      (testing "returns 401 if wrong token given"
        (is (= 401 (-> {:url (make-url "swagger.json")
                        :method :get
                        :headers {"Authorization" "Bearer wrong token"}
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status)))))))

(defn- ->req
  "Creates a request object from given build context"
  [ctx]
  (h/->match-data {sut/context ctx}))

(deftest get-params
  (let [repo (h/gen-repo)
        cust (-> (h/gen-cust)
                 (assoc :repos {(:id repo) repo}))
        param-values [{:name "test-param"
                       :value "test value"}]
        params [{:customer-id (:id cust)
                 :parameters param-values}]
        build {:customer-id (:id cust)
               :repo-id (:id repo)}]
    
    (testing "fetches params from local db if configured"
      (h/with-memory-store st
        (let [req (->req {:storage st
                          :build build})]
          (is (some? (st/save-params st (:id cust) params)))
          (is (= param-values
                 (:body @(sut/get-params req)))))))

    (testing "retrieves from remote api if no db"
      ;; Requests look differend because of applied middleware
      (at/with-fake-http [{:request-url (format "http://test-api/customer/%s/repo/%s/param" (:id cust) (:id repo))
                           :request-method :get}
                          {:status 200
                           :body (pr-str param-values)
                           :headers {"Content-Type" "application/edn"}}]
        (is (= param-values
               (-> {:api {:url "http://test-api"}
                    :build build}
                   (->req)
                   (sut/get-params)
                   deref
                   :body)))))))

(deftest get-ip-addr
  (testing "returns ipv4 address"
    (is (re-matches #"\d+\.\d+\.\d+\.\d+"
                    (sut/get-ip-addr)))))

(deftest api-server-routes
  (let [token (sut/generate-token)
        app (sut/make-app {:token token
                           :events (h/fake-events)
                           :storage (st/make-memory-storage)})
        auth (fn [req]
               (mock/header req "Authorization" (str "Bearer " token)))]
    (testing "`/test` returns ok"
      (is (= 200 (-> (mock/request :get "/test")
                     (auth)
                     (app)
                     :status))))

    (testing "`GET /params` retrieves build params"
      (is (= 200 (-> (mock/request :get "/params")
                     (auth)
                     (app)
                     deref
                     :status))))

    (testing "`POST /event` dispatches event"
      (is (= 202 (-> (mock/request :post "/event")
                     (mock/body (pr-str {:type ::test-event}))
                     (mock/content-type "application/edn")
                     (auth)
                     (app)
                     :status))))))
