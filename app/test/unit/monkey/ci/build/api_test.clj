(ns monkey.ci.build.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [monkey.ci.build.api :as sut]))

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
  (let [s (sut/start-server {})
        make-url (fn [path]
                   (format "http://localhost:%d/%s" (:port s) path))]
    (with-open [srv (:server s)]

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

(deftest get-ip-addr
  (testing "returns ipv4 address"
    (is (re-matches #"\d+\.\d+\.\d+\.\d+"
                    (sut/get-ip-addr)))))

(deftest build-params
  (testing "invokes `params` endpoint on client"
    (let [m (fn [req]
              (when (= "/customer/test-cust/repo/test-repo/param" (:url req))
                (md/success-deferred [{:name "key"
                                       :value "value"}])))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= {"key" "value"} (sut/build-params rt))))))

(deftest download-artifact
  (testing "invokes artifact download endpoint on client"
    (let [m (fn [req]
              (when (= "/customer/test-cust/repo/test-repo/builds/test-build/artifact/test-artifact/download"
                       (:url req))
                (md/success-deferred "test artifact contents")))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= "test artifact contents"
             (sut/download-artifact rt "test-artifact"))))))
