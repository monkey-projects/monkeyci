(ns monkey.ci.web.middleware
  "Middleware for web requests.  Could be that we move this to interceptors later."
  (:require [buddy.auth :as ba]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.web
             [common :as c]
             [response :as r]]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware
             [exception :as rrme]
             [muuntaja :as rrmm]
             [parameters :as rrmp]]))

(defn exception-logger [h]
  (fn [req]
    (try
      (h req)
      (catch Exception ex
        ;; Log and rethrow
        (log/error (str "Got error while handling request: " (:uri req)) ex)
        (throw ex)))))

(def exception-middleware
  (rrme/create-exception-middleware
   (merge rrme/default-handlers
          {:auth/unauthorized (fn [e req]
                                (if (ba/authenticated? req)
                                  {:status 403
                                   :body (.getMessage e)}
                                  {:status 401
                                   :body "Unauthenticated"}))})))

(defn stringify-body
  "Since the raw body could be read more than once (security, content negotiation...),
   this interceptor replaces it with a string that can be read multiple times.  This
   should only be used for requests that have reasonably small bodies!  In other
   cases, the body could be written to a temp file."
  [h]
  (fn [req]
    (-> req
        (mc/update-existing :body (fn [s]
                                    (when (instance? java.io.InputStream s)
                                      (slurp s))))
        (h))))

(defn kebab-case-query
  "Middleware that converts any query params to kebab-case, to make them more idiomatic."
  [h]
  (fn [req]
    (-> req
        (mc/update-existing-in [:parameters :query] (partial mc/map-keys csk/->kebab-case-keyword))
        (h))))

(defn log-request
  "Just logs the request, for monitoring or debugging purposes."
  [h]
  (fn [req]
    (log/info "Handling request:" (select-keys req [:uri :request-method :parameters]))
    (h req)))

(defn passthrough-middleware
  "No-op middleware, just passes the request to the parent handler."
  [h]
  (fn [req]
    (h req)))

(defn post-events
  "Middleware that posts any events that are found in the response map"
  [h]
  (fn [req]
    (let [resp (h req)
          evt (r/get-events resp)]
      (when (not-empty evt)
        (em/post-events (c/req->mailman req) evt))
      (r/remove-events resp))))

(defn resolve-org-id
  "Replaces the org id when a display id is given.  How resolving happens is 
   delegated to a resolver fn that takes the req and the org display id.  Since
   there is no sure way to distinguish between a display id and a cuid, the
   resolver should be able to handle both situations."
  [h resolver]
  (fn [req]
    (-> req
        (mc/update-existing-in [:parameters :path :org-id] (partial resolver req))
        (h))))

(defn replace-body-org-id
  "Since the `org-id` can be a cuid or a display id, this interceptor replaces the
   value in the request body with the one in the path params."
  [h]
  (fn [req]
    (-> req
        (mc/update-existing-in [:parameters :body :org-id] (constantly (c/org-id req)))
        (h))))

(def default-middleware
  "Default middleware for http servers"
  [rrmp/parameters-middleware
   rrmm/format-middleware
   exception-middleware
   exception-logger
   rrc/coerce-exceptions-middleware
   rrc/coerce-request-middleware
   rrc/coerce-response-middleware])
