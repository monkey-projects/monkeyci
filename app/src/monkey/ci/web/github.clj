(ns monkey.ci.web.github
  "Functionality specific for Github"
  (:require [buddy.core
             [codecs :as codecs]
             [mac :as mac]]
            [clojure.core.async :refer [go <!! <!]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [context :as ctx]
             [labels :as lbl]
             [storage :as s]
             [utils :as u]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
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

(defn- github-commit-trigger?
  "Checks if the incoming request is actually a commit trigger.  Github can also
   send other types of requests."
  [req]
  (some? (get-in req [:body-params :head-commit])))

(defn webhook
  "Receives an incoming webhook from Github.  This actually just posts
   the event on the internal bus and returns a 200 OK response."
  [req]
  (log/trace "Got incoming webhook with body:" (prn-str (:body-params req)))
  (c/posting-handler
   req
   (fn [req]
     (when (github-commit-trigger? req)
       {:type :webhook/github
        :id (req->webhook-id req)
        :payload (:body-params req)}))))

(defn- find-ssh-keys [st {:keys [customer-id repo-id]}]
  (let [repo (s/find-repo st [customer-id repo-id])
        ssh-keys (s/find-ssh-keys st customer-id)]
    (lbl/filter-by-label repo ssh-keys)))

(defn prepare-build
  "Event handler that looks up details for the given github webhook.  If the webhook 
   refers to a valid configuration, a build id is created and a new event is launched,
   which in turn should start the build runner."
  [{st :storage :as ctx} {:keys [id payload] :as evt}]
  (if-let [details (s/find-details-for-webhook st id)]
    (let [{:keys [master-branch clone-url ssh-url private]} (:repository payload)
          build-id (u/new-build-id)
          commit-id (get-in payload [:head-commit :id])
          md (-> details
                 (dissoc :id :secret-key)
                 (assoc :webhook-id id
                        :build-id build-id
                        :commit-id commit-id
                        :source :github)
                 (merge (-> payload
                            :head-commit
                            (select-keys [:timestamp :message :author])))
                 (merge (select-keys payload [:ref])))
          ssh-keys (find-ssh-keys st details)
          conf {:git (-> {:url (if private ssh-url clone-url)
                          :main-branch master-branch
                          :ref (:ref payload)
                          :commit-id commit-id
                          :ssh-keys-dir (ctx/ssh-keys-dir ctx build-id)}
                         (mc/assoc-some :ssh-keys ssh-keys))
                :sid (s/ext-build-sid md) ; Build storage id
                :build-id build-id}]
      (when (s/create-build-metadata st md)
        {:type :webhook/validated
         :details details
         :build conf}))
    (log/warn "No webhook configuration found for" id)))

(defn- process-reply [{:keys [status] :as r}]
  (log/debug "Got github reply:" r)
  (update r :body c/parse-json))

(defn- request-access-token [req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-secret client-id]} (c/from-context req :github)]
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
  (auth/generate-jwt req {:type "github"
                          :user-id (:id user)}))

(defn login
  "Invoked by the frontend during OAuth2 login flow.  It requests a Github
   user access token using the given authorization code."
  [req]
  (let [token-reply (request-access-token req)]
    (if (and (= 200 (:status token-reply)) (nil? (get-in token-reply [:body :error])))
      ;; Request user info, generate JWT
      (let [user (request-user-info (get-in token-reply [:body :access-token]))]
        (-> user
            (assoc :token (generate-jwt req user))
            (rur/response)))
      ;; Failure
      ;; TODO Don't treat all responses as client errors
      (rur/bad-request (:body token-reply)))))
