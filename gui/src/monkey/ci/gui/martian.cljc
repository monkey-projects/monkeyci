(ns monkey.ci.gui.martian
  (:require [martian.core :as martian]
            [martian.cljs-http :as mh]
            [martian.interceptors :as mi]
            [martian.re-frame :as mr]
            [monkey.ci.common.schemas :as cs]
            [monkey.ci.gui.edn]
            [monkey.ci.gui.local-storage]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.db :as ldb]
            [re-frame.core :as rf]
            [schema.core :as s]))

(def org-path ["/org/" :org-id])
(def repo-path (into org-path ["/repo/" :repo-id]))
(def build-path (into repo-path ["/builds/" :build-id]))
(def param-path (into org-path ["/param/" :param-id]))
(def user-path ["/user/" :user-id])
(def mailing-path ["/admin/mailing/" :mailing-id])

(def org-schema
  {:org-id s/Str})

(def repo-schema
  (assoc org-schema
         :repo-id s/Str))

(def build-schema
  (assoc repo-schema :build-id s/Str))

(def param-schema
  (assoc org-schema
         :param-id s/Str))

(def user-schema
  {:user-id s/Str})

(def webhook-schema
  {:webhook-id s/Str})

;; TODO Use the same source as backend for this
(s/defschema NewOrg
  {:name s/Str})

(s/defschema UpdateOrg
  {:id s/Str
   :name s/Str})

(s/defschema Label
  {:name s/Str
   :value s/Str})

(s/defschema UpdateRepo
  {:org-id s/Str
   :name s/Str
   :url s/Str
   (s/optional-key :main-branch) s/Str
   (s/optional-key :public) s/Bool
   (s/optional-key :github-id) s/Int
   (s/optional-key :labels) [Label]})

(def NewRepo UpdateRepo)

(s/defschema log-query-schema
  {:query s/Str
   :direction s/Str
   (s/optional-key :end) s/Int
   (s/optional-key :start) s/Int})

(s/defschema UpdateParam
  {:name s/Str
   :value s/Str})

(s/defschema LabelFilterConjunction
  {:label s/Str
   :value s/Str})

(s/defschema LabelFilterDisjunction
  [LabelFilterConjunction])

(s/defschema NewParamSet
  {(s/optional-key :description) s/Str
   :parameters [UpdateParam]
   :label-filters [LabelFilterDisjunction]})

(s/defschema UpdateParamSet
  (assoc NewParamSet
         :id s/Str))

(s/defschema SshKey
  {(s/optional-key :id) s/Str
   (s/optional-key :description) s/Str
   :private-key s/Str
   :public-key s/Str
   :label-filters [LabelFilterDisjunction]})

(s/defschema UserCredits
  {:amount s/Int
   (s/optional-key :reason) s/Str
   :from-time s/Int})

(s/defschema CreditSubscription
  {:amount s/Int
   :valid-from s/Int
   (s/optional-key :valid-until) s/Int})

(s/defschema NewWebhook
  {:org-id s/Str
   :repo-id s/Str})

(s/defschema TriggerParams
  {(s/optional-key :branch) s/Str
   (s/optional-key :tag) s/Str
   (s/optional-key :commit-id) s/Str
   ;; If we use string keys here, martian drops the parameters due to schema coercion (I think).
   ;; But now it sends them as keywords, which results in a 400 error on client side.
   (s/optional-key :params) {s/Any s/Str}})

(s/defschema ApiToken
  {(s/optional-key :description) s/Str
   (s/optional-key :valid-until) s/Int})

