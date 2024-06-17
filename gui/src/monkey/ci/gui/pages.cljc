(ns ^:dev/always monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.views :as login]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.build.views :as build]
            [monkey.ci.gui.job.views :as job]
            [monkey.ci.gui.customer.views :as customer]
            [monkey.ci.gui.home.views :as home]
            [monkey.ci.gui.repo.views :as repo]
            [re-frame.core :as rf]))

(def pages
  {:page/root home/page
   :page/build build/page
   :page/login login/page
   :page/github-callback login/github-callback
   :page/bitbucket-callback login/bitbucket-callback
   :page/customer customer/page
   :page/customer-new customer/page-new
   :page/customer-join home/page-join
   :page/add-repo customer/add-repo-page
   :page/repo repo/page
   :page/repo-edit repo/edit
   :page/job job/page})

(def route-name (comp :name :data))
(def public? #{:page/login :page/github-callback :page/bitbucket-callback})

(defn render-page [route]
  (log/debug "Rendering page for route:" (str (route-name route)))
  (if-let [p (get pages (get-in route [:data :name]))]
    [p route]
    [:div.alert.alert-warning
     [:h3 "Page not found"]
     [:p "No page exists for route " [:b (str (get-in route [:data :name]))]]]))

(defn render []
  (let [r (rf/subscribe [:route/current])
        t (rf/subscribe [:login/token])]
    ;; If no token found, redirect to login
    (if (or @t (public? (route-name @r)))
      [render-page @r]
      (rf/dispatch [:login/login-and-redirect]))))
