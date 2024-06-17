(ns monkey.ci.web.bitbucket
  "Bitbucket specific endpoints, mainly for authentication or push callbacks."
  (:require [aleph.http :as http]
            [monkey.ci
             [config :as config]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.web
             [common :as c]
             [oauth2 :as oauth2]]
            [ring.util.response :as rur]))

(defn- process-reply [r]
  (if (<= 400 (:status r))
    (throw (ex-info "Got error response" r)))
  (cond-> r
    (= "application/json" (get-in r [:headers "content-type"]))
    (update :body c/parse-json)))

(defn- request-access-token [req]
  (let [code (get-in req [:parameters :query :code])
        {:keys [client-secret client-id]} (c/from-rt req (comp :github rt/config))]
    (-> @(http/post "https://bitbucket.org/site/oauth2/access_token"
                    {:form-params {:grant_type "authorization_code"
                                   :code code}
                     :headers {"Accept" "application/json"
                               "Authorization" (str "Basic " (u/->base64 (str client-id ":" client-secret)))}})
        (process-reply))))

(defn- ->oauth-user [{:keys [id email]}]
  {:email email
   :sid [:bitbucket id]})

(defn- request-user-info [token]
  (when token 
    (-> @(http/get "https://api.bitbucket.org/2.0/user"
                   {:headers {"Accept" "application/json"
                              "Authorization" (str "Bearer " token)}})
        (process-reply)
        ;; TODO Check for failures
        :body
        (->oauth-user))))

(def login (oauth2/login-handler
            request-access-token
            request-user-info))

(defn get-config
  "Lists public bitbucket config, necessary for authentication."
  [req]  
  (rur/response {:client-id (c/from-rt req (comp :client-id :bitbucket rt/config))}))

(defmethod config/normalize-key :bitbucket [_ conf]
  conf)
