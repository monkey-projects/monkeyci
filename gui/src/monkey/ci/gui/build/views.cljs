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
  (co/status-icon status "8em"))

(defn build-status [{:keys [status]}]
  (let [status-desc {:pending      "The build is waiting to be picked up by a runner."
                     :initializing "Compute capacity is being provisioned."
                     :running      "The build script is running."
                     :success      "The build has completed successfully."
                     :error        "The build has failed."
                     :canceled     "The build has been canceled."}]
    [:div.d-flex.gap-4.align-items-center
     [build-status-icon status]
     [:div
      [:h3 (cs/capitalize (name status))]
      [:p (get status-desc status "Unknown")]]]))

(defn build-details
  "Displays the build details by looking it up in the list of repo builds."
  [{:keys [credits] :as build}]
  (letfn [(item [k v]
            [:div.row
             [:div.col-4.offset-1 [:b k]]
             [:div.col-7 v]])]
    [:<>
     (item "Start time" (t/reformat (:start-time build)))
     (item [:span {:title "Total time that has passed between build start and end"} "Elapsed"]
           [:span {:title (t/reformat (:end-time build))} [co/build-elapsed build]])
     (item "Git ref" (get-in build [:git :ref]))
     (when (number? credits)
       (item [:span {:title "Consumed amount of credits"} "Credits"] credits))
     (when-let [msg (or (:message build) (get-in build [:script :message]))]
       [:div.row
        [:div.col-md-4.offset-1 [:b "Message"]]
        [:div.col-md-7 msg]])
     (when-let [msg (get-in build [:git :message])]
       [:div.row
        [:div.col-md-4.offset-1 [:b "Commit message"]]
        [:div.col-md-7 msg]])]))

(defn overview [build]
  (when build
    [:div.d-flex.align-items-center.gap-4
     [build-status build]
     [:div.flex-grow-1
      [build-details build]]]))

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

(defn- render-job [idx job]
  (let [r (rf/subscribe [:route/current])
        cells [[:td [:b (inc idx)]]
               [:td [:a {:href (job-path job @r)}
                     (:id job)]]
               [:td (->> (get-in job [:dependencies])
                         (cs/join ", "))]
               [:td [co/build-result (:status job)]]
               [:td (elapsed job)]]]
    [:<>
     (into [:tr {:on-click #(rf/dispatch [:route/goto-path (job-path job @r)])}] cells)]))

(defn- jobs-table [jobs]
  [:table.table.table-hover
   [:thead
    [:tr
     [:th "#"]
     [:th "Job"]
     [:th "Dependencies"]
     [:th "Result"]
     [:th "Elapsed"]]]
   (->> jobs
        (map-indexed render-job)
        (into [:tbody]))])

(defn- build-jobs []
  (let [jobs (rf/subscribe [:build/jobs])]
    (println "Jobs:" (str @jobs))
    (if (empty? @jobs)
      [:p "This build does not contain any jobs."]
      [jobs-table @jobs])))

(defn- current-overview []
  (let [build (rf/subscribe [:build/current])]
    [overview @build]))

(defn- cancel-btn []
  (let [build (rf/subscribe [:build/current])
        c? (rf/subscribe [:build/canceling?])]
    (when (#{:running :initializing} (:status @build))
      [:button.btn.btn-icon.btn-danger.btn-sm
       (cond-> {:on-click (u/link-evt-handler [:build/cancel])
                :title "Cancel this build"}
         @c? (assoc :disabled true))
       [co/icon :x-circle]])))

(def status-ended? #{:success :error :failure :canceled})

(defn- retry-btn []
  (let [build (rf/subscribe [:build/current])
        r? (rf/subscribe [:build/retrying?])]
    (when (status-ended? (:status @build))
      [:button.btn.btn-icon.btn-primary.btn-sm
       (cond-> {:on-click (u/link-evt-handler [:build/retry])
                :title "Restart this build"}
         @r? (assoc :disabled true))
       [co/icon :arrow-repeat]])))

(defn build-title []
  (let [route (rf/subscribe [:route/current])
        params (-> @route r/path-params)
        repo (rf/subscribe [:repo/info (:repo-id params)])]
    [:h3.me-auto [:span.me-2 co/build-icon] (:name @repo) " - " (:build-id params)]))

(defn page [route]
  (rf/dispatch [:build/init])
  (let [loading? (rf/subscribe [:build/loading?])]
    [l/default
     [:<>
      [:div.d-flex.gap-2.align-items-start.mb-2
       [build-title]
       (when @loading?
         [:button.btn.btn-primary.btn-sm
          {:disabled true}
          [:div.spinner-border]])
       [retry-btn]
       [cancel-btn]
       [co/reload-btn-sm [:build/reload]]]
      [:div.card
       [:div.card-body
        [co/alerts [:build/alerts]]
        ;; TODO When loading, show placeholders
        [current-overview]
        [build-jobs]]]]]))
