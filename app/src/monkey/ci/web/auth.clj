(ns monkey.ci.web.auth
  "Authentication and authorization functions"
  (:require [buddy.core
             [codecs :as codecs]
             [keys :as bk]
             [nonce :as nonce]]
            [buddy.sign.jwt :as jwt]
            [java-time.api :as jt]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(defn generate-secret-key
  "Generates a random secret key object"
  []
  (-> (nonce/random-nonce 32)
      (codecs/bytes->hex)))

(defn generate-jwt
  "Signs a JWT using the keypair from the request context."
  [req payload]
  (let [pk (c/from-context req (comp :priv :jwk))]
    (-> payload
        (assoc :exp (-> (jt/plus (jt/instant) (jt/hours 1))
                        (jt/to-millis-from-epoch)))
        (jwt/sign pk {:alg :rs256}))))

(defn generate-keypair
  "Generates a new RSA keypair"
  []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)))

(defn keypair->ctx [kp]
  {:pub (.getPublic kp)
   :priv (.getPrivate kp)})

(defn make-jwk
  "Creates a JWK object from a public key that can be exposed for external 
   verification."
  [pub]
  (-> (bk/public-key->jwk pub)
      (assoc :kid "master"
             :alg "RS256")))

(defn jwks
  "JWKS endpoint handler"
  [req]
  (let [k (c/from-context req :jwk)]
    (rur/response {:keys [(make-jwk (:pub k))]})))
