(ns monkey.ci.gui.dashboard.login.db
  (:require [monkey.ci.gui.login.db :as ldb]))

(defn- set-prop [db k v]
  (assoc-in db [::login k] v))

(defn- get-prop [db k]
  (get-in db [::login k]))

(defn set-oauth-provider [db provider]
  (set-prop db :oauth-provider provider))

(defn get-oauth-provider [db]
  (get-prop db :oauth-provider))

(def provider-config-key
  {:github ldb/github-config
   :bitbucket ldb/bitbucket-config
   :codeberg ldb/codeberg-config})

(defn get-client-id [db provider]
  (get-in db [(get provider-config-key provider) :client-id]))
