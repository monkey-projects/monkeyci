(ns monkey.ci.gui.alerts
  "Centralization of alerts.  This to avoid having to put hardcoded strings in events."
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn render-alert [{:keys [type message]}]
  [:div.d-flex
   {:class (str "alert alert-dismissable alert-" (name type))
    :role :alert}
   message
   [:button.btn-close.ms-auto
    {:type :button
     :data-bs-dismiss :alert
     :aria-label "Close"}]])

(defn component
  "Renders an alerts component with given id."
  [id]
  (let [s (rf/subscribe id)]
    ;; TODO Allow user to close notifications
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))

(def gh-installation-url
  "URL that Github uses to configure app integrations"
  "https://github.com/settings/installations")

(defn alert-msg [type formatter]
  (fn [& args]
    {:type type
     :message (apply formatter args)}))

(defn error-msg [msg]
  (alert-msg :danger #(str msg ": " (u/error-msg %))))

(def github-load-user-failed
  (error-msg "Unable to retrieve user details from Github"))

(def github-login-failed
  (error-msg "Unable to fetch Github user token"))

(def github-load-config-failed
  (error-msg "Unable to load Github config"))

(defn org-details-failed [id]
  (error-msg (str "Could not load details for organization " id)))

(def org-fetch-github-repos
  (alert-msg :info (constantly "Fetching repositories from Github...")))

(def org-user-orgs-failed
  (error-msg "Unable to fetch user organizations from Github"))

(def org-github-repos-success
  (alert-msg :success #(str "Found " % " repositories in Github.")))

(def org-github-repos-failed
  (alert-msg :danger
             (fn [err]
               [:<>
                [:p
                 "Unable to fetch repositories from Github: " (u/error-msg err) "."]
                [:p
                 "You may need to "
                 [:a.text-white {:href gh-installation-url :target :_blank}
                  [:b "configure the MonkeyCI application in Github."]]]])))

(def bitbucket-load-config-failed
  (error-msg "Unable to load Bitbucket config"))

(def bitbucket-load-user-failed
  (error-msg "Unable to retrieve user details from Bitbucket"))

(def bitbucket-login-failed
  (error-msg "Unable to fetch Bitbucket user token"))

(def bitbucket-ws-failed
  (error-msg "Unable to fetch Bitbucket workspaces"))

(def bitbucket-repos-failed
  (error-msg "Unable to fetch repositories for Bitbucket workspace"))

(def bitbucket-webhooks-failed
  (error-msg "Unable to load Bitbucket webhooks"))

(def repo-watch-failed
  (error-msg "Failed to watch repo"))

(def repo-unwatch-failed
  (error-msg "Failed to unwatch repo"))

(def repo-lookup-github-id-failed
  (error-msg "Failed to retrieve repo details from Github"))

(def builds-load-failed
  (error-msg "Failed to load builds"))

(def build-trigger-success
  (alert-msg :info (constantly "A new build has been triggered.")))

(def build-trigger-failed
  (error-msg "Failed to trigger a new build"))

(def build-retry-success
  (alert-msg :info (constantly "The build has been re-triggered.")))

(def build-retry-failed
  (error-msg "Unable to restart this build"))

(def org-search-failed
  (error-msg "Failed to search for organizations"))

(def org-create-success
  (alert-msg :success (fn [body] [:span "Organization " [:b (:name body)] " has been created."])))

(def org-create-failed
  (error-msg "Failed to create organization"))

(def org-save-success
  (alert-msg :success (fn [body] [:span "Organization " [:b (:name body)] " has been updated."])))

(def org-save-failed
  (error-msg "Failed to update organization"))

(def org-recent-builds-failed
  (error-msg "Failed to load recent builds"))

(def org-latest-builds-failed
  (error-msg "Failed to load latest builds"))

(def org-stats-failed
  (error-msg "Failed to load statistics"))

(def org-credits-failed
  (error-msg "Failed to load credit information"))

(def org-ssh-keys-failed
  (error-msg "Failed to load SSH keys"))

(def org-save-ssh-keys-failed
  (error-msg "Failed to save SSH keys"))

(def repo-update-success
  {:type :success
   :message "Repository changes have been saved."})

(def repo-create-success
  {:type :success
   :message "Repository has been created."})

(def repo-update-failed
  (error-msg "Failed to save changes"))

(def repo-create-failed
  (error-msg "Failed to create repo"))

(def admin-login-failed
  (error-msg "Failed to authenticate"))

(def credit-issues-failed
  (error-msg "Failed to retrieve credit issuances"))

(def credit-issue-save-success
  {:type :success
   :message "New credits have been issued."})

(def credit-issue-save-failed
  (error-msg "Failed to issue credits"))

(def credit-subs-failed
  (error-msg "Failed to retrieve credit subscriptions"))

(def sub-save-success
  {:type :success
   :message "New credit subscription has been created."})

(def sub-save-failed
  (error-msg "Failed to create credit subscription"))

(def credit-issue-all-success
  (alert-msg :success
             #(str "Issued " (count %) " credit(s).")))

(def credit-issue-all-failed
  (error-msg "Failed to issue credits for all subscriptions"))

(def clean-proc-failed
  (error-msg "Failed to clean dangling processes"))

(def user-load-orgs-failed
  (error-msg "Could not retrieve linked organizations"))

(def webhooks-load-failed
  (error-msg "Could not retrieve repository webhooks"))

(def webhooks-new-failed
  (error-msg "Failed to create new webhook"))

(def webhook-delete-success
  (alert-msg :success #(str "Webhook " % " has been deleted.")))

(def webhooks-delete-failed
  (error-msg "Failed to delete webhook"))
