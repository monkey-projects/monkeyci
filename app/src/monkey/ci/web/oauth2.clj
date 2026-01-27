(ns monkey.ci.web.oauth2
  "OAuth2 flow support handlers"
  (:require [aleph.http :as http]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.storage :as s]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
            [ring.util.response :as rur]))

(defn- generate-jwt [req user]
  ;; Perhaps we should use the internal user id instead?
  ;; TODO Add user permissions
  (auth/generate-jwt req (auth/user-token [(name (:type user)) (:type-id user)])))

(defn- add-jwt [user req]
  (assoc user :token (generate-jwt req user)))

(defn- fetch-or-create-user
  "Given the oauth user info, finds the matching user in the database, or creates
   a new one."
  [{:keys [sid email] :as auth-user} req]
  (let [st (c/req->storage req)]
    (-> (or (s/find-user-by-type st sid)
            (let [u {:id (s/new-id)
                     :type (first sid)
                     :type-id (second sid)
                     ;; Keep track of email for reporting purposes
                     :email email}]
              (s/save-user st u)
              u))
        (assoc (keyword (str (name (first sid)) "-token")) (:token auth-user)))))

(defn login-handler
  "Invoked by the frontend during OAuth2 login flow.  It requests a
   user access token using the given authorization code."
  [request-access-token request-user-info]
  (fn [req]
    (let [token-reply (request-access-token req)]
      (if (and (= 200 (:status token-reply)) (nil? (get-in token-reply [:body :error])))
        ;; Request user info, generate JWT
        (let [token (get-in token-reply [:body :access-token])]
          (-> (request-user-info token)
              ;; Return token to frontend, we'll need it when doing requests to external api.
              (assoc :token token)
              (fetch-or-create-user req)
              ;; Return any refresh tokens, so we can request a new token when it expires.
              (mc/assoc-some :refresh-token (get-in token-reply [:body :refresh-token]))
              (add-jwt req)
              (rur/response)))
        ;; Failure
        ;; TODO Don't treat all responses as client errors
        (rur/bad-request (:body token-reply))))))

(defn- request-access-token
  "Requests access token using oidc flow"
  [url client-id client-secret set-params opts]
  (let [qp (merge {:client_id client-id
                   :client_secret client-secret}
                  opts)]
    (-> (http/post url
                   (-> {:headers {"Accept" "application/json"
                                  "User-Agent" c/user-agent}
                        :throw-exceptions false}
                       (set-params qp)))
        (md/chain c/parse-body)
        deref)))

(defn- request-new-token [{:keys [get-creds request-token-url set-params]} req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-id client-secret]} (get-creds req)]
    (log/debug "Requesting new token from" request-token-url)
    (request-access-token request-token-url client-id client-secret set-params
                          {:code code})))

(defn- request-user-info
  "Fetch user details in order to get the id and email (although
   the latter is not strictly necessary).  We need the id in order to
   link the external user to the MonkeyCI user."
  [{:keys [user-info-url convert-user]} token]
  (log/debug "Requesting user info from" user-info-url "using access token" token)
  (-> (http/get user-info-url
                {:headers {"Accept" "application/json"
                           "Authorization" (str "Bearer " token)
                           "User-Agent" c/user-agent}
                 :throw-exceptions false})
      (md/chain
       c/parse-body
       :body
       convert-user)
      deref))

(defn oidc-login
  "Generic OIDC login flow handler, that requests a new token using client id and secret.
   The given config specifies the urls to invoke, and where to get credentials."
  [conf]
  (login-handler
   (partial request-new-token conf)
   (partial request-user-info conf)))

(defn refresh-token
  "Refreshes a token using the refresh token"
  [{:keys [get-creds request-token-url set-params]} req]
  (let [{:keys [client-secret client-id]} (get-creds req)
        refresh-token (get-in req [:parameters :body :refresh-token])]
    (request-access-token request-token-url
                          client-id
                          client-secret
                          set-params
                          {:grant_type "refresh_token"
                           :refresh_token refresh-token})))

(defn oidc-refresh
  "Generic OIDC refresh flow handler, that refreshes a token using client id and secret 
   as specified in the given config."
  [conf]
  (login-handler
   (partial refresh-token conf)
   (partial request-user-info conf)))
