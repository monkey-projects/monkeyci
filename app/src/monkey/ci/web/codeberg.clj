(ns monkey.ci.web.codeberg
  "Codeberg-specific functionality, mainly for authentication."
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.web
             [common :as c]
             [oauth2 :as oauth2]]
            [ring.util.response :as rur]))

(defn- codeberg-config
  "Fetches codeberg configuration from the request"
  [req]
  (c/from-rt req (comp :codeberg :config)))

(defn- ->oauth-user [{:keys [id email] :as u}]
  {:email email
   :sid [:codeberg id]})

(def oidc-config {:convert-user ->oauth-user
                  :request-token-url "https://codeberg.org/login/oauth/access_token"
                  :user-info-url "https://codeberg.org/api/v1/user"
                  :get-creds codeberg-config
                  :set-params (fn [req p]
                                ;; Codeberg expects data in the body, not query params like github
                                (let [json (-> (update p :grant_type #(or % "authorization_code"))
                                               (json/generate-string))]
                                  (-> req
                                      (assoc :body json)
                                      (update :headers merge {"Content-Type" "application/json"
                                                              "Content-Length" (count json)}))))})

(def login (oauth2/oidc-login oidc-config))
(def refresh (oauth2/oidc-refresh oidc-config))

(defn get-config
  "Lists public codeberg configuration to use"
  [req]
  (-> req
      (codeberg-config)
      (select-keys [:client-id])
      (rur/response)))
