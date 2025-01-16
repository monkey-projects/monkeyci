(ns monkey.ci.gui.martian
  (:require [martian.core :as martian]
            [martian.cljs-http :as mh]
            [martian.interceptors :as mi]
            [martian.re-frame :as mr]
            [monkey.ci.gui.local-storage]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.db :as ldb]
            [re-frame.core :as rf]
            [schema.core :as s]))

(def customer-path ["/customer/" :customer-id])
(def repo-path (into customer-path ["/repo/" :repo-id]))
(def build-path (into repo-path ["/builds/" :build-id]))
(def param-path (into customer-path ["/param/" :param-id]))
(def user-path ["/user/" :user-id])

(def customer-schema
  {:customer-id s/Str})

(def repo-schema
  (assoc customer-schema
         :repo-id s/Str))

(def build-schema
  (assoc repo-schema :build-id s/Str))

(def param-schema
  (assoc customer-schema
         :param-id s/Str))

(def user-schema
  {:user-id s/Str})

;; TODO Use the same source as backend for this
(s/defschema NewCustomer
  {:name s/Str})

(s/defschema Label
  {:name s/Str
   :value s/Str})

(s/defschema UpdateRepo
  {:customer-id s/Str
   :name s/Str
   :url s/Str
   (s/optional-key :main-branch) s/Str
   (s/optional-key :github-id) s/Int
   (s/optional-key :labels) [Label]})

(s/defschema log-query-schema
  {:query s/Str
   :start s/Int
   :direction s/Str
   (s/optional-key :end) s/Int})

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

