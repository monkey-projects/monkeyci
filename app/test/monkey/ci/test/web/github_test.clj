(ns monkey.ci.test.web.github-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [events :as events]
             [storage :as st]]
            [monkey.ci.web.github :as sut]
            [monkey.ci.test.helpers :as h]
            [ring.mock.request :as mock]))

(deftest valid-security?
  (testing "false if nil"
    (is (not (true? (sut/valid-security? nil)))))

  (testing "true if valid"
    ;; Github provided values for testing
    (is (true? (sut/valid-security?
                {:secret "It's a Secret to Everybody"
                 :payload "Hello, World!"
                 :x-hub-signature "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"})))))

(deftest extract-signature
  (testing "nil if nil input"
    (is (nil? (sut/extract-signature nil))))

  (testing "returns value of the sha256 key"
    (is (= "test-value" (sut/extract-signature "sha256=test-value"))))

  (testing "nil if key is not sha256"
    (is (nil? (sut/extract-signature "key=value")))))

(deftest generate-secret-key
  (testing "generates random string"
    (is (string? (sut/generate-secret-key)))))

(deftest webhook
  (testing "posts event"
    (let [bus (events/make-bus)
          ctx {:reitit.core/match {:data {:monkey.ci.web.handler/context {:event-bus bus}}}}
          req (-> (mock/request :post "/webhook/github")
                  (mock/body "test body")
                  (merge ctx))
          recv (atom [])
          _ (events/register-handler bus :webhook/github (partial swap! recv conj))]
      (is (some? (sut/webhook req)))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) 500))))))

(deftest prepare-build
  (testing "creates metadata file for customer/project/repo"
    (h/with-memory-store s
      (is (some? (st/create-webhook-details s {:id "test-webhook"
                                               :customer-id "test-customer"
                                               :project-id "test-project"
                                               :repo-id "test-repo"})))
      (let [r (sut/prepare-build {:storage s}
                                 {:id "test-webhook"
                                  :payload {}})]
        (is (some? r))
        (is (st/obj-exists? s (format "builds/test-customer/test-project/test-repo/%s/metadata.md"
                                      (get-in r [:build :build-id]))))))))
