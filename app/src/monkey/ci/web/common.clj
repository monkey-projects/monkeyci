(ns monkey.ci.web.common
  (:require [buddy.auth :as ba]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.core.async :refer [go <! <!! >!]]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.events :as e]
            [muuntaja.core :as mc]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware
             [exception :as rrme]
             [muuntaja :as rrmm]
             [parameters :as rrmp]]
            [ring.util.response :as rur]))

(defn req->ctx [req]
  (get-in req [:reitit.core/match :data ::context]))

(defn from-context [req f]
  (f (req->ctx req)))

(defn req->bus
  "Gets the event bus from the request data"
  [req]
  (from-context req :event-bus))

(defn req->storage
  "Retrieves storage object from the request context"
  [req]
  (from-context req :storage))

(defn post-event
  "Posts event to the bus found in the request data.  Returns an async channel
   holding `true` if the event is posted."
  [req evt]
  (go (some-> (req->bus req)
              (e/channel)
              (>! (e/add-time evt)))))

(defn make-muuntaja
  "Creates muuntaja instance with custom settings"
  []
  (mc/create
   (-> mc/default-options
       (assoc-in 
        ;; Convert keys to kebab-case
        [:formats "application/json" :decoder-opts]
        {:decode-key-fn csk/->kebab-case-keyword})
       (assoc-in
        [:formats "application/json" :encoder-opts]
        {:encode-key-fn (comp csk/->camelCase name)}))))

(defn- exception-logger [h]
  (fn [req]
    (try
      (h req)
      (catch Exception ex
        ;; Log and rethrow
        (log/error (str "Got error while handling request" (:uri req)) ex)
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

(def default-middleware
  [rrmp/parameters-middleware
   rrmm/format-middleware
   exception-middleware
   exception-logger
   rrc/coerce-exceptions-middleware
   rrc/coerce-request-middleware
   rrc/coerce-response-middleware])

(defn make-app [router]
  (ring/ring-handler
   router
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))))

(defn posting-handler
  "Handles the incoming http request by dispatching it to the handler `h`
   which returns an event, which is then posted."
  [req h]
  ;; TODO Refactor this into an interceptor where the handler is able to
  ;; return a custom response in addition to dispatching an event.
  ;; Httpkit can't handle channels so read it here
  (<!!
   (go
     (rur/status
      (let [evt (h req)]
        (if (or (nil? evt) (<! (post-event req evt)))
          200
          500))))))

(defn parse-json [s]
  (if (string? s)
    (json/parse-string s csk/->kebab-case-keyword)
    (with-open [r (io/reader s)]
      (json/parse-stream r csk/->kebab-case-keyword))))
