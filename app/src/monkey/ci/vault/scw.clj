(ns monkey.ci.vault.scw
  "Scaleway implementation of crypto functionality"
  (:require [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.scw.core :as scw]))

(defn make-client
  "Creates a Scaleway api client using given config"
  [config]
  (assoc config :ctx (scw/key-mgr-ctx config)))

(defn- fail-on-error [{:keys [status] :as resp}]
  (if (>= status 400)
    (md/error-deferred (ex-info "Got error response from Scaleway API" {:response resp}))
    resp))

(defn- invoke-scw [{:keys [ctx] :as client} ep opts]
  (md/chain
   (mc/response-for ctx ep
                    (-> client
                        (select-keys [:region :key-id])
                        (merge opts)))
   fail-on-error
   :body))

(defn generate-dek
  "Generates a new AES256 data encryption key (DEK).  Returns a deferred that 
   contains the generated key and it's encrypted value (for storage).  The DEK 
   should be used to encrypt data, or other encryption keys.  The key should 
   be decoded from base64 before passing it to `common/encrypt`."
  [client]
  (md/chain
   (invoke-scw client :generate-data-key {:without-plaintext false})
   (fn [{:keys [ciphertext plaintext]}]
     {:enc ciphertext
      :key plaintext})))

(defn decrypt-dek
  "Given an encrypted DEK, decrypts it using the configured Scaleway encryption key
   and returns the unencrypted value (base64 encoded).  This value can then be passed
   to `common/encrypt` and `common/decrypt`, in combination with an initialization
   vector."
  [client dek]
  (md/chain
   (invoke-scw client :decrypt {:ciphertext dek})
   :plaintext))
