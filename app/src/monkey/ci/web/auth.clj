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

(def kid "master")

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
        (jwt/sign pk {:alg :rs256 :header {:kid kid}}))))

(defn generate-keypair
  "Generates a new RSA keypair"
  []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)))

(defn keypair->ctx [kp]
  {:pub (.getPublic kp)
   :priv (.getPrivate kp)})

(defn config->keypair
  "Loads private and public keys from the app config, returns a map that can be
   used in the context `:jwk`."
  [conf]
  (let [m {:private-key bk/private-key
           :public-key bk/public-key}
        loaded-keys (mapv (fn [[k f]]
                            (when-let [v (get-in conf [:jwk k])]
                              (f v)))
                          m)]
    (when (every? some? loaded-keys)
      (zipmap [:priv :pub] loaded-keys))))

(defn make-jwk
  "Creates a JWK object from a public key that can be exposed for external 
   verification."
  [pub]
  (-> (bk/public-key->jwk pub)
      (assoc :kid kid
             ;; RS256 is currently the only algorithm supported by OCI api gateway
             :alg "RS256")))

(defn jwks
  "JWKS endpoint handler"
  [req]
  (if-let [k (c/from-context req (comp :pub :jwk))]
    (rur/response {:keys [(make-jwk k)]})
    (rur/not-found {:message "No JWKS configured"})))
