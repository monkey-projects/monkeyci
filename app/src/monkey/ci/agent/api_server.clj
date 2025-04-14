(ns monkey.ci.agent.api-server
  "Functionality for running a multi-build api server.  This is very similar to the
   single-build api server, but it allows to register multiple builds, each with
   their own token.  The token must be specified in the request, and it allows the
   request handlers to determine the build associated with the request."
  (:require [aleph.http :as http]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.web
             [common :as c]
             [middleware :as wm]]
            [reitit.ring :as ring]
            [ring.util.response :as rur]))

(defn- set-token [req token]
  (assoc req ::token token))

(def get-token ::token)

(def bearer-regex #"^Bearer (.*)$")

(defn- extract-token [auth]
  (some->> auth
           (re-matches bearer-regex)
           (second)))

(defn security-middleware
  "Middleware that checks if the authorization header matches the specified token"
  [handler builds]
  (fn [req]
    (let [token (-> (get-in req [:headers "authorization"])
                    (extract-token))]
      (if (contains? @builds token)
        (-> req
            (set-token token)
            (handler))
        (rur/status 401)))))

(defn build-middleware
  "Looks up the build associated with the security token and stores it in the context,
   so it can be used by the request handlers."
  [handler builds]
  (fn [req]
    (let [b (get @builds (get-token req))]
      (-> req
          (bas/set-build b)
          (handler)))))

(defn make-router [{:keys [builds] :as conf}]
  (ring/router
   bas/routes
   {:data {:middleware (concat [[security-middleware builds]
                                [build-middleware builds]]
                               wm/default-middleware)
           :muuntaja (c/make-muuntaja)
           :coercion reitit.coercion.schema/coercion
           bas/context conf}}))

(defn start-server [conf]
  (-> (http/start-server
       (c/make-app (make-router conf))
       {:port 0})
      (bas/server-with-port)))
