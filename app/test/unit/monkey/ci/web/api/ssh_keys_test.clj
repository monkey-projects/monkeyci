(ns monkey.ci.web.api.ssh-keys-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.ssh-keys :as sut]))

(deftest get-org-ssh-keys
  (testing "decrypts private key using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-decrypter (constantly "decrypted")))
          org (h/gen-org)
          org-id (:id org)
          _ (st/save-org st org)
          ssh-key (h/gen-ssh-key)
          _ (st/save-ssh-keys st org-id [ssh-key])
          res (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path :org-id] org-id)
                  (sut/get-org-ssh-keys))]
      (is (= 200 (:status res)))
      (is (= "decrypted" (-> res
                             :body
                             first
                             :private-key))))))

(deftest get-repo-ssh-keys
  (testing "decrypts private key using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-decrypter (constantly "decrypted")))
          repo (h/gen-repo)
          org (-> (h/gen-org)
                  (assoc :repos {(:id repo) repo}))
          org-id (:id org)
          _ (st/save-org st org)
          ssh-key (-> (h/gen-ssh-key)
                      (dissoc :label-filters))
          _ (st/save-ssh-keys st org-id [ssh-key])
          res (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path] {:org-id org-id
                                                 :repo-id (:id repo)})
                  (sut/get-repo-ssh-keys))]
      (is (= 200 (:status res)))
      (is (= "decrypted" (-> res
                             :body
                             first))))))

(deftest update-ssh-keys
  (testing "encrypts private keys using vault"
    (let [{st :storage :as rt} (-> (trt/test-runtime)
                                   (trt/set-encrypter (fn [_ _ cuid]
                                                        (str "encrypted with " cuid))))
          org (h/gen-org)
          org-id (:id org)
          _ (st/save-org st org)
          res (-> rt
                  (h/->req)
                  (assoc
                   :parameters
                   {:path
                    {:org-id org-id}
                    :body
                    [{:private-key "test-pk"
                      :public-key "test-pub"}]})
                  (sut/update-ssh-keys))
          key-id (-> res :body first :id)]
      (is (= 200 (:status res)))
      (is (= (str "encrypted with " key-id)
             (-> (st/find-ssh-keys st org-id)
                 first
                 :private-key))))))
