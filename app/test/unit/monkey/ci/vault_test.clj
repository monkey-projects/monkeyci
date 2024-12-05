(ns monkey.ci.vault-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.nonce :as nonce]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [protocols :as p]
             [utils :as u]
             [vault :as sut]]
            [monkey.oci.vault :as v]))

(deftest oci-vault
  (let [config {:compartment-id "test-compartment"
                :vault-id "test-vault"
                :key-id "test-key"
                :secret-name "test-secret"}
        iv (sut/generate-iv)
        vault (sut/make-oci-vault config)]
    (is (satisfies? p/Vault vault) "is a vault")
    (is (some? iv))

    (testing "`start`"
      (testing "fetches encryption key from vault as byte[]"
        (with-redefs [v/get-secret-bundle-by-name
                      (fn [_ opts]
                        (if (= (select-keys config [:vault-id :secret-name])
                               opts)
                          {:secret-bundle-content
                           {:content-type "BASE64"
                            :content (u/->base64 "test-encryption-key")}}
                          (throw (ex-info "Invalid arguments" opts))))]
          (is (= "test-encryption-key"
                 (-> vault
                     (co/start)
                     :encryption-key
                     (String.))))))

      (testing "generates and stores new encryption key if not found"
        (let [new-key "new-encryption-key"
              enc-key (u/->base64 new-key)]
          (with-redefs [v/get-secret-bundle-by-name
                        (fn [_ _]
                          (throw (ex-info "Not found" {:status 404})))
                        v/generate-data-encryption-key
                        (fn [_ opts]
                          (if (and (= (:key-id config) (:key-id opts))
                                   (= "AES" (get-in opts [:key-shape :algorithm])))
                            {:plaintext enc-key}
                            (throw (ex-info "Invalid arguments" opts))))
                        v/create-secret
                        (fn [_ opts]
                          (if (and (= config (select-keys opts (keys config)))
                                   (= enc-key (get-in opts [:secret-content :content])))
                            {:id "new-secret-id"}
                            (throw (ex-info "Invalid secret details" opts))))]
            (is (= new-key
                   (-> vault
                       (co/start)
                       :encryption-key
                       (String.))))))))
    
    (testing "encrypts and decrypts using fetched key"
      (let [txt "test message"
            enc-key (nonce/random-nonce 32)
            vault (assoc vault :encryption-key enc-key)
            enc (p/encrypt vault iv txt)]
        (is (string? enc))
        (is (= txt (p/decrypt vault iv enc)))))))
