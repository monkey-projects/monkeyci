(ns monkey.ci.build.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.ci.build
             [api :as sut]
             [api-server :as server]]))

(deftest api-client
  (let [{:keys [token] :as s} (server/start-server {})
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))
        client (sut/make-client (make-url "swagger.json") token)]
    (with-open [srv (:server s)]
      
      (testing "can create api client"
        (is (some? client)))

      (testing "can invoke test endpoint"
        (is (= {:result "ok"}
               @(mc/response-for client :test {})))))))

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
