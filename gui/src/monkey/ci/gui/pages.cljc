(ns ^:dev/always monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.api-keys.views :as api-keys]
            [monkey.ci.gui.build.views :as build]
            [monkey.ci.gui.home.views :as home]
            [monkey.ci.gui.job.views :as job]
            [monkey.ci.gui.login.views :as login]
            [monkey.ci.gui.org.views :as org]
            [monkey.ci.gui.params.views :as params]
            [monkey.ci.gui.repo.views :as repo]
            [monkey.ci.gui.ssh-keys.views :as ssh-keys]
            [monkey.ci.gui.webhooks.views :as webhooks]
            [re-frame.core :as rf]))

(def pages
  {:page/root home/page
   :page/build build/page
   :page/login login/page
   :page/github-callback login/github-callback
   :page/bitbucket-callback login/bitbucket-callback
   :page/org org/page
   :page/org-api-keys api-keys/org-page
   :page/org-settings org/page-edit
   :page/org-new org/page-new
   :page/org-join home/page-join
   :page/org-params params/page
   :page/org-ssh-keys ssh-keys/page
   :page/add-repo repo/new
   :page/add-github-repo org/add-github-repo-page
   :page/add-bitbucket-repo org/add-bitbucket-repo-page
   :page/repo repo/page
   :page/repo-edit repo/edit
   :page/repo-settings repo/settings-page
   :page/job job/page
   :page/webhooks webhooks/page})

(defn render-page [route]
  (log/debug "Rendering page for route:" (str (r/route-name route)))
  (if-let [p (get pages (r/route-name route))]
    [p route]
    ;; TODO Replace with the general "not found" page
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
