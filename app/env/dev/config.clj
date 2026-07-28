(ns config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [common :as c]
            [meta-merge.core :as mm]
            [monkey.ci
             [config :as config]
             [storage]])
  (:import java.io.PushbackReader))

;; Global config state
(defonce global-config (atom {}))

(defn load-config [f]
  (config/load-config-file (io/file "dev-resources" "config" f)))

(defn load-config!
  "Loads config from `f` and adds it to the state"
  [f]
  (swap! global-config mm/meta-merge (load-config f)))

(defn reset-config! []
  (reset! global-config {}))

(defn update-config! [f & args]
  (apply swap! global-config f args))

(defn account->sid []
  (let [v (juxt :org-id :repo-id)]
    (->> @global-config
         :account
         (v)
         (vec))))
