(ns monkey.ci.gui.dashboard.routing
  "Dashboard specific routes"
  (:require [monkey.ci.gui.routing :as r]))

(def routes
  "Dashboard nav routes"
  [["/" :page/root]
   ["/login" {:name :page/login
              :public? true}]
   ["/d/:org-id" :page/org-dashboard]])

(def router (r/make-router routes))

(defn start! []
  (r/start-router router))
