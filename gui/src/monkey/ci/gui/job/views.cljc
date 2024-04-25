(ns monkey.ci.gui.job.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.job.events :as e]
            [monkey.ci.gui.job.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.tabs :as tabs]
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

(defn- job-labels [labels]
  (->> labels
       (map (fn [[k v]]
              [:li k ": " v]))
       (into [:ul])))

(defn- job-details []
  (when-let [{:keys [start-time end-time labels] deps :dependencies :as job} @(rf/subscribe [:job/current])]
    [info
     ["Status:" [co/build-result (:status job)]]
     (when start-time
       ["Started at:" [co/date-time start-time]])
     (when end-time
       ["Ended at:" [co/date-time end-time]])
     (when (and start-time end-time)
       ["Duration:" [t/format-seconds (int (/ (- end-time start-time) 1000))]])
     (when-not (empty? labels)
       ["Labels:" [job-labels labels]])
     (when-not (empty? deps)
       ["Dependent on:" (cs/join ", " deps)])]))

(defn- path->file [p]
  (some-> p
          (cs/split #"/")
          last))

(defn- log-contents [job path]
  (let [log (rf/subscribe [:job/logs path])]
    ;; Reload log file
    (rf/dispatch [:job/load-logs job path])
    [:<>
     [co/alerts [:job/path-alerts path]]
     (when (and @log (not-empty @log))
       [co/log-contents @log])]))

(defn- log-tabs [job]
  (when-let [files (rf/subscribe [:job/log-files])]
    (if (empty? @files)
      [:p "No log information available.  You may want to try again later."]
      (->> @files
           (map-indexed (fn [idx p]
                          {:header (path->file p)
                           :contents [log-contents job p]
                           :current? (zero? idx)}))
           (conj [tabs/tabs e/log-tabs-id])))))

(defn- load-log-tabs []
  (when-let [job @(rf/subscribe [:job/current])]
    (rf/dispatch [:job/load-log-files job])
    [log-tabs job]))

(defn page [_]
  (rf/dispatch [:job/init])
  (let [job-id (rf/subscribe [:job/id])]
    [l/default
     [:<>
      [:h2 "Job: " @job-id]
      [:div.mb-2
       [job-details]]
      [co/alerts [:job/alerts]]
      [load-log-tabs]]]))
