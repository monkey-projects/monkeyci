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
             [build :as b]
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
(def req->repo-sid (comp (juxt :customer-id :repo-id) :path :parameters))

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

(defn- find-ssh-keys [st customer-id repo-id]
  (let [repo (s/find-repo st [customer-id repo-id])
        ssh-keys (s/find-ssh-keys st customer-id)]
    (lbl/filter-by-label repo ssh-keys)))

(defn- file-changes
  "Determines file changes according to the payload commits."
  [payload]
  (let [fkeys [:changes/added :changes/modified :changes/removed]
        pkeys (juxt :added :modified :removed)]
    (->> payload
         :commits
         (reduce (fn [r c]
                   (merge-with (comp set concat) r (zipmap fkeys (pkeys c))))
                 (zipmap fkeys (repeat #{}))))))

(defn create-build
  "Looks up details for the given github webhook.  If the webhook refers to a valid 
   configuration, a build entity is created and a build structure is returned, which
   eventually will be passed on to the runner."
  [{st :storage :as rt} init-build payload]
  (let [{:keys [master-branch clone-url ssh-url private]} (:repository payload)
        build-id (u/new-build-id)
        commit-id (get-in payload [:head-commit :id])
        ssh-keys (find-ssh-keys st (:customer/id init-build) (:repo/id init-build))
        build {:build/entity
               (-> init-build
                   (assoc :git/entity {:git/url (if private ssh-url clone-url)
                                       :git/main-branch master-branch
                                       :git/ref (:ref payload)
                                       :commit/id commit-id
                                       :commit/message (get-in payload [:head-commit :message])
                                       :git/changes (file-changes payload)}
                          :build/id build-id
                          ;; Do not use the commit timestamp, because when triggered from a tag
                          ;; this is still the time of the last commit, not of the tag creation.
                          :time/start (u/now)
                          :build/phase :pending))
               :build/status
               {:build/cleanup? true
                :git/status (-> {:git/ssh-keys-dir (rt/ssh-keys-dir rt build-id)}
                                (mc/assoc-some :git/ssh-keys ssh-keys))}}]
    (when (s/save-build st (b/entity build))
      build)))

(defn create-webhook-build [{st :storage :as rt} id payload]
  (if-let [details (s/find-details-for-webhook st id)]
    (create-build
     rt
     (-> details
         (select-keys [:customer/id :repo/id :webhook/id])
         (assoc :build/source :github-webhook))
     payload)
    (log/warn "No webhook configuration found for" id)))

(defn create-app-build
  "Creates a build as triggered from an app call.  This does not originate from a
   webhook, but rather from a watched repo."
  [rt {:keys [customer-id id]} payload]
  (create-build
   rt
   {:customer/id customer-id
    :repo/id id
    :build/source :github-app}
   payload))

(def body (comp :body :parameters))

(def should-trigger-build? (every-pred github-push?
                                       (complement (comp :deleted body))))

(defn webhook
  "Receives an incoming webhook from Github.  This starts the build
   runner async and returns a 202 accepted."
  [{p :parameters :as req}]
  (log/trace "Got incoming webhook with body:" (prn-str (body req)))
  (log/debug "Event type:" (get-in req [:headers "x-github-event"]))
  (if (should-trigger-build? req)
    (let [rt (c/req->rt req)]
      (if-let [build (create-webhook-build rt (get-in p [:path :id]) (body req))]
        (do
          (c/run-build-async
           (assoc rt :build build))
          (-> (rur/response {:build-id (:build-id build)})
              (rur/status 202)))
        ;; No valid webhook found
        (rur/not-found {:message "No valid webhook configuration found"})))
    ;; If this is not a build event, just respond with a '204 no content'
    (rur/status 204)))

(defn app-webhook [req]
  (log/debug "Got github app webhook event:" (pr-str (body req)))
  (log/debug "Event type:" (get-in req [:headers "x-github-event"]))
  (if (should-trigger-build? req)
    (let [github-id (get-in (body req) [:repository :id])
          matches (s/find-watched-github-repos (c/req->storage req) github-id)
          run-build (fn [repo]
                      (let [rt (c/req->rt req)
                            build (create-app-build rt repo (body req))]
                        (c/run-build-async (assoc rt :build build))
                        build))]
      (log/debug "Found" (count matches) "watched builds for id" github-id)
      (-> (->> matches
               (map run-build)
               (map (comp (partial hash-map :build-id) :build-id))
               (hash-map :builds)
               (rur/response))
          (rur/status (if (empty? matches) 204 202))))
    ;; Don't trigger build, just say fine
    (rur/status 204)))

(defn- in->repo [in]
  (mc/assoc-some
   {}
   :repo/id (:id in)
   :customer/id (:customer-id in)
   :repo/name (:name in)
   :repo/url (:url in)
   :github/id (:github-id in)))

(defn- repo->out [r]
  {:id (:repo/id r)
   :customer-id (:customer/id r)
   :github-id (:github/id r)
   :name (:repo/name r)
   :url (:repo/url r)})

(defn watch-repo
  "Adds the repository to the watch list for github webhooks.  If the repo
   does not exist, it will be created."
  [req]
  (let [st (c/req->storage req)
        repo (in->repo (get-in req [:parameters :body]))
        existing (when-let [id (:repo/id repo)]
                   (s/find-repo st [(:customer/id repo) id]))
        with-id (if existing
                  (merge existing repo)
                  (assoc repo :repo/id (s/new-id)))]
    (if (s/watch-github-repo st with-id)
      (rur/response (repo->out with-id))
      (rur/status 500))))

(defn unwatch-repo [req]
  (let [st (c/req->storage req)
        sid (req->repo-sid req)]
    (if (s/unwatch-github-repo st sid)
      (rur/response (s/find-repo st sid))
      (rur/status 404))))

(defn- process-reply [{:keys [status] :as r}]
  (log/trace "Got github reply:" r)
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
      (select-keys [:id :email])
      ;; Return token to frontend, we'll need it when doing github requests.
      (assoc :github-token token)))

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
        (merge (select-keys user [:github-token])))))

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
