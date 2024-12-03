(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:require [monkey.ci.protocols :as p]
            [monkey.oci.vault :as vault]))

(defn- check-error! [{:keys [status] :as resp}]
  (when (or (nil? status) (>= status 400))
    (throw (ex-info "Vault error" resp)))
  resp)

(defrecord OciVault [client config]
  p/Vault
  (encrypt [_ txt]
    (-> (vault/encrypt client (assoc config :plaintext (vault/->b64 txt)))
        (deref)
        check-error!
        :body
        :ciphertext))

  (decrypt [_ obj]
    ;; TODO May become slow for lots of values
    (-> (vault/decrypt client (assoc config :ciphertext obj))
        (deref)
        check-error!
        :body
        :plaintext
        vault/b64->
        (String.))))

(defn make-oci-vault [config]
  (->OciVault (vault/make-crypto-client config)
              (select-keys config [:key-id :key-version-id])))

(defmulti make-vault :type)

(defmethod make-vault :oci [config]
  (make-oci-vault config))
