(ns ^:dev/always monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.views :as login]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.build.views :as build]
            [monkey.ci.gui.job.views :as job]
            [monkey.ci.gui.customer.views :as customer]
            [monkey.ci.gui.home.views :as home]
            [monkey.ci.gui.params.views :as params]
            [monkey.ci.gui.repo.views :as repo]
            [monkey.ci.gui.routing :as r]
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
   :page/customer-params params/page
   :page/add-github-repo customer/add-github-repo-page
   :page/add-bitbucket-repo customer/add-bitbucket-repo-page
   :page/repo repo/page
   :page/repo-edit repo/edit
   :page/job job/page})

(defn render-page [route]
  (log/debug "Rendering page for route:" (str (r/route-name route)))
  (if-let [p (get pages (r/route-name route))]
    [p route]
    [:div.alert.alert-warning
     [:h3 "Page not found"]
     [:p "No page exists for route " [:b (str (r/route-name route))]]]))

(defn render []
  (let [r (rf/subscribe [:route/current])
        t (rf/subscribe [:login/token])]
    ;; If no token found, redirect to login
    (if (or @t (r/public? (r/route-name @r)))
      [render-page @r]
      (rf/dispatch [:login/login-and-redirect]))))
