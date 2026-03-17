(ns monkey.ci.gui.dashboard.routing
  "Dashboard specific routes"
  (:require [monkey.ci.gui.routing :as r]))

(def routes
  "Dashboard nav routes"
  [["/" :page/root]
   ["/d/:org-id" :page/org-dashboard]])

(defonce router (r/make-router routes))

(defn start! []
  (reset! r/router router)
  (r/start-router))
