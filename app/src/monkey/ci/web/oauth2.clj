(ns monkey.ci.web.oauth2
  "OAuth2 flow support handlers"
  (:require [monkey.ci.storage :as s]
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
  [{:keys [sid] :as auth-user} req]
  (let [st (c/req->storage req)]
    (-> (or (s/find-user-by-type st sid)
            (let [u {:id (s/new-id)
                     :type (first sid)
                     :type-id (second sid)
                     ;; Keep track of email for reporting purposes
                     :email (:email auth-user)}]
              (s/save-user st u)
              u))
        (assoc (keyword (str (name (first sid)) "-token")) (:token auth-user)))))

(defn login-handler
  "Invoked by the frontend during OAuth2 login flow.  It requests an
   user access token using the given authorization code."
  [request-access-token request-user-info]
  (fn [req]
    (let [token-reply (request-access-token req)]
      (if (and (= 200 (:status token-reply)) (nil? (get-in token-reply [:body :error])))
        ;; Request user info, generate JWT
        (let [token (get-in token-reply [:body :access-token])]
          (-> (request-user-info token)
              ;; Return token to frontend, we'll need it when doing requests to external api.
              ;; TODO Keep track of any refresh tokens, so we can request a new token when it expires.
              (assoc :token token)
              (fetch-or-create-user req)
              (add-jwt req)
              (rur/response)))
        ;; Failure
        ;; TODO Don't treat all responses as client errors
        (rur/bad-request (:body token-reply))))))
