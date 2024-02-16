(ns monkey.ci.web.auth
  "Authentication and authorization functions"
  (:require [buddy.auth :as ba]
            [buddy.auth
             [backends :as bb]
             [middleware :as bmw]]
            [buddy.core
             [codecs :as codecs]
             [keys :as bk]
             [nonce :as nonce]]
            [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [monkey.ci
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.web.common :as c]
            [ring.util.response :as rur]))

(def kid "master")

(defn generate-secret-key
  "Generates a random secret key object"
  []
  (-> (nonce/random-nonce 32)
      (codecs/bytes->hex)))

(defn sign-jwt [payload pk]
  (jwt/sign payload pk {:alg :rs256 :header {:kid kid}}))

(defn augment-payload [payload]
  ;; TODO Make token expiration configurable
  (assoc payload
         :exp (-> (jt/plus (jt/instant) (jt/hours 1))
                  (jt/to-millis-from-epoch))
         ;; TODO Make issuer and audiences configurable
         :iss "https://app.monkeyci.com"
         :aud ["https://api.monkeyci.com"]))

(defn generate-jwt
  "Signs a JWT using the keypair from the request context."
  [req payload]
  (let [pk (c/from-rt req (comp :priv :jwk))]
    (-> payload
        (augment-payload)
        (sign-jwt pk))))

(defn generate-keypair
  "Generates a new RSA keypair"
  []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)))

(defn keypair->rt [kp]
  {:pub (.getPublic kp)
   :priv (.getPrivate kp)})
(def ^:deprecated keypair->ctx keypair->rt)

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
    (log/debug "Configured JWK:" (:jwk conf))
    (when (every? some? loaded-keys)
      (zipmap [:priv :pub] loaded-keys))))

(defn make-jwk
  "Creates a JWK object from a public key that can be exposed for external 
   verification."
  [pub]
  (-> (bk/public-key->jwk pub)
      (assoc :kid kid
             ;; RS256 is currently the only algorithm supported by OCI api gateway
             :alg "RS256"
             ;; Required by oci api gateway
             :use "sig")))

(def ^:deprecated ctx->pub-key (comp :pub :jwk))
(def rt->pub-key (comp :pub :jwk))

(defn jwks
  "JWKS endpoint handler"
  [req]
  (if-let [k (c/from-rt req rt->pub-key)]
    (rur/response {:keys [(make-jwk k)]})
    (rur/not-found {:message "No JWKS configured"})))

(defn- lookup-user [{:keys [storage]} {:keys [sub]}]
  (when sub
    (let [id (->> (seq (.split sub "/"))
                  (take 2))]
      (when (= 2 (count id))
        (log/debug "Looking up user with id" id)
        (some-> (st/find-user storage id)
                (update :customers set))))))

(defn secure-ring-app
  "Wraps the ring handler so it verifies the JWT authorization header"
  [app rt]
  (let [pk (rt->pub-key rt)
        backend (bb/jws {:secret pk
                         :token-name "Bearer"
                         :options {:alg :rs256}
                         :authfn (partial lookup-user rt)})]
    (-> app
        (bmw/wrap-authentication backend))))

(defn- check-authorization! [req]
  (when-let [cid (get-in req [:parameters :path :customer-id])]
    (when-not (and (ba/authenticated? req)
                   (contains? (get-in req [:identity :customers]) cid))
      (throw (ex-info "User does not have access to this customer"
                      {:type :auth/unauthorized
                       :customer-id cid})))))

(defn customer-authorization
  "Middleware that verifies the identity token to check if the user has
   access to the given customer."
  [h]
  (fn [req]
    (check-authorization! req)
    (h req)))

(defmethod rt/setup-runtime :jwk [conf _]
  (config->keypair conf))
