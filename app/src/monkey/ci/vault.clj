(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:require [monkey.ci.protocols :as p]
            [monkey.oci.vault :as vault]
            [monkey.oci.vault.b64 :as b]))

(defrecord OciVault [client config]
  p/Vault
  (encrypt [_ txt]
    (-> (vault/encrypt client (assoc config :plaintext (b/->b64 txt)))
        :ciphertext))

  (decrypt [_ obj]
    ;; TODO May become slow for lots of values
    (-> (vault/decrypt client (assoc config :ciphertext obj))
        :plaintext
        b/b64->str)))

(defn make-oci-vault [config]
  (->OciVault (vault/make-client config)
              (select-keys config [:key-id :key-version-id])))

(defmulti make-vault :type)

(defmethod make-vault :oci [config]
  (make-oci-vault config))
