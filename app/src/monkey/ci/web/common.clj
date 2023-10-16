(ns monkey.ci.web.common
  (:require [camel-snake-kebab.core :as csk]
            [clojure.core.async :refer [go >!]]
            [monkey.ci.events :as e]
            [muuntaja.core :as mc]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware
             [muuntaja :as rrmm]
             [parameters :as rrmp]]))

(defn- from-context [req obj]
  (get-in req [:reitit.core/match :data :monkey.ci.web.handler/context obj]))

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
              (>! evt))))

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

(def default-middleware
  [rrmp/parameters-middleware
   rrmm/format-middleware
   rrc/coerce-exceptions-middleware
   rrc/coerce-request-middleware
   rrc/coerce-response-middleware])
