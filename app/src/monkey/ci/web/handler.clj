(ns monkey.ci.web.handler
  "Handler for the web server"
  (:require [aleph
             [http :as aleph]
             [netty :as netty]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [runtime :as rt]
             [utils :as u]
             [version :as v]]
            [monkey.ci.metrics.core :as metrics]
            [monkey.ci.web
             [admin :as admin]
             [api :as api]
             [auth :as auth]
             [bitbucket :as bitbucket]
             [common :as c]
             [github :as github]
             [http :as wh]
             [middleware :as wm]]
            [monkey.ci.web.api
             [crypto :as crypto-api]
             [org :as org-api]
             [invoice :as inv-api]
             [join-request :as jr-api]
             [params :as param-api]
             [repo :as repo-api]
             [ssh-keys :as ssh-api]]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [ring.middleware.cors :as cors]
            [ring.util.response :as rur]
            [schema.core :as s]))

(defn health [_]
  ;; TODO Make this more meaningful
  (wh/text-response "ok"))

(defn version [_]
  (wh/text-response (v/version)))

(defn metrics [req]
  (if-let [m (c/from-rt req :metrics)]
    (wh/text-response (metrics/scrape m))
    (rur/status 204)))

(def Id c/Id)
(def Name c/Name)

(defn- assoc-id [s]
  (assoc s (s/optional-key :id) Id))

(s/defschema Label
  {:name Name
   :value c/not-empty-str})

(s/defschema NewOrg
  {:name Name})

(s/defschema UpdateOrg
  (assoc-id NewOrg))

(s/defschema SearchOrg
  {(s/optional-key :name) s/Str
   (s/optional-key :id) s/Str})

(s/defschema NewWebhook
  {:org-id Id
   :repo-id Id})

(s/defschema UpdateWebhook
  (assoc-id NewWebhook))

(s/defschema NewRepo
  {:org-id Id
   :name Name
   :url s/Str
   (s/optional-key :main-branch) Id
   (s/optional-key :labels) [Label]
   (s/optional-key :public) s/Bool})

(s/defschema UpdateRepo
  (-> NewRepo
      (assoc-id)
      (assoc (s/optional-key :github-id) s/Int)))

(s/defschema WatchGithubRepo
  (-> NewRepo
      (assoc-id)
      (assoc :github-id s/Int)))

(s/defschema WatchBitBucketRepo
  (-> NewRepo
      (assoc-id)
      (assoc :workspace s/Str
             :repo-slug s/Str
             :token s/Str)))

(s/defschema ParameterValue
  {:name s/Str
   :value s/Str})

(s/defschema LabelFilterConjunction
  {:label s/Str
   :value s/Str})

(s/defschema LabelFilter
  [LabelFilterConjunction])

(s/defschema Parameters
  {(s/optional-key :id) Id
   (s/optional-key :org-id) Id
   :parameters [ParameterValue]
   (s/optional-key :description) s/Str
   :label-filters [LabelFilter]})

(s/defschema SshKeys
  {(s/optional-key :id) Id
   (s/optional-key :org-id) Id
   :private-key s/Str
   :public-key s/Str ; TODO It may be possible to extract public key from private
   (s/optional-key :description) s/Str
   :label-filters [LabelFilter]})

(s/defschema User
  {:type s/Str
   :type-id s/Any
   (s/optional-key :id) Id ; Internal id
   (s/optional-key :email) s/Str
   (s/optional-key :orgs) [Id]})

(s/defschema EmailRegistration
  {:email s/Str})

(s/defschema TriggerParams
  {(s/optional-key :branch) s/Str
   (s/optional-key :tag) s/Str
   (s/optional-key :commit-id) s/Str
   ;; Accept anything as string, because some clients may send keys as keywords,
   ;; when using edn.
   (s/optional-key :params) {s/Any s/Str}})

(def since-params
  {:query {(s/optional-key :since) s/Int
           (s/optional-key :until) s/Int}})

