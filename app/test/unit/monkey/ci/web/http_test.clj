(ns monkey.ci.web.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph
             [http :as aleph]
             [netty :as netty]]
            [monkey.ci.test.helpers :as h]
            [monkey.ci.web.http :as sut]))

(deftest start-server
  (with-redefs [aleph/start-server (fn [h opts]
                                     {:handler h
                                      :opts opts})]
    
    (testing "starts http server with default port"
      (is (number? (-> (sut/start-server {} (constantly nil))
                       :opts
                       :port))))

    (testing "passes args as opts"
      (is (= 1234 (-> (sut/start-server {:port 1234}
                                        (constantly nil))
                      :opts
                      :port))))))

(deftest stop-server
  (testing "stops the server"
    (let [stopped? (atom false)
          s (h/->FakeServer stopped?)]
      (is (nil? (sut/stop-server s)))
      (is (true? @stopped?))))

  (testing "does nothing when server is `nil`"
    (is (nil? (sut/stop-server nil)))))

(deftest on-server-close
  (testing "waits until netty server closes"
    (with-redefs [netty/wait-for-close (fn [s]
                                         (if (= ::server s)
                                           ::closed
                                           ::invalid-arg))]
      (is (= ::closed @(sut/on-server-close (sut/map->HttpServer {:server ::server})))))))
