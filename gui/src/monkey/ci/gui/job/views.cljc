(ns monkey.ci.gui.job.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.job.events :as e]
            [monkey.ci.gui.job.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(defn- info
  "Displays the given rows (consisting of 2 items) as an info grid."
  [& rows]
  (letfn [(info-row [[k v]]
            [:div.row
             [:div.col-2 k]
             [:div.col-2 v]])]
    (->> rows
         (remove nil?)
         (map info-row)
         (into [:<>]))))

(defn- job-details []
  (let [{:keys [start-time end-time labels] deps :dependencies :as job} @(rf/subscribe [:job/current])]
    [info
     ["Status:" [co/build-result (:status job)]]
     (when start-time
       ["Started at:" [co/date-time start-time]])
     (when end-time
       ["Ended at:" [co/date-time end-time]])
     (when (and start-time end-time)
       ["Duration:" [t/format-seconds (int (/ (- end-time start-time) 1000))]])
     (when-not (empty? labels)
       ["Labels:" (str labels)])
     (when-not (empty? deps)
       ["Dependent on:" (cs/join ", " deps)])]))

(defn page [_]
  (rf/dispatch [:job/init])
  (let [job-id (rf/subscribe [:job/id])]
    [l/default
     [:<>
      [:h2 "Job: " @job-id]
      [job-details]
      [co/alerts [:job/alerts]]]]))
