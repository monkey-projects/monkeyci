(ns monkey.ci.gui.job.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.artifact.events]
            [monkey.ci.gui.artifact.subs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.job.events :as e]
            [monkey.ci.gui.job.subs :as s]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.test-results :as tr]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
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
       (map (fn [kv]
              (vec (interleave [:li ":"] kv))))
       (into [:ul])))

(defn job-details
  ([job]
   (when-let [{:keys [start-time end-time labels] deps :dependencies} job]
     [:div.d-flex.gap-4.align-items-center.mb-3
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
       (let [msg (or (:message job) (get-in job [:result :message]))]
         (when-not (empty? msg)
           [:div.row
            [:div.col-2 "Message:"]
            ;; TODO Truncate, but allow user to expand
            [:div.col-10 msg]]))]]))
  ([]
   (job-details @(rf/subscribe [:job/current]))))

(defn- path->file [p]
  (some-> p
          (cs/split #"/")
          last))

(defn- show-log [lbl contents]
  (->>
   (concat
    [(co/->html (co/colored (str lbl ":") 95))
     [:br]]
    (when (and contents (not-empty contents))
      (mapv co/->html contents))
    [[:br]])
   (into [:pre])))

(defn- log-contents [lbl path]
  (let [log (rf/subscribe [:job/logs path])]
    (show-log lbl @log)))

(def log-types
  {:out "stdout"
   :err "stderr"})

(defn- script-line [idx {:keys [expanded?] :as l}]
  [:<>
   [:a {:on-click (u/link-evt-handler [:job/toggle-logs idx l])}
    [:span.me-1
     [co/icon (if expanded? :chevron-down :chevron-right)]]
    (co/->html (co/colored (:cmd l) 92))]
   [:br]
   (when expanded?
     (->> log-types
          (map (fn [[t lbl]]
                 (when-let [path (get l t)]
                   [log-contents lbl path])))
          (into [:<>])))])

(defn- job-logs [job]
  (rf/dispatch [:job/load-log-files job])
  (let [script-logs (rf/subscribe [:job/script-with-logs])]
    ;; Display combined logs for all script lines in the job
    [co/log-viewer (map-indexed script-line @script-logs)]))

(def has-logs? (comp  #{:running :success :failure :error :canceled} :status))

(defn- log-tab [job]
  (when (has-logs? job)
    {:header "Logs"
     :contents [job-logs job]}))

(defn- job-output [{:keys [output error]}]
  [co/log-viewer
   (->> [["stdout" output] ["stderr" error]]
        (filter (comp not-empty second))
        ;; FIXME Multiple spaces are not displayed correctly
        (map (fn [[lbl v]]
               [lbl (->> (cs/split v #"\n")
                         (interpose [:br]))]))
        (map (partial apply show-log)))])

(defn- output-tab [job]
  (let [result (select-keys (:result job) [:output :error])]
    (when (not-empty result)
      {:header "Output"
       :contents [job-output result]})))

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
        {:keys [org-id repo-id build-id]} (r/path-params @route)]
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

(defn unblock-btn
  ([job unlocking?]
   [:button.btn.btn-primary
    (cond-> {:title "Allow this job to continue execution."
             :on-click (u/link-evt-handler [:job/unblock job])}
      unlocking? (assoc :disabled true))
    (if unlocking?
      [co/spinner-text "Continue"]
      [co/icon-text :play-fill "Continue"])])
  ([job]
   (let [u? (rf/subscribe [::s/unblocking?])]
     (unblock-btn job @u?))))

(defn details-tabs
  "Renders tabs to display the job details.  These tabs include logs and test results."
  [{:keys [status] :as job}]
  (let [[f :as tabs] (->> [(output-tab job)
                           (error-trace job)
                           (test-results job)
                           (log-tab job)
                           (artifacts-tab job)]
                          (remove nil?))]
    (cond
      (= :pending status)
      [:p "The job is waiting until all conditions are met for execution."]

      (= :blocked status)
      [:<>
       [:p "This job needs manual approval in order to continue."]
       [unblock-btn job]]

      (empty? tabs)
      [:p "No job details available.  You may want to try again later."]
      
      :else
      ;; Make first tab the active one
      ;; FIXME Doesn't work after refresh
      (conj [tabs/tabs e/details-tabs-id]
            (replace {f (assoc f :current? true)} tabs)))))

(defn- load-details-tabs
  "Loads any additional job details and renders the tabs to display them."
  []
  (when-let [job @(rf/subscribe [:job/current])]
    [details-tabs job]))

(defn- return-link []
  (let [route (rf/subscribe [:route/current])]
    [:div.mt-2
     [:a {:href (r/path-for :page/build (r/path-params @route))}
      [:span.me-1 [co/icon :chevron-left]] "Back to build"]]))

(def route->id (comp (juxt :org-id :repo-id :build-id :job-id)
                     r/path-params))

(defn page [route]
  (let [uid (e/route->id route)]
    (rf/dispatch [:job/init uid])
    (let [job-id (rf/subscribe [:job/id])]
      [:<>
       [l/default
        [:<>
         [co/page-title [co/icon-text :cpu "Job: " @job-id]]
         [:div.card
          [:div.card-body
           [job-details]
           [co/alerts [:job/alerts]]
           [load-details-tabs]]]
         [return-link]]]
       ;; Put modals as high as possible on the dom tree to avoid interference
       [tr/test-details-modal]])))
