(ns monkey.ci.web.github
  "Functionality specific for Github"
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [labels :as lbl]
             [runtime :as rt]
             [storage :as s]
             [utils :as u]
             [version :as v]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]
             [oauth2 :as oauth2]
             [response :as r]]
            [aleph.http :as http]
            [ring.util.response :as rur]))

(def req->repo-sid (comp (juxt :customer-id :repo-id) :path :parameters))

(defn validate-security
  "Middleware that validates the github security header using a fn that retrieves
   the secret for the request."
  [h & [get-secret]]
  (auth/validate-hmac-security h {:get-secret get-secret}))

(defn github-event [req]
  (get-in req [:headers "x-github-event"]))

(def github-push?
  "Checks if the incoming request is actually a push.  Github can also
   send other types of requests."
  (comp (partial = "push") github-event))

(defn- find-ssh-keys [{st :storage} customer-id repo-id]
  (let [repo (s/find-repo st [customer-id repo-id])]
    (c/find-ssh-keys st repo)))

(defn- file-changes
  "Determines file changes according to the payload commits."
  [payload]
  (let [fkeys [:added :modified :removed]]
    (->> payload
         :commits
         (reduce (fn [r c]
                   (merge-with (comp set concat) r (select-keys c fkeys)))
                 (zipmap fkeys (repeat #{}))))))

(defn create-build
  "Looks up details for the given github webhook.  If the webhook refers to a valid 
   configuration, a build structure is returned, which will be sent in a `build/triggered`
   event and eventually passed on to a runner.  Creating the entity in the database is
   up to the event handler, to ensure uniqueness of assigned ids."
  [{st :storage :as rt} {:keys [customer-id repo-id] :as init-build} payload]
  (let [{:keys [master-branch clone-url ssh-url private]} (:repository payload)
        commit-id (get-in payload [:head-commit :id])
        ssh-keys (find-ssh-keys rt customer-id repo-id)
        build (-> init-build
                  (assoc :id (s/new-id)
                         :git (-> payload
                                  :head-commit
                                  (select-keys [:message :author])
                                  (assoc :url (if private ssh-url clone-url)
                                         :main-branch master-branch
                                         :ref (:ref payload)
                                         :commit-id commit-id)
                                  (mc/assoc-some :ssh-keys ssh-keys))
                         ;; Do not use the commit timestamp, because when triggered from a tag
                         ;; this is still the time of the last commit, not of the tag creation.
                         :start-time (u/now)
                         :status :pending
                         :changes (file-changes payload)))]
    ;; Add the sid, cause it's used downstream.  In this case it's the repo id, because
    ;; the build id still needs to be assigned.
    (assoc build :sid [customer-id repo-id])))

(defn create-webhook-build [{st :storage :as rt} id payload]
  (if-let [details (s/find-webhook st id)]
    (create-build
     rt
     (-> details
         (select-keys [:customer-id :repo-id])
         (assoc :webhook-id id
                :source :github-webhook))
     payload)
    (log/warn "No webhook configuration found for" id)))

(defn create-app-build
  "Creates a build as triggered from an app call.  This does not originate from a
   webhook, but rather from a watched repo."
  [rt {:keys [customer-id id]} payload]
  (create-build rt
                {:customer-id customer-id
                 :repo-id id
                 :source :github-app}
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
        (-> (rur/response (select-keys build [:id]))
            (r/add-event (b/build-triggered-evt build))
            (rur/status 202))
        ;; No valid webhook found
        (rur/not-found {:message "No valid webhook configuration found"})))
    ;; If this is not a build event, just respond with a '204 no content'
    (rur/status 204)))

(defn app-webhook [req]
  (log/trace "Got github app webhook event:" (pr-str (body req)))
  (log/debug "Event type:" (get-in req [:headers "x-github-event"]))
  (if (should-trigger-build? req)
    (let [github-id (get-in (body req) [:repository :id])
          builds (->> (s/find-watched-github-repos (c/req->storage req) github-id)
                      (map (fn [repo]
                             (create-app-build (c/req->rt req) repo (body req)))))]
      (log/debug "Found" (count builds) "watched builds for id" github-id)
      (-> builds
          (as-> b (->> (map #(select-keys % [:id]) b)
                       (hash-map :builds)))
          (rur/response)
          (r/add-events (map b/build-triggered-evt builds))
          (rur/status (if (empty? builds) 204 202))))
    ;; Don't trigger build, just say fine
    (rur/status 204)))

(defn watch-repo
  "Adds the repository to the watch list for github webhooks.  If the repo
   does not exist, it will be created."
  [req]
  (let [st (c/req->storage req)
        repo (get-in req [:parameters :body])
        existing (when-let [id (:id repo)]
                   (s/find-repo st [(:customer-id repo) id]))
        with-id (if existing
                  (merge existing repo)
                  (assoc repo :id (c/gen-repo-display-id st repo)))]
    (if (s/watch-github-repo st with-id)
      (rur/response with-id)
      (rur/status 500))))

(defn unwatch-repo [req]
  (let [st (c/req->storage req)
        sid (req->repo-sid req)]
    (log/debug "Unwatching repo:" sid)
    (if (s/unwatch-github-repo st sid)
      (rur/response (s/find-repo st sid))
      (rur/status 404))))

(def user-agent (str "MonkeyCI:" (v/version)))

(defn- github-config
  "Fetches github configuration from the request"
  [req]
  (c/from-rt req (comp :github rt/config)))

(defn- request-access-token [client-id client-secret opts]
  (-> (http/post "https://github.com/login/oauth/access_token"
                 {:query-params (merge {:client_id client-id
                                        :client_secret client-secret}
                                       opts)
                  :headers {"Accept" "application/json"
                            "User-Agent" user-agent}
                  :throw-exceptions false})
      (md/chain c/parse-body)
      deref))

(defn- request-new-token [req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-secret client-id]} (github-config req)]
    (request-access-token client-id client-secret {:code code})))

(defn- ->oauth-user [{:keys [id email]}]
  {:email email
   :sid [:github id]})

(defn request-user-info
  "Fetch github user details in order to get the id and email (although
   the latter is not strictly necessary).  We need the id in order to
   link the Github user to the MonkeyCI user."
  [token]
  (-> (http/get "https://api.github.com/user"
                {:headers {"Accept" "application/json"
                           "Authorization" (str "Bearer " token)
                           ;; Required by github
                           "User-Agent" user-agent}
                 :throw-exceptions false})
      (md/chain
       c/parse-body
       :body
       ->oauth-user)
      deref))

(def login (oauth2/login-handler
            request-new-token
            request-user-info))

(defn get-config
  "Lists public github configuration to use"
  [req]
  (rur/response {:client-id (c/from-rt req (comp :client-id :github rt/config))}))

(defn refresh-token
  "Refreshes a github token using the refresh token"
  [req]
  (let [{:keys [client-secret client-id]} (github-config req)
        refresh-token (get-in req [:parameters :body :refresh-token])]
    (request-access-token client-id
                          client-secret
                          {:grant_type "refresh_token"
                           :refresh_token refresh-token})))

(def refresh
  "Refreshing a token follows the same flow as login, but with a slightly different
   request to github oauth."
  (oauth2/login-handler
   refresh-token
   request-user-info))
