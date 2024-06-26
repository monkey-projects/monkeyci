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

(defn build-details
  "Displays the build details by looking it up in the list of repo builds."
  []
  (let [d (rf/subscribe [:build/current])
        {:keys [message]} @d]
    (letfn [(item [k v]
              [:<>
               [:div.col-md-2 [:b k]]
               [:div.col-md-2 v]])]
      [:div.mb-3
       [:div.row
        (item "Result" [co/build-result (:status @d)])
        (item "Start time" (t/reformat (:start-time @d)))]
       [:div.row
        (item "Git ref" (get-in @d [:git :ref]))
        (item "End time" (t/reformat (:end-time @d)))]
       [:div.row
        (item "Credits" (or (:credits @d) 0))
        (item [:span {:title "Total time that has passed between build start and end"} "Elapsed"]
              [co/build-elapsed @d])]
       (when message
         [:div.row
          [:div.col-md-2 [:b "Message"]]
          [:div.col-md-10 message]])])))

(defn build-result []
  (let [b (rf/subscribe [:build/current])]
    ;; TODO Signal warnings or skipped jobs.
    (case (:status @b)
      :success
      [:div.alert.alert-success
       [co/icon :check-circle-fill]
       [:span.ms-2 "Build successful!"]]
      :error
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

(defn- job-details [{:keys [labels start-time end-time] deps :dependencies arts :save-artifacts :as job}]
  (letfn [(format-labels [lbls]
            (->> lbls
                 (map (partial cs/join " = "))
                 (cs/join ", ")))]
    [:div.row
     [:div.col-4
      [:ul
       (when start-time
         [:li "Started at: " [co/date-time start-time]])
       (when end-time
         [:li "Ended at: " [co/date-time end-time]])
       (when-not (empty? labels)
         [:li "Labels: " (format-labels labels)])
       (when-not (empty? deps)
         [:li "Dependent on: " (cs/join ", " deps)])
       (when-not (empty? arts)
         ;; TODO Link to artifact
         [:li "Artifacts: " (cs/join ", " (map :id arts))])]]
     [:div.col-4
      [logs-btn job]]
     [:div.col-4
      [:div.float-end
       [hide-btn job]]]]))

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
        [build-jobs]
        [:div
         [:a {:href (r/path-for :page/repo params)} "Back to repository"]]]])))
