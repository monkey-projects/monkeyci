(ns monkey.ci.web.codeberg
  "Codeberg-specific functionality, mainly for authentication."
  (:require [aleph.http :as http]
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

(def oidc-config {:convert-user ->oauth-user
                  :request-token-url "https://codeberg.com/login/oauth/access_token"
                  :user-info-url "https://codeberg.com/login/oauth/userinfo"
                  :get-creds codeberg-config})

(def login (oauth2/oidc-login oidc-config))
(def refresh (oauth2/oidc-refresh oidc-config))

(defn get-config
  "Lists public codeberg configuration to use"
  [req]
  (rur/response {:client-id (:client-id (codeberg-config req))}))
