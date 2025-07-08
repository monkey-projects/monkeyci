(ns monkey.ci.web.crypto
  "Cryptographic functions, for encrypting/decrypting sensitive data"
  (:require [buddy.core.codecs :as bcc]
            [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.vault.common :as vc]
            [monkey.ci.web.common :as wc]))

(defn crypto-iv
  "Looks up crypto initialization vector for the org associated with the
   request.  If no crypto record is found, one is generated."
  ([st org-id]
   (if-let [crypto (st/find-crypto st org-id)]
     (:iv crypto)
     (let [iv (v/generate-iv)]
       (log/debug "No crypto record found for org" org-id ", generating a new one")
       (when (st/save-crypto st {:org-id org-id
                                 :iv iv})
         iv))))
  ([req]
   (let [org-id (wc/org-id req)
         st (wc/req->storage req)]
     (crypto-iv st org-id))))

(defn- with-vault-and-iv [req f]
  (let [iv (delay (crypto-iv req))
        v (wc/req->vault req)]
    (fn [x]
      ;; Deprecated
      (log/warn "Calling deprecated crypto fn that uses old-style iv")
      (f v @iv x))))

(defn- from-crypto [req f]
  (wc/from-rt req (comp f :crypto)))

(defn encrypter [req]
  (or (from-crypto req :encrypter)
      ;; For backwards compatibility
      (with-vault-and-iv req p/encrypt)))

(defn decrypter [req]
  (or (from-crypto req :decrypter)
      ;; For backwards compatibility
      (with-vault-and-iv req p/decrypt)))

(defn dek?
  "Checks if the argument is a valid data encryption key"
  [x]
  (and (instance? byte/1 x)
       (= v/dek-size (count x))))

(defn b64-dek?
  "Checks if argument is a base64-encoded DEK"
  [x]
  (some-> x
          (bcc/b64->bytes)
          (dek?)))

(def dek-generator #(from-crypto % :dek-generator))

(defn generate-dek
  "Generates a new DEK using request context for given org id.  Returns both 
   the encrypted and unencrypted key."
  [req org-id]
  ((dek-generator req) org-id))

(defn generate-build-dek
  "Generates a new build-specific DEK, encrypted using the DEK of the org."
  [rt org-id]
  (let [dek (-> (v/generate-key)
                (bcc/bytes->b64-str))
        e (get-in rt [:crypto :encrypter])]
    {:key dek
     :enc (e dek org-id org-id)}))

(def cuid->iv v/cuid->iv)
(def encrypt vc/encrypt)
(def decrypt vc/decrypt)
