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
          ctx {:reitit.core/match {:data {:monkey.ci.web.common/context {:event-bus bus}}}}
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
      (let [wh {:id "test-webhook"
                :customer-id "test-customer"
                :project-id "test-project"
                :repo-id "test-repo"}]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/prepare-build {:storage s}
                                   {:id "test-webhook"
                                    :payload {}})]
          (is (some? r))
          (is (st/obj-exists? s (-> wh
                                    (select-keys [:customer-id :project-id :repo-id])
                                    (assoc :build-id (get-in r [:build :build-id]))
                                    (st/build-metadata-sid))))))))

  (testing "metadata contains commit timestamp and message"
    (h/with-memory-store s
      (let [wh {:id "test-webhook"
                :customer-id "test-customer"
                :project-id "test-project"
                :repo-id "test-repo"}]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/prepare-build {:storage s}
                                   {:id "test-webhook"
                                    :payload {:head-commit {:message "test message"
                                                            :timestamp "2023-10-10"}}})
              id (get-in r [:build :sid])
              md (st/find-build-metadata s id)]
          (is (st/sid? id))
          (is (some? md))
          (is (= "test message" (:message md)))
          (is (= "2023-10-10" (:timestamp md)))))))
  
  (testing "`nil` if no configured webhook found"
    (h/with-memory-store s
      (is (nil? (sut/prepare-build {:storage s}
                                   {:id "test-webhook"
                                    :payload {}})))))

  (testing "uses clone url for public repos"
    (h/with-memory-store s
      (let [wh {:id "test-webhook"
                :customer-id "test-customer"
                :project-id "test-project"
                :repo-id "test-repo"}]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/prepare-build {:storage s}
                                   {:id "test-webhook"
                                    :payload {:repository {:ssh-url "ssh-url"
                                                           :clone-url "clone-url"}}})]
          (is (= "clone-url" (get-in r [:build :git :url])))))))

  (testing "uses ssh url if repo is private"
    (h/with-memory-store s
      (let [wh {:id "test-webhook"
                :customer-id "test-customer"
                :project-id "test-project"
                :repo-id "test-repo"}]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/prepare-build {:storage s}
                                   {:id "test-webhook"
                                    :payload {:repository {:ssh-url "ssh-url"
                                                           :clone-url "clone-url"
                                                           :private true}}})]
          (is (= "ssh-url" (get-in r [:build :git :url]))))))))