(def webhook-routes
  ["/webhook"
   [[""
     {:auth-chain [auth/org-body-checker]}
     [["" {:post {:handler    api/create-webhook
                  :parameters {:body NewWebhook}}}]]]
    ["/health"
     {:conflicting true}
     [["" {:get
           {:handler (constantly (rur/status 200))}}]]]
    ["/github"
     {:conflicting true}
     [[""
       {:post       {:handler    github/app-webhook
                     :parameters {:body s/Any}}
        :middleware [:github-app-security]}]
      ["/app"
       {:post       {:handler    github/app-webhook
                     :parameters {:body s/Any}}
        :middleware [:github-app-security]}]
      ["/:id"
       {:conflicting true
        :post        {:handler    github/webhook
                      :parameters {:path {:id Id}
                                   :body s/Any}}
        :middleware  [:github-security]}]]]
    ["/bitbucket/:id"
     {:post       {:handler    bitbucket/webhook
                   :parameters {:path {:id Id}
                                :body s/Any}}
      :middleware [:bitbucket-security]}]
    ["/:webhook-id"
     {:parameters  {:path {:webhook-id c/Id}}
      :auth-chain  [auth/webhook-org-checker]
      :conflicting true}
     [["" {:get    {:handler api/get-webhook}
           :put    {:handler    api/update-webhook
                    :parameters {:body UpdateWebhook}}
           :delete {:handler api/delete-webhook}}]]]]])

(def org-parameter-routes
  ["/param"
   [["" {:get  {:handler param-api/get-org-params}
         :put  {:handler param-api/update-params
                :parameters {:body [Parameters]}}
         :post {:handler param-api/create-param
                :parameters {:body Parameters}}}]
    ["/:param-id"
     {:parameters {:path {:param-id Id}}
      :get {:handler param-api/get-param}
      :put {:handler param-api/update-param
            :parameters {:body Parameters}}
      :delete {:handler param-api/delete-param}}]]])

(def org-ssh-keys-routes
  ["/ssh-keys"
   {:get {:handler ssh-api/get-org-ssh-keys}
    :put {:handler ssh-api/update-ssh-keys
          :parameters {:body [SshKeys]}}}])

(def repo-parameter-routes
  ["/param"
   {:auth-chain ^:replace [auth/org-auth-checker]}
   [["" {:get {:handler param-api/get-repo-params}}]]])

(def repo-ssh-keys-routes
  ["/ssh-keys"
   {:auth-chain ^:replace [auth/org-auth-checker]}
   [["" {:get {:handler ssh-api/get-repo-ssh-keys}}]]])

(def repo-webhook-routes
  ["/webhooks"
   {:auth-chain ^:replace [auth/org-auth-checker]}
   [["" {:get {:handler repo-api/list-webhooks}}]]])

(def log-routes
  ["/logs"                              ; Deprecated, use loki instead
   [[""
     {:get {:handler api/list-build-logs}}]
    ["/download"
     {:get {:handler api/download-build-log
            :parameters {:query {:path s/Str}}}}]]])

(def artifact-routes
  ["/artifact"
   [["" {:get {:handler api/get-build-artifacts}}]
    ["/:artifact-id"
     {:parameters {:path {:artifact-id s/Str}}}
     [[""
       {:get {:handler api/get-artifact}}]
      ["/download"
       {:get {:handler api/download-artifact}}]]]]])

(def build-routes
  ["/builds" ; TODO Replace with singular
   {:conflicting true}
   [["" {:get {:handler api/get-builds}}]
    ["/trigger"
     {:post {:handler api/trigger-build
             ;; For backwards compabitility, we also allow query params
             :parameters {:query TriggerParams
                          :body TriggerParams}}}]
    ["/latest"
     {:get {:handler api/get-latest-build}}]
    ["/:build-id"
     {:parameters {:path {:build-id Id}}}
     [[""
       {:get {:handler api/get-build}}]
      ["/retry"
       {:post {:handler api/retry-build}}]
      ["/cancel"
       {:post {:handler api/cancel-build}}]
      log-routes
      artifact-routes]]]])

(def watch-routes
  ["" [["/github"
        [["/watch" {:post {:handler github/watch-repo
                           :parameters {:body WatchGithubRepo}}}]]]
       ["/bitbucket"
        [["/watch" {:post {:handler bitbucket/watch-repo
                           :parameters {:body WatchBitBucketRepo}}}]]]]])

