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

(defn build-status-icon [status]
  (let [[cl icon] (get {:success      [:text-success :check-circle]
                        :error        [:text-danger :x-circle]
                        :running      [:text-info :play-circle]
                        :pending      [:text-warning :pause-circle]
                        :initializing [:text-warning :play-circle]}
                       status
                       [:text-default :question-circle])]
    [:div (cond-> {:style {:font-size "8em"}
                   :class cl}
            status (assoc :title (name status)))
     [co/icon icon]]))

(defn build-status [{:keys [status]}]
  (let [status-desc {:pending      "The build is waiting to be picked up by a runner."
                     :initializing "Compute capacity is being provisioned."
                     :running      "The build script is running."
                     :success      "The build has completed succesfully."
                     :error        "The build has failed."}]
    [:div.d-flex.gap-4.align-items-center
     [build-status-icon status]
     [:div
      [:h3 (cs/capitalize (name status))]
      [:p (get status-desc status "Unknown")]]]))

(defn build-details
  "Displays the build details by looking it up in the list of repo builds."
  [build]
  (letfn [(item [k v]
            [:<>
             [:div.col-lg-3.col-sm-6 [:b k]]
             [:div.col-lg-3.col-sm-6 v]])]
    [:<>
     [:div.row
      (item "Start time" (t/reformat (:start-time build)))]
     [:div.row
      (item "End time" (t/reformat (:end-time build)))
      (item "Git ref" (get-in build [:git :ref]))]
     [:div.row
      (item [:span {:title "Total time that has passed between build start and end"} "Elapsed"]
            [co/build-elapsed build])
      (item "Credits" (or (:credits build) 0))]
     (when-let [msg (:message build)]
       [:div.row
        [:div.col-md-3 [:b "Message"]]
        [:div.col-md-9 msg]])]))

(defn overview [build]
  [:div.d-flex.align-items-center.gap-4
   [build-status build]
   [:div.flex-grow-1
    [build-details build]]]
  #_[:div.container
     [:div.row
      [:div.col-5
       [build-status build]]
      [:div.container.col-7.align-bottom
       [build-details build]]]])

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
          reloading? (rf/subscribe [:build/reloading?])
          curr (rf/subscribe [:build/current])]
      [l/default
       [:<>
        [:div.d-flex.gap-2.align-items-start.mb-2
         [:h3.me-auto (:name @repo) " - " (:build-id params)]
         [co/reload-btn-sm [:build/reload] (when @reloading? {:disabled true})]]
        [:div.card
         [:div.card-body
          [co/alerts [:build/alerts]]
          [overview @curr]
          [build-jobs]]]]])))
