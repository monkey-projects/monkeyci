(ns monkey.ci.web.api.admin
  (:require [ring.util.response :as rur]))

(defn issue-credits
  "Issues ad-hoc credits to a customer."
  [req]
  (rur/response {:message "todo"}))

(defn issue-auto-credits
  "Issues new credits to all customers that have subscriptions."
  [req]
  ;; TODO
  (rur/response {:message "todo"}))
