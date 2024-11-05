(ns monkey.ci.gui.alerts
  "Centralization of alerts.  This to avoid having to put hardcoded strings in events."
  (:require [monkey.ci.gui.utils :as u]))

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
  (error-msg "Unable to fetch repositories from Github"))

(def bitbucket-ws-failed
  (error-msg "Unable to fetch Bitbucket workspaces"))

(def bitbucket-repos-failed
  (error-msg "Unable to fetch repositories for Bitbucket workspace"))

(def repo-watch-failed
  (error-msg "Failed to watch repo"))

(def repo-unwatch-failed
  (error-msg "Failed to unwatch repo"))

(def cust-create-success
  (alert-msg :success (fn [body] [:span "Customer " [:b (:name body)] " has been created."])))

(def cust-create-failed
  (error-msg "Failed to create customer"))

(def cust-recent-builds-failed
  (error-msg "Failed to load recent builds"))

(def cust-stats-failed
  (error-msg "Failed to load statistics"))

(def cust-credits-failed
  (error-msg "Failed to load credit information"))