(ns monkey.ci.gui.alerts
  "Centralization of alerts.  This to avoid having to put hardcoded strings in events."
  (:require [monkey.ci.gui.utils :as u]))

(def gh-installation-url
  "URL that Github uses to configure app integrations"
  "https://github.com/settings/installations")

(defn alert-msg [type formatter]
  (fn [& args]
    {:type type
     :message (apply formatter args)}))

(defn error-msg [msg]
  (alert-msg :danger #(str msg ": " (u/error-msg %))))

(defn cust-details-failed [id]
  (error-msg (str "Could not load details for customer " id)))

(def cust-fetch-github-repos
  (alert-msg :info (constantly "Fetching repositories from Github...")))

(def cust-user-orgs-failed
  (error-msg "Unable to fetch user organizations from Github"))

(def cust-github-repos-success
  (alert-msg :success #(str "Found " % " repositories in Github.")))

(def cust-github-repos-failed
  (alert-msg :danger
             (fn [err]
               [:<>
                [:p
                 "Unable to fetch repositories from Github: " (u/error-msg err) "."]
                [:p
                 "You may need to "
                 [:a.text-white {:href gh-installation-url :target :_blank}
                  [:b "configure the MonkeyCI application in Github."]]]])))

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

(def builds-load-failed
  (error-msg "Failed to load builds"))

(def cust-search-failed
  (error-msg "Failed to search for customers"))

(def cust-create-success
  (alert-msg :success (fn [body] [:span "Customer " [:b (:name body)] " has been created."])))

(def cust-create-failed
  (error-msg "Failed to create customer"))

(def cust-recent-builds-failed
  (error-msg "Failed to load recent builds"))

(def cust-latest-builds-failed
  (error-msg "Failed to load latest builds"))

(def cust-stats-failed
  (error-msg "Failed to load statistics"))

(def cust-credits-failed
  (error-msg "Failed to load credit information"))

(def cust-ssh-keys-failed
  (error-msg "Failed to load SSH keys"))

(def cust-save-ssh-keys-failed
  (error-msg "Failed to save SSH keys"))

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