(def Date #"\d{4}-\d{2}-\d{2}")

(defn public-route [conf]
  (merge {:method :get
          :produces #{"application/edn"}
          :consumes #{"application/edn"}}
         conf))

(defn api-route [conf]
  (-> conf
      (assoc-in [:headers-schema (s/optional-key :authorization)] s/Str)
      (public-route)))

(def org-routes
  [(api-route
    {:route-name :get-org
     :path-parts org-path
     :path-schema org-schema})

   (api-route
    {:route-name :create-org
     :method :post
     :path-parts ["/org"]
     :body-schema {:org NewOrg}})

   (api-route
    {:route-name :update-org
     :method :put
     :path-parts org-path
     :path-schema org-schema
     :body-schema {:org UpdateOrg}})

   (api-route
    {:route-name :search-orgs
     :path-parts ["/org"]
     :query-schema {(s/optional-key :name) s/Str
                    (s/optional-key :id) s/Str}})

   (api-route
    {:route-name :get-org-stats
     :path-parts (into org-path ["/stats"])
     :path-schema org-schema
     :query-schema {(s/optional-key :since) s/Int
                    (s/optional-key :until) s/Int}
     :method :get})])

(def repo-routes
  [(api-route
    {:route-name :get-repo
     :method :get
     :path-parts repo-path
     :path-schema repo-schema})

   (api-route
    {:route-name :create-repo
     :method :post
     :path-parts (into org-path ["/repo"])
     :path-schema org-schema
     :body-schema {:repo NewRepo}})

   (api-route
    {:route-name :update-repo
     :method :put
     :path-parts repo-path
     :path-schema repo-schema
     :body-schema {:repo UpdateRepo}})

   (api-route
    {:route-name :delete-repo
     :method :delete
     :path-parts repo-path
     :path-schema repo-schema})])

(def build-routes
  [(api-route
    {:route-name :get-recent-builds
     :path-parts (into org-path ["/builds/recent"])
     :path-schema org-schema
     :query-schema {(s/optional-key :since) s/Int
                    (s/optional-key :n) s/Int}})

   (api-route
    {:route-name :get-org-latest-builds
     :path-parts (into org-path ["/builds/latest"])
     :path-schema org-schema})

   (api-route
    {:route-name :get-builds
     :path-parts (into repo-path ["/builds"])
     :path-schema repo-schema})

   (api-route
    {:route-name :trigger-build
     :method :post
     :path-parts (into repo-path ["/builds/trigger"])
     :path-schema repo-schema
     :body-schema {:trigger TriggerParams}})

   (api-route
    {:route-name :get-build
     :path-parts build-path
     :path-schema build-schema})

   (api-route
    {:route-name :retry-build
     :method :post
     :path-parts (conj build-path "/retry")
     :path-schema build-schema})

   (api-route
    {:route-name :cancel-build
     :method :post
     :path-parts (conj build-path "/cancel")
     :path-schema build-schema})])

(def params-routes
  [(api-route
    {:route-name :get-org-params
     :path-parts (into org-path ["/param"])
     :path-schema org-schema})

   (api-route
    {:route-name :update-org-params
     :path-parts (into org-path ["/param"])
     :path-schema org-schema
     :method :post
     :body-schema {:params [UpdateParamSet]}})

   (api-route
    {:route-name :create-param-set
     :path-parts (into org-path ["/param"])
     :path-schema org-schema
     :method :post
     :body-schema {:params NewParamSet}})

   (api-route
    {:route-name :update-param-set
     :path-parts param-path
     :path-schema param-schema
     :method :put
     :body-schema {:params UpdateParamSet}})

   (api-route
    {:route-name :delete-param-set
     :path-parts param-path
     :path-schema param-schema
     :method :delete})])

(def ssh-keys-routes
  [(api-route
    {:route-name :get-org-ssh-keys
     :path-parts (into org-path ["/ssh-keys"])
     :path-schema org-schema})

   (api-route
    {:route-name :update-org-ssh-keys
     :path-parts (into org-path ["/ssh-keys"])
     :path-schema org-schema
     :method :put
     :body-schema {:ssh-keys [SshKey]}
     :headers-schema {(s/optional-key :content-type) s/Str}})])

(def credit-routes
  [(api-route
    {:route-name :get-org-credits
     :path-parts (into org-path ["/credits"])
     :path-schema org-schema
     :method :get})

   (api-route
    {:route-name :get-credit-issues
     :path-parts ["/admin/credits/" :org-id]
     :path-schema org-schema
     :method :get})

   (api-route
    {:route-name :create-credit-issue
     :path-parts ["/admin/credits/" :org-id "/issue"]
     :path-schema org-schema
     :body-schema {:credits UserCredits}
     :method :post})

   (api-route
    {:route-name :credits-issue-all
     :path-parts ["/admin/credits/issue"]
     :body-schema {:issue-all {:date Date}}
     :method :post})

   (api-route
    {:route-name :get-credit-subs
     :path-parts ["/admin/credits/" :org-id "/subscription"]
     :path-schema org-schema
     :method :get})

   (api-route
    {:route-name :create-credit-sub
     :path-parts ["/admin/credits/" :org-id "/subscription"]
     :path-schema org-schema
     :body-schema {:sub CreditSubscription}
     :method :post})])

(def token-routes
  [(api-route
    {:route-name :get-org-tokens
     :path-parts (into org-path ["/token"])
     :path-schema org-schema
     :method :get})

   (api-route
    {:route-name :create-org-token
     :path-parts (into org-path ["/token"])
     :path-schema org-schema
     :body-schema {:token ApiToken}
     :method :post})

   (api-route
    {:route-name :delete-org-token
     :path-parts (into org-path ["/token/" :token-id])
     :path-schema (assoc org-schema :token-id s/Str)
     :method :delete})])

(def user-routes
  [(api-route
    {:route-name :get-user-orgs
     :path-parts (into user-path ["/orgs"])
     :path-schema user-schema})

   (api-route
    {:route-name :get-user-join-requests
     :path-parts (into user-path "/join-request")
     :path-schema user-schema})

   (api-route
    {:route-name :create-user-join-request
     :method :post
     :path-parts (into user-path "/join-request")
     :path-schema user-schema
     :body-schema {:join-request
                   {:org-id s/Str}}})])

(def webhook-routes
  [(api-route
    {:route-name :get-repo-webhooks
     :method :get
     :path-parts (conj repo-path "/webhooks")
     :path-schema repo-schema})

   (api-route
    {:route-name :create-webhook
     :method :post
     :path-parts ["/webhook"]
     :body-schema {:webhook NewWebhook}})

   (api-route
    {:route-name :delete-webhook
     :method :delete
     :path-parts ["/webhook/" :webhook-id]
     :path-schema webhook-schema})])

(def log-routes
  [(api-route
    {:route-name :download-log
     :path-parts ["/logs/" :org-id "/entries"]
     :path-schema org-schema
     :query-schema log-query-schema
     :produces #{"application/json"}})

   (api-route
    {:route-name :get-log-stats
     :path-parts ["/logs/" :org-id "/stats"]
     :path-schema org-schema
     :query-schema log-query-schema
     :produces #{"application/json"}})

   (api-route
    {:route-name :get-log-label-values
     :path-parts ["/logs/" :org-id "/label/" :label "/values"]
     :path-schema (assoc org-schema :label s/Str)
     :query-schema log-query-schema
     :produces #{"application/json"}})])

(def github-routes
  [(api-route
    {:route-name :watch-github-repo
     :method :post
     :path-parts (conj org-path "/repo/github/watch")
     :path-schema org-schema
     :body-schema {:repo {:name s/Str
                          :url s/Str
                          :org-id s/Str
                          :github-id s/Int}}})

   (api-route
    {:route-name :unwatch-github-repo
     :method :post
     :path-parts (conj repo-path "/github/unwatch")
     :path-schema repo-schema})

   (public-route
    {:route-name :github-login
     :method :post
     :path-parts ["/github/login"]
     :query-schema {:code s/Str}})

   (public-route
    {:route-name :github-refresh
     :method :post
     :path-parts ["/github/refresh"]
     :body-schema {:refresh {:refresh-token s/Str}}})   
   
   (public-route
    {:route-name :get-github-config
     :path-parts ["/github/config"]})])

(def bitbucket-routes
  [(api-route
    {:route-name :watch-bitbucket-repo
     :method :post
     :path-parts (conj org-path "/repo/bitbucket/watch")
     :path-schema org-schema
     :body-schema {:repo {:name s/Str
                          :url s/Str
                          :org-id s/Str
                          :workspace s/Str
                          :repo-slug s/Str
                          :token s/Str}}})


   (api-route
    {:route-name :unwatch-bitbucket-repo
     :method :post
     :path-parts (conj repo-path "/bitbucket/unwatch")
     :path-schema repo-schema
     :body-schema {:repo {:token s/Str}}})

   (api-route
    {:route-name :search-bitbucket-webhooks
     :path-parts (conj org-path "/webhook/bitbucket")
     :path-schema org-schema
     :query-schema {(s/optional-key :repo-id) s/Str
                    (s/optional-key :workspace) s/Str
                    (s/optional-key :repo-slug) s/Str
                    (s/optional-key :bitbucket-id) s/Str}})

   (public-route
    {:route-name :bitbucket-login
     :method :post
     :path-parts ["/bitbucket/login"]
     :query-schema {:code s/Str}})

   (public-route
    {:route-name :bitbucket-refresh
     :method :post
     :path-parts ["/bitbucket/refresh"]
     :body-schema {:refresh {:refresh-token s/Str}}})

   (public-route
    {:route-name :get-bitbucket-config
     :path-parts ["/bitbucket/config"]})])

(def reaper-routes
  [(api-route
    {:route-name :admin-reaper
     :method :post
     :path-parts ["/admin/reaper"]})])

(def mailing-routes
  [(api-route
    {:route-name :get-mailings
     :method :get
     :path-parts ["/admin/mailing"]})

   (api-route
    {:route-name :create-mailing
     :method :post
     :path-parts ["/admin/mailing"]
     :body-schema {:mailing cs/Mailing}})

   (api-route
    {:route-name :update-mailing
     :method :put
     :path-parts mailing-path
     :path-schema {:mailing-id s/Str}
     :body-schema {:mailing cs/Mailing}})

   (api-route
    {:route-name :delete-mailing
     :method :delete
     :path-parts mailing-path})

   (api-route
    {:route-name :get-sent-mailings
     :method :get
     :path-parts (conj mailing-path "/send")
     :path-schema {:mailing-id s/Str}})

   (api-route
    {:route-name :create-send-mailing
     :method :post
     :path-parts (conj mailing-path "/send")
     :path-schema {:mailing-id s/Str}
     :body-schema {:send cs/SentMailing}})])

(def general-routes
  [(public-route
    {:route-name :get-version
     :method :get
     :path-parts ["/version"]})])

(def login-routes
  [(public-route
    {:route-name :admin-login
     :method :post
     :path-parts ["/admin/login"]
     :body-schema {:creds {:username s/Str
                           :password s/Str}}})])

(def routes
  (concat
   org-routes
   repo-routes
   build-routes
   params-routes
   ssh-keys-routes
   credit-routes
   token-routes
   user-routes
   webhook-routes
   log-routes
   github-routes
   bitbucket-routes
   reaper-routes
   mailing-routes
   general-routes
   login-routes))

;; The api url.  This should be configured in a `config.js`.
(def url #?(:clj "http://localhost:3000"
            :cljs (if (exists? js/apiRoot)
                    js/apiRoot
                    "http://test:3000")))

(def disable-with-credentials
  "Interceptor that explicitly sets the `with-credentials?` property in the request
   to `false`.  If this property is not provided, then `cljs-http` will enable it on
   the outgoing request.  This in turn causes CORS issues."
  {:enter (fn [ctx]
            (assoc-in ctx [:request :with-credentials?] false))})

(defn ^:dev/after-load init
  "Initializes using the fixed routes"
  []
  (log/debug "Initializing" (count routes) "routes")
  (rf/dispatch-sync
   [::mr/init (martian/bootstrap
               url
               routes
               {:interceptors (-> mh/default-interceptors
                                  (mi/inject disable-with-credentials
                                             :before
                                             ::mh/perform-request))})]))

(defn- set-token [opts t]
  (cond-> opts
    t (assoc :authorization (str "Bearer " t))))

(defn- add-token [db opts]
  (set-token opts (get db :auth/token)))

(rf/reg-event-fx
 ::error-handler
 [(rf/inject-cofx :local-storage ldb/storage-token-id)]
 (fn [{:keys [db local-storage]} [_ orig-evt error-evt err]]
   (let [{:keys [refresh-token]} local-storage]
     {:dispatch (if (and (= 401 (:status err)) refresh-token)
                  ;; Try refreshing the token.  Only when that fails too,
                  ;; we should re-login.
                  [::refresh-token refresh-token orig-evt]
                  (do
                    (log/debug "Got error:" (clj->js err))
                    (conj error-evt err)))})))

(rf/reg-event-fx
 ::refresh-token
 (fn [{:keys [db]} [_ refresh-token orig-evt]]
   (let [req (case (ldb/provider db)
               :github :github-refresh
               :bitbucket :bitbucket-refresh)]
     (log/debug "Attempting to refresh using token" refresh-token)
     {:dispatch [:martian.re-frame/request
                 req
                 {:refresh {:refresh-token refresh-token}}
                 [::refresh-token--success orig-evt]
                 [::refresh-token--failed]]})))

(rf/reg-event-fx
 ::refresh-token--success
 [(rf/inject-cofx :local-storage ldb/storage-token-id)]
 (fn [{:keys [db local-storage]} [_ orig-evt {{:keys [token] :as body} :body}]]
   (letfn [(update-token [evt t]
             (vec (concat (take 2 evt)
                          [(set-token (nth evt 2) t)]
                          (drop 3 evt))))]
     (log/debug "Token successfully refreshed, dispatching original event:" (str orig-evt))
     {:db (-> db
              (ldb/set-token token)
              (ldb/set-provider-token (or (:github-token body) (:bitbucket-token body))))
      :dispatch (update-token orig-evt token)
      ;; Update local storage with new tokens
      :local-storage [ldb/storage-token-id
                      (merge local-storage
                             (select-keys body [:token :refresh-token :github-token :bitbucket-token]))]})))

(rf/reg-event-fx
 ::refresh-token--failed
 (fn [_ _]
   ;; In any case, redirect to login page.  Even for non-401 errors, because what else
   ;; can we do?
   {:dispatch [:route/goto :page/login]}))

;; Takes the token from the db and adds it to the martian request
(rf/reg-event-fx
 :secure-request
 (fn [{:keys [db]} [_ req args on-success on-failure]]
   (let [orig [:martian.re-frame/request
               req
               (add-token db args)
               on-success]]
     {:dispatch (conj orig [::error-handler (conj orig on-failure) on-failure])})))

(defn api-url
  "Constructs a url to the api for given path"
  [path]
  (str url path))
