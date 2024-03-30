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
             [storage :as st]
             [utils :as u]]
            [monkey.ci.web.common :as c]
            [ring.middleware.params :as rmp]
            [ring.util.response :as rur]))

(def kid "master")
(def role-user "user")
(def role-build "build")

(defn user-token
  "Creates token contents for an authenticated user"
  [user-sid]
  {:role role-user
   :sub (u/serialize-sid user-sid)})

(defn build-token
  "Creates token contents for a build, to be used by a build script."
  [build-sid]
  {:role role-build
   :sub (u/serialize-sid build-sid)})

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

(defn generate-jwt-from-rt
  "Generates a JWT from the private key in the runtime"
  [rt payload]
  (when-let [pk (get-in rt [:jwk :priv])]
    (-> payload
        (augment-payload)
        (sign-jwt pk))))

(defn generate-jwt
  "Signs a JWT using the keypair from the request context."
  [req payload]
  (-> req
      (c/req->rt)
      (generate-jwt-from-rt payload)))

(defn generate-keypair
  "Generates a new RSA keypair"
  []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)))

(defn keypair->rt [kp]
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

(def rt->pub-key (comp :pub :jwk))

(defn jwks
  "JWKS endpoint handler"
  [req]
  (if-let [k (c/from-rt req rt->pub-key)]
    (rur/response {:keys [(make-jwk k)]})
    (rur/not-found {:message "No JWKS configured"})))

(defmulti resolve-token (fn [_ {:keys [role]}] role))

(defmethod resolve-token role-user [{:keys [storage]} {:keys [sub]}]
  (when sub
    (let [id (u/parse-sid sub)]
      (when (= 2 (count id))
        (log/debug "Looking up user with id" id)
        (some-> (st/find-user storage id)
                (update :customers set))))))

(defmethod resolve-token role-build [{:keys [storage]} {:keys [sub]}]
  (when-let [build (some->> sub
                            (u/parse-sid)
                            (st/find-build storage))]
    (assoc build :customers #{(:customer-id build)})))

(defn- query-auth-to-bearer
  "Middleware that puts the authorization token query param in the authorization header
   if no auth header is provided."
  [h]
  (fn [req]
    (let [header (get-in req [:headers "authorization"])
          query (get-in req [:query-params "authorization"])]
      (cond-> req
        (and query (not header))
        (assoc-in [:headers "authorization"] (str "Bearer " query))
        true h))))

(defn secure-ring-app
  "Wraps the ring handler so it verifies the JWT authorization header"
  [app rt]
  (let [pk (rt->pub-key rt)
        backend (bb/jws {:secret pk
                         :token-name "Bearer"
                         :options {:alg :rs256}
                         :authfn (partial resolve-token rt)})]
    (-> app
        (bmw/wrap-authentication backend)
        ;; Also check authorization query arg, because in some cases it's not possible
        ;; to pass it as a header (e.g. server-sent events).
        (query-auth-to-bearer)
        (rmp/wrap-params))))

(defn- check-authorization!
  "Checks if the request identity grants access to the customer specified in 
   the parameters path."
  [req]
  (when-let [cid (get-in req [:parameters :path :customer-id])]
    (when-not (and (ba/authenticated? req)
                   (contains? (get-in req [:identity :customers]) cid))
      (throw (ex-info "Credentials do not grant access to this customer"
                      {:type :auth/unauthorized
                       :customer-id cid})))))

(defn customer-authorization
  "Middleware that verifies the identity token to check if the user or build has
   access to the given customer."
  [h]
  (fn [req]
    (check-authorization! req)
    (h req)))

(defmethod rt/setup-runtime :jwk [conf _]
  (config->keypair conf))
