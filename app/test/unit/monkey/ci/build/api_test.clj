(ns monkey.ci.build.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.ci.build
             [api :as sut]
             [api-server :as server]]
            [monkey.ci.events.build-api :as events]
            [monkey.ci.helpers :as h]
            [monkey.ci.protocols :as p]
            [monkey.ci.test.api-server :as tas]))

(deftest api-client
  (let [config (tas/test-config)
        {:keys [token] :as s} (server/start-server config)
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))
        client (sut/make-client base-url token)]
    (with-open [srv (:server s)]
      
      (testing "can create api client"
        (is (fn? client)))

      (testing "can invoke test endpoint"
        (is (= {:result "ok"}
               (:body @(client (sut/as-edn {:path "/test"
                                            :method :get}))))))

      (testing "can post events"
        (let [ep (events/make-event-poster client)
              recv (-> config :events :recv)
              event {:type ::test-event :message "test event"}]
          (is (some? (p/post-events ep event)))
          (is (not= :timeout (h/wait-until #(not-empty @recv) 1000)))
          (is (= event (-> (first @recv)
                           (select-keys (keys event))))))))))

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
