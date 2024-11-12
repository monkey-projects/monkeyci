(ns monkey.ci.web.bitbucket
  "Bitbucket specific endpoints, mainly for authentication or push callbacks."
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [labels :as lbl]
             [runtime :as rt]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]
             [oauth2 :as oauth2]]
            [ring.util.response :as rur]))

(defn- handle-error [ex]
  (log/error "Got Bitbucket error:" ex)
  (let [d (ex-data ex)]
    (some-> d
            :body
            (bs/to-string)
            (log/error))
    d))

(defn- request-access-token [req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-secret client-id]} (c/from-rt req (comp :bitbucket rt/config))]
    @(-> (http/post "https://bitbucket.org/site/oauth2/access_token"
                    {:form-params {:grant_type "authorization_code"
                                   :code code}
                     :basic-auth [client-id client-secret]
                     :headers {"Accept" "application/json"}})
         (md/chain c/parse-body)
         (md/catch handle-error))))

(defn- ->oauth-user [{:keys [uuid] :as u}]
  (log/debug "Converting Bitbucket user:" u)
  {:sid [:bitbucket uuid]})

(defn- api-req [req]
  (http/request (-> req
                    (dissoc :path)
                    (assoc :url (str "https://api.bitbucket.org/2.0" (:path req))))))

(defn- auth-req [req token]
  (api-req (assoc-in req [:headers "Authorization"] (str "Bearer " token))))

(defn- request-user-info [token]
  (when token 
    (-> @(auth-req {:path "/user"
                    :request-method :get
                    :headers {"Accept" "application/json"}}
                   token)
        (c/parse-body)
        :body
        (->oauth-user))))

(def login (oauth2/login-handler
            request-access-token
            request-user-info))

(defn get-config
  "Lists public bitbucket config, necessary for authentication."
  [req]  
  (rur/response {:client-id (c/from-rt req (comp :client-id :bitbucket rt/config))}))

(defn- ext-webhook-url [req wh]
  (str (c/req->ext-uri req "/customer") "/webhook/bitbucket/" (:id wh)))

(defn- create-bb-webhook [req wh]
  (log/debug "Creating Bitbucket webhook for internal webhook" (:id wh))
  (let [s (c/req->storage req)
        url (ext-webhook-url req wh)
        body (c/body req)
        ;; Create bitbucket webhook using external url
        ;; TODO Reactivate existing webhook
        bb-resp @(-> (auth-req {:path (format "/repositories/%s/%s/hooks" (:workspace body) (:repo-slug body))
                                :request-method :post
                                :headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:url url
                                        :active true
                                        :secret (:secret-key wh)
                                        :events ["repo:push"]
                                        :description "MonkeyCI generated webhook"})}
                               (:token body))
                     (md/chain c/parse-body))]
    (if (= 201 (:status bb-resp))
      (st/save-bb-webhook s (-> body
                                (select-keys [:repo-slug :workspace])
                                (assoc :id (cuid/random-cuid)
                                       :webhook-id (:id wh)
                                       :bitbucket-id (get-in bb-resp [:body :uuid]))))
      (throw (ex-info "Unable to create bitbucket webhook" bb-resp)))))

(defn watch-repo
  "Starts watching a Bitbucket repo.  This installs a webhook in the repository,
   or reactivates it if it already exists.  We keep track of the returned webhook 
   id in the database."
  [req]
  (let [s (c/req->storage req)
        body (c/body req)
        cust-id (c/customer-id req)
        cust (st/find-customer s cust-id)]
    (if cust
      (let [repo (-> body
                     (select-keys [:customer-id :name :url :main-branch])
                     (assoc :id (c/gen-repo-display-id s body)))
            wh {:customer-id cust-id
                :repo-id (:id repo)
                :id (cuid/random-cuid)
                :secret-key (auth/generate-secret-key)}]
        ;; TODO Only create if it does not exist already
        (if (st/save-repo s repo)
          (if (st/save-webhook s wh)
            (if (create-bb-webhook req wh)
              (-> (rur/response repo)
                  (rur/status 201))
              (c/error-response "Unable to create bitbucket webhook" 500))
            (c/error-response "Unable to save webhook" 500))
          (c/error-response "Unable to save repository" 500)))
      (c/error-response "Customer not found" 404))))

(defn unwatch-repo
  "Unwatches Bitbucket repo by deactivating any existing webhook."
  [req]
  ;; TODO Deactivate the BB webhook and delete associated record
  (rur/response "todo"))

(defn- git-ref [payload]
  (let [{:keys [name type]} (get-in payload [:push :new])]
    (condp = type
      "branch" (str "refs/heads/" name)
      "tag" (str "refs/tags/" name))))

(defn- make-build [req {:keys [customer-id repo-id] :as wh}]
  (let [st (c/req->storage req)
        idx (st/find-next-build-idx st [customer-id repo-id])
        build-id (c/new-build-id idx)
        sid [customer-id repo-id build-id]
        body (get-in req [:parameters :body])
        repo (st/find-repo st [customer-id repo-id])
        ssh-keys (->> (st/find-ssh-keys st customer-id)
                      (lbl/filter-by-label repo))]
    (-> (zipmap [:customer-id :repo-id :build-id] sid)
        (assoc :sid sid
               :source :bitbucket-webhook
               :start-time (t/now)
               :status :pending
               :idx idx
               :cleanup? true
               :git (cond-> {:url (:url repo)
                             :ref (git-ref body)
                             :ssh-keys-dir (rt/ssh-keys-dir (c/req->rt req) build-id)}
                      (not-empty ssh-keys) (assoc :ssh-keys ssh-keys))))))

(defn- handle-push [req]
  (let [st (c/req->storage req)
        webhook-id (get-in req [:parameters :path :id])
        wh (st/find-webhook st webhook-id)]
    (if wh
      (let [build (make-build req wh)]
        (c/run-build-async (c/req->rt req) build)
        (-> (rur/response (select-keys build [:build-id]))
            (rur/status 202)))
      (c/error-response "Webhook not found" 404))))

(defn- handle-unsupported [type]
  (log/debug "Ignoring unsupported Bitbucket webhook request:" type)
  (rur/status 200))

(defn webhook
  "Handles incoming calls from Bitbucket through installed webhooks."
  [req]
  (log/debug "Got incoming bitbucket request:" (pr-str (:body req)))
  (let [type (get-in req [:headers "x-event-key"])]
    (condp = type
      "repo:push" (handle-push req)
      (handle-unsupported type))))

(defn validate-security
  "Validates bitbucket hmac signature header"
  [h]
  (auth/validate-hmac-security h {:header "x-hub-signature"}))
