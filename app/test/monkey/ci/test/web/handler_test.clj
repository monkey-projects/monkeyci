(ns monkey.ci.test.web.handler-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
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

(deftest stop-server
  (with-redefs [http/server-stop! (constantly ::stopped)]
    (testing "stops the server"
      (is (= ::stopped (sut/stop-server :dummy-server))))

    (testing "does nothing when server is `nil`"
      (is (nil? (sut/stop-server nil))))))

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
