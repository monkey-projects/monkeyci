(ns monkey.ci.web.bitbucket
  "Bitbucket specific endpoints, mainly for authentication or push callbacks."
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.runtime :as rt]
            [monkey.ci.web
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

(defn- request-user-info [token]
  (when token 
    (-> @(http/get "https://api.bitbucket.org/2.0/user"
                   {:headers {"Accept" "application/json"
                              "Authorization" (str "Bearer " token)}})
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

