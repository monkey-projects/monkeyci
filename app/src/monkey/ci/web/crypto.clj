(ns monkey.ci.web.crypto
  "Cryptographic functions, for encrypting/decrypting sensitive data"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
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

(defn encrypter [req]
  (or (wc/from-rt req :encrypter)
      ;; For backwards compatibility
      (with-vault-and-iv req p/encrypt)))

(defn decrypter [req]
  (or (wc/from-rt req :decrypter)
      ;; For backwards compatibility
      (with-vault-and-iv req p/decrypt)))

(defn dek?
  "Checks if the argument is a valid data encryption key"
  [x]
  (and (instance? byte/1 x)
       (= v/dek-size (count x))))

(def dek-generator #(wc/from-rt % :dek-generator))

(defn generate-dek
  "Generates a new DEK using request context for given org id.  Returns both 
   the encrypted and unencrypted key."
  [req org-id]
  ((dek-generator req) org-id))

#_(def dek-provider #(wc/from-rt % :dek-provider))

#_(defn get-dek
  "Decrypts the given encrypted data encryption key using the key decrypter from the
   request.  Returns the unencrypted key, which can be passed to `encrypt` or `decrypt`
   along with an initialization vector (iv)."
  [req dek]
  ((dek-decrypter req) dek))

;; (defn encrypter
;;   "Returns an encrypter fn, using the information retrieved from the request context.
;;    It's a multi-arity function that encrypts its argument.  The 1-arity variant loads
;;    the iv from the crypto record, but this is deprecated.  The 2-arity variant takes
;;    a cuid, which is used to calculate the iv."
;;   [req]
;;   ;; Old style, deprecated
;;   (with-vault-and-iv req p/encrypt))

;; (defn decrypter
;;   "Similar to `encrypter`, but for decryption."
;;   [req]
;;   ;; Old style, deprecated
;;   (with-vault-and-iv req p/decrypt))
