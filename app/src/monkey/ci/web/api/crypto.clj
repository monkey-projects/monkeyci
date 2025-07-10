(ns monkey.ci.web.api.crypto
  "Cryptographic endpoint handlers"
  (:require [monkey.ci.web
             [common :as c]
             [crypto :as cr]]
            [ring.util.response :as rur]))

(defn decrypt-key
  "Decrypts an encrypted key using the DEK associated with the org"
  [req]
  (let [d (cr/decrypter req)
        org-id (c/org-id req)]
    (rur/response {:key (d (-> (c/body req) :enc) org-id org-id)})))
