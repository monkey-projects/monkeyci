(ns monkey.ci.gui.dashboard.core
  "Dashboard entry point"
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.dashboard.login.views :as lv]
            [monkey.ci.gui.dashboard.main.events :as e]
            [monkey.ci.gui.dashboard.main.views :as mv]
            [monkey.ci.gui.dashboard.routing :as dr]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn not-implemented [route]
  [:<>
   [:h3 "Not Implemented"]
   [:p "Oops!  Looks like this page has not been implemented yet."]
   [:p (:path route)]])

(defn root-page []
  [:h1 "Default Root Page"])

(def pages
  {:page/root root-page
   :page/login lv/login-page
   :page/oauth-callback lv/github-callback-page
   :page/org-dashboard mv/main-page})

(defn render-page [route]
  (log/debug "Rendering page for route:" route)
  (let [p (get pages (r/route-name route) not-implemented)]
    [p route]))

(defn render-route []
  (let [r (rf/subscribe [:route/current])
        ;;u (rf/subscribe [:login/user])
        ]
    (if (or (r/public? @r) #_@u)
      (if @r
        (render-page @r)
        (root-page))
      (rf/dispatch [:route/goto :page/login]))))

(defn ^:dev/after-load reload []
  (c/reload [render-route]))

(defn init []
  (dr/start!)
  (rf/dispatch-sync [::e/initialize-db])
  (m/init)
  (reload))
