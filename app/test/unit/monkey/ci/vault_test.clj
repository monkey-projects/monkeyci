(ns monkey.ci.vault-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [protocols :as p]
             [utils :as u]
             [vault :as sut]]
            [monkey.oci.vault :as v]))

(deftest oci-vault
  (let [config {:vault-id "test-vault"
                :crypto-endpoint "http://test"}
        vault (sut/make-oci-vault config)]
    (is (satisfies? p/Vault vault) "is a vault")
    
    (testing "encrypts using vault crypto endpoint"
      (with-redefs [v/encrypt (constantly (future {:status 200
                                                   :body {:ciphertext "encrypted"}}))]
        (is (= "encrypted" (p/encrypt vault "test-text")))))

    (testing "decrypts using vault crypto endpoint"
      (with-redefs [v/decrypt (constantly (future {:status 200
                                                   :body {:plaintext (u/->base64 "decrypted")}}))]
        (is (= "decrypted" (p/decrypt vault "test-enc")))))

    (testing "throws on backend error"
      (with-redefs [v/encrypt (constantly (future {:status 400}))]
        (is (thrown? Exception (p/encrypt vault "test")))))))