(def unwatch-routes
  ["" [["/github"
        [["/unwatch" {:post {:handler github/unwatch-repo}}]]]
       ["/bitbucket"
        [["/unwatch" {:post {:handler bitbucket/unwatch-repo
                             :parameters
                             {:body {:token s/Str}}}}]]]]])

(def repo-routes
  (letfn [(add-repo-checker [routes]
            (u/update-nth routes 1 u/update-nth 1 assoc :auth-chain [auth/public-repo-checker]))]
    ["/repo"
     (-> (c/generic-routes
          {:creator repo-api/create-repo
           :updater repo-api/update-repo
           :getter  repo-api/get-repo
           :deleter repo-api/delete-repo
           :new-schema NewRepo
           :update-schema UpdateRepo
           :id-key :repo-id
           :child-routes [repo-parameter-routes
                          repo-ssh-keys-routes
                          repo-webhook-routes
                          build-routes
                          unwatch-routes]})
         (conj watch-routes)
         (add-repo-checker))]))

(s/defschema JoinRequestSchema
  {:org-id Id
   (s/optional-key :message) s/Str})

(s/defschema JoinRequestResponse
  {(s/optional-key :message) s/Str})

(def org-join-request-routes
  ["/join-request"
   [["" {:get {:handler jr-api/list-org-join-requests}}]
    ["/:request-id"
     {:parameters {:path {:request-id Id}}}
     [["/approve"
       {:post {:handler jr-api/approve-join-request
               :parameters {:body JoinRequestResponse}}}]
      ["/reject"
       {:post {:handler jr-api/reject-join-request
               :parameters {:body JoinRequestResponse}}}]]]]])

(def event-stream-routes
  ["/events" {:get {:handler api/event-stream
                    :parameters {:query {(s/optional-key :authorization) s/Str}}}}])

(def org-build-routes
  ["/builds"
   [["/recent"
     {:get {:handler org-api/recent-builds
            :parameters (assoc-in since-params
                                  [:query (s/optional-key :n)] s/Int)}}]
    ["/latest"
     {:get {:handler org-api/latest-builds}}]]])

(def stats-routes
  ["/stats" {:get {:handler org-api/stats
                   :parameters (assoc-in since-params
                                         [:query (s/optional-key :zone-offset)] s/Str)}}])

(def credit-routes
  ["/credits" {:get {:handler org-api/credits}}])

(def org-webhook-routes
  ["/webhook"
   [["/bitbucket" {:get {:handler bitbucket/list-webhooks
                         :parameters
                         {:query {(s/optional-key :repo-id) s/Str
                                  (s/optional-key :workspace) s/Str
                                  (s/optional-key :repo-slug) s/Str
                                  (s/optional-key :bitbucket-id) s/Str}}}}]]])

(s/defschema InvoiceSearchFilter
  {(s/optional-key :from-date) s/Str
   (s/optional-key :until-date) s/Str
   (s/optional-key :invoice-nr) s/Str})

(def invoice-routes
  ["/invoice"
   [[""
     {:get {:handler inv-api/search-invoices
            :parameters
            {:query InvoiceSearchFilter}}}]
    ["/:invoice-id"
     {:get {:handler inv-api/get-invoice
            :parameters
            {:path {:invoice-id Id}}}}]]])

(def crypto-routes
  ["/crypto"
   [["/decrypt-key"
     {:post {:handler crypto-api/decrypt-key
             :parameters
             {:body {:enc s/Str}}}}]]])

(def org-routes
  ["/org"
   {:auth-chain [auth/org-auth-checker]}
   (c/generic-routes
    {:creator org-api/create-org
     :updater org-api/update-org
     :getter  org-api/get-org
     :searcher org-api/search-orgs
     :new-schema NewOrg
     :update-schema UpdateOrg
     :search-schema SearchOrg
     :id-key :org-id
     :child-routes [repo-routes
                    org-parameter-routes
                    org-ssh-keys-routes
                    org-join-request-routes
                    event-stream-routes
                    org-build-routes
                    stats-routes
                    credit-routes
                    org-webhook-routes
                    invoice-routes
                    crypto-routes]})])

