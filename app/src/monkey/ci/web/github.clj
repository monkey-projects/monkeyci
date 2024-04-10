(ns monkey.ci.web.github
  "Functionality specific for Github"
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.core.async :refer [go <!! <!]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [labels :as lbl]
             [runtime :as rt]
             [storage :as s]
             [utils :as u]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
            ;; TODO Replace httpkit with aleph
            [org.httpkit.client :as http]
            [ring.util.response :as rur]))

(defn extract-signature [s]
  (when s
    (let [[k v :as parts] (seq (.split s "="))]
      (when (and (= 2 (count parts)) (= "sha256" k))
        v))))

(defn valid-security?
  "Validates security header"
  [{:keys [secret payload x-hub-signature]}]
  (when-let [sign (extract-signature x-hub-signature)]
    (mac/verify payload
              (codecs/hex->bytes sign)
              {:key secret :alg :hmac+sha256})))

(def req->webhook-id (comp :id :path :parameters))

(defn validate-security
  "Middleware that validates the github security header using a fn that retrieves
   the secret for the request."
  ([h get-secret]
   (fn [req]
     (if (valid-security? {:secret (get-secret req)
                           :payload (:body req)
                           :x-hub-signature (get-in req [:headers "x-hub-signature-256"])})
       (h req)
       (-> (rur/response "Invalid signature header")
           (rur/status 401)))))
  ([h]
   (validate-security h (fn [req]
                          ;; Find the secret key by looking up the webhook from storage
                          (some-> (c/req->storage req)
                                  (s/find-details-for-webhook (req->webhook-id req))
                                  :secret-key)))))

(defn github-event [req]
  (get-in req [:headers "x-github-event"]))

(def github-push?
  "Checks if the incoming request is actually a push.  Github can also
   send other types of requests."
  (comp (partial = "push") github-event))

(defn- find-ssh-keys [st {:keys [customer-id repo-id]}]
  (let [repo (s/find-repo st [customer-id repo-id])
        ssh-keys (s/find-ssh-keys st customer-id)]
    (lbl/filter-by-label repo ssh-keys)))

(defn create-build
  "Looks up details for the given github webhook.  If the webhook refers to a valid 
   configuration, a build entity is created and a build structure is returned, which
   eventually will be passed on to the runner."
  [{st :storage :as rt} {:keys [id payload]}]
  (if-let [details (s/find-details-for-webhook st id)]
    (let [{:keys [master-branch clone-url ssh-url private]} (:repository payload)
          build-id (u/new-build-id)
          commit-id (get-in payload [:head-commit :id])
          ssh-keys (find-ssh-keys st details)
          build (-> (select-keys details [:customer-id :repo-id])
                    (assoc :git (-> payload
                                    :head-commit
                                    (select-keys [:message :author])
                                    (assoc :url (if private ssh-url clone-url)
                                           :main-branch master-branch
                                           :ref (:ref payload)
                                           :commit-id commit-id
                                           :ssh-keys-dir (rt/ssh-keys-dir rt build-id))
                                    (mc/assoc-some :ssh-keys ssh-keys))
                           :source :github
                           ;; Do not use the commit timestamp, because when triggered from a tag
                           ;; this is still the time of the last commit, not of the tag creation.
                           :start-time (u/now)
                           :status :running
                           :build-id build-id
                           :webhook-id id
                           :cleanup? true))]
      (when (s/save-build st build)
        ;; Add the sid, cause it's used downstream
        (assoc build :sid (s/ext-build-sid build))))
    (log/warn "No webhook configuration found for" id)))

(defn webhook
  "Receives an incoming webhook from Github.  This starts the build
   runner async and returns a 202 accepted."
  [{p :parameters :as req}]
  (log/trace "Got incoming webhook with body:" (prn-str (:body p)))
  (log/debug "Event type:" (get-in req [:headers "x-github-event"]))
  (if (and (github-push? req) (not (get-in p [:body :deleted])))
    (let [rt (c/req->rt req)]
      (if-let [build (create-build rt {:id (get-in p [:path :id])
                                       :payload (:body p)})]
        (do
          (c/run-build-async
            (assoc rt :build build))
          (rur/response {:build-id (:build-id build)}))
        ;; No valid webhook found
        (rur/not-found {:message "No valid webhook configuration found"})))
    ;; If no build trigger or non build event, just respond with a '204 no content'
    (rur/status 204)))

(defn app-webhook [req]
  (log/debug "Got github app webhook event:" (pr-str (get-in req [:parameters :body])))
  (log/debug "Event type:" (get-in req [:headers "x-github-event"]))
  ;; TODO Determine the customer/repo this is about, then check if it's configured to build
  (rur/response {:message "ok"}))

(defn- process-reply [{:keys [status] :as r}]
  (log/debug "Got github reply:" r)
  (update r :body c/parse-json))

(defn- request-access-token [req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-secret client-id]} (c/from-rt req (comp :github rt/config))]
    (-> @(http/post "https://github.com/login/oauth/access_token"
                    {:query-params {:client_id client-id
                                    :client_secret client-secret
                                    :code code}
                     :headers {"Accept" "application/json"}})
        (process-reply))))

(defn- request-user-info [token]
  (-> @(http/get "https://api.github.com/user"
                 {:headers {"Accept" "application/json"
                            "Authorization" (str "Bearer " token)}})
      (process-reply)
      ;; TODO Check for failures
      :body
      ;; TODO Create or lookup user in database according to github id
      (select-keys [:id :avatar-url :email :name])))

(defn- generate-jwt [req user]
  ;; Perhaps we should use the internal user id instead?
  ;; TODO Add user permissions
  (auth/generate-jwt req (auth/user-token ["github" (:type-id user)])))

(defn- add-jwt [user req]
  (assoc user :token (generate-jwt req user)))

(defn- fetch-or-create-user
  "Given the github user info, finds the matching user in the database, or creates
   a new one."
  [user req]
  (let [st (c/req->storage req)]
    (-> (or (s/find-user st [:github (:id user)])
            (let [u {:type "github"
                     :type-id (:id user)
                     ;; Keep track of email for reporting purposes
                     :email (:email user)}]
              (s/save-user st u)
              u))
        (merge (select-keys user [:avatar-url :name])))))

(defn login
  "Invoked by the frontend during OAuth2 login flow.  It requests a Github
   user access token using the given authorization code."
  [req]
  (let [token-reply (request-access-token req)]
    (if (and (= 200 (:status token-reply)) (nil? (get-in token-reply [:body :error])))
      ;; Request user info, generate JWT
      (-> (request-user-info (get-in token-reply [:body :access-token]))
          (fetch-or-create-user req)
          (add-jwt req)
          (rur/response))
      ;; Failure
      ;; TODO Don't treat all responses as client errors
      (rur/bad-request (:body token-reply)))))

(defn get-config
  "Lists public github configuration to use"
  [req]
  (rur/response {:client-id (c/from-rt req (comp :client-id :github rt/config))}))

(defmethod config/normalize-key :github [_ conf]
  conf)
