(ns monkey.ci.web.crypto
  "Cryptographic functions, for encrypting/decrypting sensitive data"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.web.common :as wc]))

(defn crypto-details
  "Retrieves crypto information for the org in the request.  If no crypto record
   is found, one is created with a new initialization vector (iv) and a master
   data encryption key (DEK).  Returns a map containing an iv and an unencrypted
   DEK."
  [req])

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
  ;; TODO Add support for DEKs
  (let [iv (crypto-iv req)
        v (wc/req->vault req)]
    (partial f v iv)))

(defn encrypter
  "Returns an encrypter fn, using the information retrieved from the request context.
   It's a 1-arity function that encrypts its argument."
  [req]
  (with-vault-and-iv req p/encrypt))

(defn decrypter
  "Similar to `encrypter`, but for decryption."
  [req]
  (with-vault-and-iv req p/decrypt))
