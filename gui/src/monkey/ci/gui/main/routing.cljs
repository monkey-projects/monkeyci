(ns monkey.ci.gui.main.routing
  (:require [monkey.ci.gui.routing :as r]))

(def main-router
  ;; Instead of pointing to the views directly, we refer to a keyword, which
  ;; is linked in another namespace (pages) to the actual view.  This allows
  ;; us to refer to the routing namespace from views, e.g. to resolve paths
  ;; by route names.
  (r/make-router
   [["/" :page/root]
    ["/login" {:name :page/login
               :public? true}]
    ["/o/join" {:conflicting true
                :name :page/org-join}]
    ["/o/new" {:conflicting true
               :name :page/org-new}]
    ["/o/:org-id" {:conflicting true
                   :name :page/org}]
    ["/o/:org-id/add-repo" :page/add-repo]
    ["/o/:org-id/add-repo/github" :page/add-github-repo]
    ["/o/:org-id/add-repo/bitbucket" :page/add-bitbucket-repo]
    ["/o/:org-id/settings" :page/org-settings]
    ["/o/:org-id/params" :page/org-params]
    ["/o/:org-id/ssh-keys" :page/org-ssh-keys]
    ["/o/:org-id/api-keys" :page/org-api-keys]
    ["/o/:org-id/billing" :page/billing]
    ["/o/:org-id/r/:repo-id" :page/repo]
    ["/o/:org-id/r/:repo-id/edit" :page/repo-edit]
    ["/o/:org-id/r/:repo-id/settings" :page/repo-settings]
    ["/o/:org-id/r/:repo-id/webhooks" :page/webhooks]
    ["/o/:org-id/r/:repo-id/b/:build-id" :page/build]
    ["/o/:org-id/r/:repo-id/b/:build-id/j/:job-id" :page/job]
    ["/d/:org-id" :page/dashboard]
    ["/u/:user-id" :page/user]
    ["/email/confirm" {:name :page/confirm-email
                       :public? true}]
    ["/email/unsubscribe" {:name :page/unsubscribe-email
                           :public? true}]
    ;; TODO Moved to oauth2 endpoint, remove these
    ["/github/callback" {:name :page/github-callback-old
                         :public? true}]
    ["/bitbucket/callback" {:name :page/bitbucket-callback-old
                            :public? true}]
    ["/oauth2/codeberg/callback" {:name :page/codeberg-callback
                                  :public? true}]
    ["/oauth2/github/callback" {:name :page/github-callback
                                :public? true}]
    ["/oauth2/bitbucket/callback" {:name :page/bitbucket-callback
                                   :public? true}]]))

(defn start! []
  (r/start-router main-router))
