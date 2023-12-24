(ns monkey.ci.gui.build.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.build.events]
            [monkey.ci.gui.build.subs]
            [re-frame.core :as rf]))

(def log-path-keys [])

(defn- build-path [route]
  (let [p (r/path-params route)
        get-p (juxt :customer-id :project-id :repo-id :build-id)]
    (->> (get-p p)
         (interleave ["customer" "project" "repo" "builds"])
         (into [m/url])
         (cs/join "/"))))

(defn- log-path [route l]
  (str (build-path route)
       "/logs/download?path=" (js/encodeURIComponent l)))

(defn- log-row [{:keys [name size]}]
  (let [route (rf/subscribe [:route/current])]
    [:tr
     [:td [:a {:href (log-path @route name)
               :target "_blank"}
           name]]
     ;; TODO Make size human readable
     [:td size]]))

(defn logs-table []
  (let [l (rf/subscribe [:build/logs])]
    [:table.table.table-striped
     [:thead
      [:tr
       [:th {:scope :col} "Log file"]
       [:th {:scope :col} "Size"]]]
     (->> @l
          (map log-row)
          (into [:tbody]))]))

(defn page [route]
  (rf/dispatch [:build/load-logs])
  (fn [route]
    (let [params (r/path-params route)]
      [l/default
       [:<>
        [:h3.float-start "Logs for " (:build-id params)]
        [:div.float-end
         [co/reload-btn [:build/load-logs]]]
        [:div.clearfix
         [co/alerts [:build/alerts]]]
        [logs-table]
        [:div
         [:a {:href (r/path-for :page/repo params)} "Back to repository"]]]])))