(def Date #"\d{4}-\d{2}-\d{2}")

(defn public-route [conf]
  (merge {:method :get
          :produces #{"application/edn"}
          :consumes #{"application/edn"}}
         conf))

(defn api-route [conf]
  (-> conf
      (assoc :headers-schema {(s/optional-key :authorization) s/Str})
      (public-route)))

(def routes
  [(api-route
    {:route-name :get-customer
     :path-parts customer-path
     :path-schema customer-schema})

   (api-route
    {:route-name :create-customer
     :method :post
     :path-parts ["/customer"]
     :body-schema {:customer NewCustomer}})

   (api-route
    {:route-name :search-customers
     :path-parts ["/customer"]
     :query-schema {(s/optional-key :name) s/Str
                    (s/optional-key :id) s/Str}})

   (api-route
    {:route-name :get-recent-builds
     :path-parts (into customer-path ["/builds/recent"])
     :path-schema customer-schema})

   (api-route
    {:route-name :get-customer-latest-builds
     :path-parts (into customer-path ["/builds/latest"])
     :path-schema customer-schema})

   (api-route
    {:route-name :get-customer-params
     :path-parts (into customer-path ["/param"])
     :path-schema customer-schema})

   (api-route
    {:route-name :update-customer-params
     :path-parts (into customer-path ["/param"])
     :path-schema customer-schema
     :method :post
     :body-schema {:params [UpdateParamSet]}})

   (api-route
    {:route-name :create-param-set
     :path-parts (into customer-path ["/param"])
     :path-schema customer-schema
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
     :method :delete})

   (api-route
    {:route-name :get-customer-ssh-keys
     :path-parts (into customer-path ["/ssh-keys"])
     :path-schema customer-schema})

   (api-route
    {:route-name :update-customer-ssh-keys
     :path-parts (into customer-path ["/ssh-keys"])
     :path-schema customer-schema
     :method :put
     :body-schema {:ssh-keys [SshKey]}})

   (api-route
    {:route-name :get-customer-stats
     :path-parts (into customer-path ["/stats"])
     :path-schema customer-schema
     :query-schema {(s/optional-key :since) s/Int
                    (s/optional-key :until) s/Int}
     :method :get})

   (api-route
    {:route-name :get-customer-credits
     :path-parts (into customer-path ["/credits"])
     :path-schema customer-schema
     :method :get})

   (api-route
    {:route-name :get-credit-issues
     :path-parts ["/admin/credits/" :customer-id]
     :path-schema customer-schema
     :method :get})

   (api-route
    {:route-name :create-credit-issue
     :path-parts ["/admin/credits/" :customer-id "/issue"]
     :path-schema customer-schema
     :body-schema {:credits UserCredits}
     :method :post})

   (api-route
    {:route-name :credits-issue-all
     :path-parts ["/admin/credits/issue"]
     :body-schema {:issue-all {:date Date}}
     :method :post})

   (api-route
    {:route-name :get-credit-subs
     :path-parts ["/admin/credits/" :customer-id "/subscription"]
     :path-schema customer-schema
     :method :get})

   (api-route
    {:route-name :create-credit-sub
     :path-parts ["/admin/credits/" :customer-id "/subscription"]
     :path-schema customer-schema
     :body-schema {:sub CreditSubscription}
     :method :post})

   (api-route
    {:route-name :get-user-customers
     :path-parts (into user-path ["/customers"])
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
                   {:customer-id s/Str}}})

   (api-route
    {:route-name :get-repo
     :method :get
     :path-parts repo-path
     :path-schema repo-schema})

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
     :path-schema repo-schema})

   (api-route
    {:route-name :get-builds
     :path-parts (into repo-path ["/builds"])
     :path-schema repo-schema})

   (api-route
    {:route-name :trigger-build
     :method :post
     :path-parts (into repo-path ["/builds/trigger"])
     :path-schema repo-schema
     :query-schema {(s/optional-key :branch) s/Str
                    (s/optional-key :tag) s/Str
                    (s/optional-key :commit-id) s/Str}})

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
     :path-schema build-schema})

   (api-route
    {:route-name :download-log
     :path-parts ["/logs/" :customer-id "/entries"]
     :path-schema customer-schema
     :query-schema log-query-schema
     :produces #{"application/json"}})

   (api-route
    {:route-name :get-log-stats
     :path-parts ["/logs/" :customer-id "/stats"]
     :path-schema customer-schema
     :query-schema log-query-schema
     :produces #{"application/json"}})

   (api-route
    {:route-name :get-log-label-values
     :path-parts ["/logs/" :customer-id "/label/" :label "/values"]
     :path-schema (assoc customer-schema :label s/Str)
     :query-schema log-query-schema
     :produces #{"application/json"}})
   
   (api-route
    {:route-name :watch-github-repo
     :method :post
     :path-parts (conj customer-path "/repo/github/watch")
     :path-schema customer-schema
     :body-schema {:repo {:name s/Str
                          :url s/Str
                          :customer-id s/Str
                          :github-id s/Int}}
     :consumes ["application/edn"]})

   (api-route
    {:route-name :watch-bitbucket-repo
     :method :post
     :path-parts (conj customer-path "/repo/bitbucket/watch")
     :path-schema customer-schema
     :body-schema {:repo {:name s/Str
                          :url s/Str
                          :customer-id s/Str
                          :workspace s/Str
                          :repo-slug s/Str
                          :token s/Str}}
     :consumes ["application/edn"]})

   (api-route
    {:route-name :unwatch-github-repo
     :method :post
     :path-parts (conj repo-path "/github/unwatch")
     :path-schema repo-schema})

   (api-route
    {:route-name :unwatch-bitbucket-repo
     :method :post
     :path-parts (conj repo-path "/bitbucket/unwatch")
     :path-schema repo-schema
     :body-schema {:repo {:token s/Str}}})

   (api-route
    {:route-name :search-bitbucket-webhooks
     :path-parts (conj customer-path "/webhook/bitbucket")
     :path-schema customer-schema
     :query-schema {(s/optional-key :repo-id) s/Str
                    (s/optional-key :workspace) s/Str
                    (s/optional-key :repo-slug) s/Str
                    (s/optional-key :bitbucket-id) s/Str}})

   (public-route
    {:route-name :get-version
     :method :get
     :path-parts ["/version"]})
   
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
     :path-parts ["/github/config"]})

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
     :path-parts ["/bitbucket/config"]})

   (public-route
    {:route-name :admin-login
     :method :post
     :path-parts ["/admin/login"]
     :body-schema {:creds {:username s/Str
                           :password s/Str}}})])

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
               {:interceptors (concat martian/default-interceptors
                                      [mi/default-coerce-response
                                       mi/default-encode-body
                                       disable-with-credentials
                                       mh/perform-request])})]))

(defn- add-token [db opts]
  (let [t (get db :auth/token)]
    (cond-> opts
      t (assoc :authorization (str "Bearer " t)))))

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
 (fn [{:keys [db local-storage]} [_ orig-evt {:keys [body]}]]
   {:db (-> db
            (ldb/set-token (:token body))
            (ldb/set-provider-token (or (:github-token body) (:bitbucket-token body))))
    :dispatch orig-evt
    ;; Update local storage with new tokens
    :local-storage [ldb/storage-token-id
                    (merge local-storage
                           (select-keys body [:token :refresh-token :github-token :bitbucket-token]))]}))

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
