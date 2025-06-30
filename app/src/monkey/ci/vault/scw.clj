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

(defn generate-dek
  "Generates a new AES256 data encryption key.  Returns a deferred that contains
   the generated key and it's encrypted value (for storage).  The DEK should be
   used to encrypt data, or other encryption keys.  The key should be decoded
   from base64 before passing it to `common/encrypt`."
  [{:keys [ctx] :as client}]
  (md/chain
   (mc/response-for ctx :generate-data-key
                    (-> client
                        (select-keys [:region :key-id])
                        (assoc :without-plaintext false)))
   fail-on-error
   :body
   (fn [{:keys [ciphertext plaintext]}]
     {:enc ciphertext
      :key plaintext})))
