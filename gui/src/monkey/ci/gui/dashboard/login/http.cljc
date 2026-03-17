(ns monkey.ci.gui.dashboard.login.http
  (:require [monkey.ci.gui.martian :as m]))

;; TODO Replace this with martian code

(defn endpoint [path]
  (str m/url path))

(def default-headers
  {"Content-Type"  "application/json"
   "Accept"        "application/json"
   "X-Client-Ver"  "3.4.1"})

(defn with-auth [headers token]
  (assoc headers "Authorization" (str "Bearer " token)))

(defn ->json [m]
  (.stringify js/JSON (clj->js m)))

(defn <-json [s]
  (js->clj (.parse js/JSON s) :keywordize-keys true))
