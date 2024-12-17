(ns monkey.ci.web.api.ssh-keys-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.web.api.ssh-keys :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(deftest get-customer-ssh-keys
  (testing "decrypts private key using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault (h/fake-vault (constantly "encrypted")
                                                                (constantly "decrypted"))))
          cust (h/gen-cust)
          cust-id (:id cust)
          _ (st/save-customer st cust)
          ssh-key (h/gen-ssh-key)
          _ (st/save-ssh-keys st cust-id [ssh-key])
          res (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path :customer-id] cust-id)
                  (sut/get-customer-ssh-keys))]
      (is (= 200 (:status res)))
      (is (= "decrypted" (-> res
                             :body
                             first
                             :private-key))))))

(deftest get-repo-ssh-keys
  (testing "decrypts private key using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault (h/fake-vault (constantly "encrypted")
                                                                (constantly "decrypted"))))
          repo (h/gen-repo)
          cust (-> (h/gen-cust)
                   (assoc :repos {(:id repo) repo}))
          cust-id (:id cust)
          _ (st/save-customer st cust)
          ssh-key (-> (h/gen-ssh-key)
                      (dissoc :label-filters))
          _ (st/save-ssh-keys st cust-id [ssh-key])
          res (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path] {:customer-id cust-id
                                                 :repo-id (:id repo)})
                  (sut/get-repo-ssh-keys))]
      (is (= 200 (:status res)))
      (is (= "decrypted" (-> res
                             :body
                             first))))))

(deftest update-ssh-key
  (testing "encrypts private keys using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-vault (h/fake-vault (constantly "encrypted")
                                                                (constantly "decrypted"))))
          cust (h/gen-cust)
          cust-id (:id cust)
          _ (st/save-customer st cust)
          res (-> rt
                  (h/->req)
                  (assoc
                   :parameters
                   {:path
                    {:customer-id cust-id}
                    :body
                    [{:private-key "test-pk"
                      :public-key "test-pub"}]})
                  (sut/update-ssh-keys))
          key-id (-> res :body first :id)]
      (is (= 200 (:status res)))
      (is (= "encrypted" (-> (st/find-ssh-keys st cust-id)
                             first
                             :private-key))))))
