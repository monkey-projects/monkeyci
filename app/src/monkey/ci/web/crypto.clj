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

(defn- from-crypto [req f]
  (wc/from-rt req (comp f :crypto)))

(defn encrypter [req]
  (from-crypto req :encrypter))

(defn decrypter [req]
  (from-crypto req :decrypter))

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
  (let [dek (-> (vc/generate-key)
                (bcc/bytes->b64-str))
        e (get-in rt [:crypto :encrypter])]
    {:key dek
     :enc (e dek org-id org-id)}))

(def cuid->iv v/cuid->iv)
(def encrypt vc/encrypt)
(def decrypt vc/decrypt)
