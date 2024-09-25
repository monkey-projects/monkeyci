(ns monkey.ci.gui.job.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.artifact.events]
            [monkey.ci.gui.artifact.subs]
            [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.job.events :as e]
            [monkey.ci.gui.job.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.test-results :as tr]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(defn status-icon [status]
  (co/status-icon status "6em"))

(defn- info
  "Displays the given rows (consisting of 2 items) as an info grid."
  [& rows]
  (letfn [(info-row [[k v]]
            [:div.row
             [:div.col-2 k]
             [:div.col-10 v]])]
    (->> rows
         (remove nil?)
         (map info-row)
         (into [:<>]))))

(defn- job-labels [labels]
  (->> labels
       (map (fn [[k v]]
              [:li k ": " v]))
       (into [:ul])))

(defn job-details
  ([job]
   (when-let [{:keys [start-time end-time labels message] deps :dependencies} job]
     [:div.d-flex.gap-4.align-items-center
      [status-icon (:status job)]
      [:div.w-100
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
          ["Dependent on:" (cs/join ", " deps)])]
       (when-not (empty? message)
         [:div.row
          [:div.col-2 "Message: "]
          [:div.col-10.text-truncate message]])]]))
  ([]
   (job-details @(rf/subscribe [:job/current]))))

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

(defn- job-output [output]
  ;; TODO Handle ansi coloring
  [co/log-contents (->> (cs/split output #"\n")
                        (map cs/trim)
                        (interpose [:br])
                        (into [:pre])
                        vector)])

(defn output-tab [job]
  (when-let [output (get-in job [:result :output])]
    {:header "Output"
     :contents [job-output output]}))

(defn- test-results-details [tr]
  [:div
   [tr/timing-chart :test-timings tr]
   [tr/test-results :test-results [:job/test-cases]]])

(defn- test-results [job]
  (when-let [tr (get-in job [:result :monkey.ci/tests])]
    {:header "Test Results"
     :contents [test-results-details tr]}))

(defn- error-trace [job]
  (when-let [st (:stack-trace job)]
    {:header "Error Trace"
     :contents [co/log-contents (-> st
                                    (cs/split #"\n")
                                    (as-> x (interpose [:br] x)))]}))

(defn- download-artifact-btn [{:keys [id]}]
  (let [loading? (rf/subscribe [:artifact/downloading? id])]
    (if @loading?
      [:button.btn.btn-sm.btn-primary [:div.spinner-border.spinner-border-sm]]
      [co/icon-btn-sm :download [:artifact/download id] {:title "Download artifact"}])))

(defn- artifacts [job]
  (let [route (rf/subscribe [:route/current])
        token (rf/subscribe [:login/token])
        {:keys [customer-id repo-id build-id]} (r/path-params @route)]
    (letfn [(artifact-row [art]
              [:tr
               [:td [download-artifact-btn art]]
               [:td (:id art)]
               [:td (:path art)]])]
      [:<>
       [co/alerts [:artifact/alerts]]
       [:table.table
        [:thead
         [:tr
          [:th ""]
          [:th.w-25 "Id"]
          [:th.w-75 "Path"]]]
        (into [:tbody] (map artifact-row (:save-artifacts job)))
        [:caption "Configured and actual job artifacts."]]])))

(defn- artifacts-tab [job]
  (when (:save-artifacts job)
    {:header "Artifacts"
     :contents [artifacts job]}))

(defn- details-tabs
  "Renders tabs to display the job details.  These tabs include logs and test results."
  [job]
  (let [files (rf/subscribe [:job/log-files])
        log-tabs (when @files
                   (->> @files
                        (map (fn [p]
                               {:header (path->file p)
                                :contents [log-contents job p]}))))
        [f :as tabs] (->> log-tabs ; TODO Merge into one tab
                          (concat [(output-tab job)
                                   (error-trace job)
                                   (test-results job)
                                   (artifacts-tab job)])
                          (remove nil?))]
    (if (empty? tabs)
      [:p "No job details available.  You may want to try again later."]
      ;; Make first tab the active one
      (conj [tabs/tabs e/details-tabs-id]
            (replace {f (assoc f :current? true)} tabs)))))

(defn- load-details-tabs
  "Loads any additional job details and renders the tabs to display them."
  []
  (when-let [job @(rf/subscribe [:job/current])]
    (rf/dispatch [:job/load-log-files job])
    [details-tabs job]))

(defn- return-link []
  (let [route (rf/subscribe [:route/current])]
    [:div.mt-2
     [:a {:href (r/path-for :page/build (r/path-params @route))}
      "Back to build"]]))

(defn page [_]
  (rf/dispatch [:job/init])
  (let [job-id (rf/subscribe [:job/id])]
    [l/default
     [:<>
      [:h3 "Job: " @job-id]
      [:div.card
       [:div.card-body
        [job-details]
        [co/alerts [:job/alerts]]
        [load-details-tabs]]]
      [return-link]]]))