(def github-routes
  ["/github"
   [["/login"
     {:post
      {:handler github/login
       :parameters {:query {:code s/Str}}}}]
    ["/refresh"
     {:post
      {:handler github/refresh
       :parameters {:body {:refresh-token s/Str}}}}]
    ["/config"
     {:get
      {:handler github/get-config}}]]])

(def bitbucket-routes
  ["/bitbucket"
   [["/login"
     {:post
      {:handler bitbucket/login
       :parameters {:query {:code s/Str}}}}]
    ["/refresh"
     {:post
      {:handler bitbucket/refresh
       :parameters {:body {:refresh-token s/Str}}}}]
    ["/config"
     {:get
      {:handler bitbucket/get-config}}]]])

(def auth-routes
  ["/auth/jwks" {:get
                 {:handler auth/jwks
                  :produces #{"application/json"}}}])

(def user-join-request-routes
  ["/join-request"
   (c/generic-routes
    {:creator jr-api/create-join-request
     :getter jr-api/get-join-request
     :searcher jr-api/search-join-requests
     :deleter jr-api/delete-join-request
     :id-key :join-request-id
     :new-schema JoinRequestSchema})])

(def user-routes
  ["/user"
   {:conflicting true
    ;; Deny all requests by default, except from sysadmins
    :auth-chain [auth/deny-all auth/sysadmin-checker]}
   [[""
     {:post
      {:handler api/create-user
       :parameters {:body User}}}]
    ;; TODO Add endpoints that use the cuid instead for consistency
    ["/:user-id"
     ;; TODO Allow from authenticated users
     {:parameters
      {:path {:user-id s/Str}}}
     [["/orgs"
       {:get
        {:handler api/get-user-orgs}}]
      user-join-request-routes]]
    ["/:user-type/:type-id"
     {:parameters
      {:path {:user-type s/Str
              :type-id s/Str}}
      :get
      {:handler api/get-user}
      :put
      {:handler api/update-user
       :parameters {:body User}}}]]])

(def email-registration-routes
  ["/email-registration"
   (c/generic-routes {:getter api/get-email-registration
                      :creator api/create-email-registration
                      :deleter api/delete-email-registration
                      :id-key :email-registration-id
                      :new-schema EmailRegistration})])

(def routes
  [["/health" {:get health}]
   ["/version" {:get version}]
   ["/metrics" {:get metrics}]
   webhook-routes
   org-routes
   github-routes
   bitbucket-routes
   auth-routes
   user-routes
   email-registration-routes
   ["/admin" admin/admin-routes]])

(defn- non-dev [rt mw]
  (if (rt/dev-mode? rt)
    ;; Disable security in dev mode
    [wm/passthrough-middleware]
    mw))

(defn make-router
  ([rt routes]
   (ring/router
    routes
    {:data {:middleware (vec (concat [wm/stringify-body
                                      [cors/wrap-cors
                                       :access-control-allow-origin #".*"
                                       :access-control-allow-methods [:get :put :post :delete]
                                       :access-control-allow-credentials true]]
                                     ;; TODO Transactions for sql storage
                                     wm/default-middleware
                                     [wm/kebab-case-query
                                      wm/log-request
                                      wm/post-events
                                      :auth-chain]))
            :muuntaja (c/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ;; Wrap the runtime in a type, so reitit doesn't change the records into maps
            ::c/runtime (c/->RuntimeWrapper rt)}
     ;; Disabled, results in 405 errors for some reason
     ;;:compile rc/compile-request-coercers
     :reitit.middleware/registry
     (->> {:github-security
           [github/validate-security]
           :github-app-security
           [github/validate-security (constantly (get-in (rt/config rt) [:github :webhook-secret]))]
           :bitbucket-security
           [bitbucket/validate-security]
           :auth-chain
           [auth/auth-chain-middleware]
           :sysadmin-check
           [auth/sysadmin-authorization]}
          ;; TODO Move the dev-mode checks into the runtime startup code
          (mc/map-vals (partial non-dev rt)))}))
  ([rt]
   (make-router rt routes)))

(defn make-app [rt]
  (-> (make-router rt)
      (c/make-app)
      (auth/secure-ring-app rt)))
