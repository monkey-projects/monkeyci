(ns monkey.ci.gui.build.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.build.events]
            [monkey.ci.gui.build.subs]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.timer :as timer]
            [re-frame.core :as rf]))

(def log-modal-id :log-dialog)

(defn- modal-title []
  (let [p (rf/subscribe [:build/log-path])]
    [:h5 "Log for " @p]))

(defn- show-downloading []
  (let [d? (rf/subscribe [:build/downloading?])]
    (when @d?
      [co/render-alert {:type :info
                        :message "Downloading log file, one moment..."}])))

(defn- log-contents []
  (let [c (rf/subscribe [:build/current-log])]
    [co/log-contents @c]))

(defn log-modal []
  (let [a (rf/subscribe [:build/log-alerts])]
    [co/modal
     log-modal-id
     [modal-title]
     [:div {:style {:min-height "100px"}}
      [co/alerts [:build/log-alerts]]
      [show-downloading]
      [log-contents]]]))

(defn build-details
  "Displays the build details by looking it up in the list of repo builds."
  []
  (let [d (rf/subscribe [:build/current])
        v (-> @d
              (select-keys [:id :message :start-time :end-time])
              (assoc :ref (get-in @d [:git :ref]))
              (update :start-time t/reformat)
              (update :end-time t/reformat))]
    (->> v
         (map (fn [[k v]]
                [:li [:b k ": "] v]))
         (concat [[:li [:b "Result: "] [co/build-result (:status @d)]]])
         (into [:ul]))))

(defn build-result []
  (let [b (rf/subscribe [:build/current])]
    ;; TODO Signal warnings or skipped jobs.
    (case (:status @b)
      :success
      [:div.alert.alert-success
       [co/icon :check-circle-fill]
       [:span.ms-2 "Build successful!"]]
      :failure
      [:div.alert.alert-danger
       [co/icon :exclamation-triangle-fill]
       [:b.ms-2.me-2 "This build failed."]
       [:span "Check the job logs for details."]]
      ;; Build still running: show nothing
      nil)))

(defn- build-path [route]
  (let [p (r/path-params route)
        get-p (juxt :customer-id :repo-id :build-id)]
    (->> (get-p p)
         (interleave ["customer" "repo" "builds"])
         (into [m/url])
         (cs/join "/"))))

(defn- calc-elapsed [{s :start-time e :end-time}]
  (when (and s e)
    (t/format-seconds (int (/ (- e s) 1000)))))

#_(defn- render-log-link [{:keys [name size path]}]
  [:span.me-1
   [:a.me-1 {:href (u/->dom-id log-modal-id)
             :data-bs-toggle "modal"
             :on-click (u/link-evt-handler [:build/download-log path])}
    name]
   (str "(" size " bytes)")])

(defn- elapsed-final [s]
  [:span (calc-elapsed s)])

(defn- elapsed-running [s]
  (let [now (t/to-epoch (t/now))]
    [:span (calc-elapsed (assoc s :end-time now))]))

(defn- elapsed [x]
  (if (u/running? x)
    [elapsed-running x]
    [elapsed-final x]))

(defn- job-path [job curr]
  (r/path-for :page/job (-> curr
                            (r/path-params)
                            (assoc :job-id (:id job)))))

(defn- logs-btn [job]
  (let [r (rf/subscribe [:route/current])]
    [:a.btn.btn-primary.me-2
     {:href (job-path job @r)}
     [co/icon :file-earmark-plus] [:span.ms-1 "View Logs"]]))

(defn- hide-btn [job]
  [:button.btn.btn-close
   {:on-click #(rf/dispatch [:job/toggle job])
    :aria-label "Close"}])

(defn- job-details [{:keys [labels start-time end-time] deps :dependencies :as job}]
  [:div.row
   [:div.col-4
    [:ul
     (when start-time
       [:li "Started at:" [co/date-time start-time]])
     (when end-time
       [:li "Ended at:" [co/date-time end-time]])
     (when-not (empty? labels)
       [:li "Labels:" (str labels)])
     (when-not (empty? deps)
       [:li "Dependent on: " (cs/join ", " deps)])]]
   #_[:div.col-6.border-start
      [:h5 "Logs"]
      (->> (:logs job)
           (filter (comp pos? :size))
           (map render-log-link)
           (map (partial conj [:li]))
           (into [:ul]))]
   [:div.col-4
    [logs-btn job]]
   [:div.col-4
    [:div.float-end
     [hide-btn job]]]])

(defn- render-job [job]
  (let [exp (rf/subscribe [:build/expanded-jobs])
        expanded? (and exp (contains? @exp (:id job)))
        r (rf/subscribe [:route/current])
        cells [[:td [:a {:href (job-path job @r)}
                     (:id job)]]
               [:td (->> (get-in job [:dependencies])
                         (cs/join ", "))]
               [:td [co/build-result (:status job)]]
               [:td (elapsed job)]]]
    [:<>
     (into [:tr {:on-click #(rf/dispatch [:job/toggle job])}] cells)
     (when expanded?
       [:tr
        [:td {:col-span (count cells)}
         [job-details job]]])]))

(defn- jobs-table [jobs]
  [:table.table
   [:thead
    [:tr
     [:th "Job"]
     [:th "Dependencies"]
     [:th "Result"]
     [:th "Elapsed"]]]
   (->> jobs
        (map render-job)
        (into [:tbody]))])

(defn- build-jobs []
  (let [jobs (rf/subscribe [:build/jobs])]
    [:div.mb-2
     [:p "This build contains " (count @jobs) " jobs"]
     [jobs-table @jobs]]))

#_(defn- log-row [{:keys [name size] :as l}]
    (let [route (rf/subscribe [:route/current])]
      [:tr
       [:td [:a {:href (u/->dom-id log-modal-id)
                 :data-bs-toggle "modal"
                 :on-click (u/link-evt-handler [:build/download-log name])}
             name]]
       ;; TODO Make size human readable
       [:td size]]))

#_(defn logs-table []
  (let [l (rf/subscribe [:build/global-logs])]
    [:table.table.table-striped
     [:thead
      [:tr
       [:th {:scope :col} "Log file"]
       [:th {:scope :col} "Size"]]]
     (->> @l
          (map log-row)
          (into [:tbody]))]))

#_(defn- build-logs [params]
  (rf/dispatch [:build/load-logs])
  (fn [params]
    [:<>
     [:p "These are the global logs that do not belong to a specific job."]
     [logs-table]]))

#_(defn- tab-header [lbl curr? contents]
  [:li.nav-item
   [:a.nav-link (cond-> {:href ""}
                  curr? (assoc :class :active
                               :aria-current :page))]
   contents])

#_(defn details-tabs [route]
  [tabs/tabs
   ::build-details
   [{:header "Jobs"
     :current? true
     :contents [build-jobs]}
    {:header "Logs"
     :contents [build-logs (r/path-params route)]}]])

(defn page [route]
  (rf/dispatch [:build/init])
  (fn [route]
    (let [params (r/path-params route)
          repo (rf/subscribe [:repo/info (:repo-id params)])
          reloading? (rf/subscribe [:build/reloading?])]
      [l/default
       [:<>
        [:div.clearfix
         [:h2.float-start (:name @repo) " - " (:build-id params)]
         [:div.float-end
          [co/reload-btn [:build/reload] (when @reloading? {:disabled true})]]]
        [co/alerts [:build/alerts]]
        [build-details]
        [build-result]
        #_[details-tabs route]
        [build-jobs]
        [log-modal]
        [:div
         [:a {:href (r/path-for :page/repo params)} "Back to repository"]]]])))
