(ns monkey.ci.web.auth
  "Authentication and authorization functions"
  (:require [buddy.auth :as ba]
            [buddy.auth
             [backends :as bb]
             [middleware :as bmw]]
            [buddy.core
             [codecs :as codecs]
             [keys :as bk]
             [mac :as mac]
             [nonce :as nonce]]
            [buddy.sign.jwt :as jwt]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [monkey.ci
             [runtime :as rt]
             [sid :as sid]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.web.common :as c]
            [ring.middleware.params :as rmp]
            [ring.util.response :as rur]))

(def kid "master")
(def role-user "user")
(def role-build "build")

(def user-id
  "Retrieves current user id from request"
  (comp :id :identity))

(defn- make-token [role sid]
  {:role role
   :sub (sid/serialize-sid sid)})

(def user-token
  "Creates token contents for an authenticated user"
  (partial make-token role-user))

(def build-token
  "Creates token contents for a build, to be used by a build script."
  (partial make-token role-build))

(defn generate-secret-key
  "Generates a random secret key object"
  []
  (-> (nonce/random-nonce 32)
      (codecs/bytes->hex)))

(defn sign-jwt [payload pk]
  (jwt/sign payload pk {:alg :rs256 :header {:kid kid}}))

(def default-token-expiration (jt/days 1))

(defn augment-payload [payload]
  ;; TODO Make token expiration configurable
  (assoc payload
         :exp (-> (jt/plus (jt/instant) default-token-expiration)
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
  (log/debug "Configured JWK:" (:jwk conf))
  (let [m {:private-key (comp bk/str->private-key u/try-slurp)
           :public-key (comp bk/str->public-key u/try-slurp)}
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

(defn expired?
  "Returns true if token has expired"
  [{:keys [exp]}]
  (not (and exp (pos? (- exp (u/now))))))

(defmulti resolve-token (fn [_ {:keys [role]}] role))

(defmethod resolve-token role-user [{:keys [storage]} {:keys [sub] :as token}]
  (when (and (not (expired? token)) sub)
    (let [id (sid/parse-sid sub)]
      (when (= 2 (count id))
        (log/trace "Looking up user with id" id)
        (some-> (st/find-user-by-type storage id)
                (update :customers set))))))

(defmethod resolve-token role-build [{:keys [storage]} {:keys [sub] :as token}]
  (when-not (expired? token)
    (when-let [build (some->> sub
                              (sid/parse-sid)
                              (st/find-build storage))]
      (assoc build :customers #{(:customer-id build)}))))

(defmethod resolve-token :default [_ _]
  ;; Fallback, for backwards compatibility
  nil)

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

(def req->webhook-id (comp :id :path :parameters))

(defn- get-webhook-secret [req]
  ;; Find the secret key by looking up the webhook from storage
  (some-> (c/req->storage req)
          (st/find-webhook (req->webhook-id req))
          :secret-key))

(defn parse-signature
  "Parses HMAC signature header, returns the algorithm and the signature."
  [s]
  (when s
    (let [[k v :as parts] (seq (.split s "="))]
      (when (= 2 (count parts))
        {:alg (keyword k)
         :signature v}))))

(defn valid-security?
  "Validates security header"
  [{:keys [secret payload x-hub-signature]}]
  (when-let [{:keys [alg signature]} (parse-signature x-hub-signature)]
    (mac/verify payload
                (codecs/hex->bytes signature)
                {:key secret :alg (keyword (str "hmac+" (name alg)))})))

(defn validate-hmac-security
  "Middleware that validates the HMAC security header using a fn that retrieves
   the secret for the request."
  [h {:keys [get-secret header]
      :or {header "x-hub-signature-256"}}]
  (let [get-secret (or get-secret get-webhook-secret)]
    (fn [req]
      (if (valid-security? {:secret (get-secret req)
                            :payload (:body req)
                            :x-hub-signature (get-in req [:headers header])})
        (h req)
        (-> (rur/response "Invalid signature header")
            (rur/status 401))))))
