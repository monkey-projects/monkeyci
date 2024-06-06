(ns monkey.ci.web.api.join-request
  "API functions for customer join requests"
  (:require [ring.util.response :as rur]))

(defn create-join-request [req]
  (rur/response {}))

(defn get-join-request [req])

(defn search-join-requests [req]
  (rur/response []))

(defn delete-join-request [req])
