(ns monkey.ci.test.web.handler-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.handler :as sut]
            [org.httpkit.server :as http]
            [ring.mock.request :as mock]))

(deftest app
  (testing "is a fn"
    (is (fn? sut/app))))

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

(deftest http-routes
  (testing "health check at `/health`"
    (is (= 200 (-> (mock/request :get "/health")
                   (sut/app)
                   :status))))

  (testing "404 when not found"
    (is (= 404 (-> (mock/request :get "/nonexisting")
                   (sut/app)
                   :status))))

  (testing "`POST /webhook/github` accepts"
    (is (= 200 (-> (mock/request :post "/webhook/github")
                   (sut/app)
                   :status)))))
