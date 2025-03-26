(ns monkey.ci.dispatcher.http
  "Http endpoints for the dispatcher.  Mainly for monitoring."
  (:require [monkey.ci.web
             [common :as wc]
             [http :as wh]]
            [reitit.ring :as rr]))

(defn health [_]
  (wh/text-response "ok"))

(def routes
  [["/health" {:get health}]])

(defn make-router
  "Creates reitit router for the dispatcher"
  [conf]
  (rr/router
   routes
   {:data {::wc/runtime (wc/->RuntimeWrapper conf)}}))

(defn make-handler [conf]
  (-> (make-router conf)
      (wc/make-app)))
