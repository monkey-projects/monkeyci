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

(defn- render-job [job]
  (let [r (rf/subscribe [:route/current])
        cells [[:td [:a {:href (job-path job @r)}
                     (:id job)]]
               [:td (->> (get-in job [:dependencies])
                         (cs/join ", "))]
               [:td [co/build-result (:status job)]]
               [:td (elapsed job)]]]
    [:<>
     (into [:tr {:on-click #(rf/dispatch [:route/goto-path (job-path job @r)])}] cells)]))

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

(defn- current-overview []
  (let [build (rf/subscribe [:build/current])]
    [overview @build]))

(defn page [route]
  (rf/dispatch [:build/init])
  (fn [route]
    (let [params (r/path-params route)
          repo (rf/subscribe [:repo/info (:repo-id params)])
          reloading? (rf/subscribe [:build/reloading?])]
      [l/default
       [:<>
        [:div.d-flex.gap-2.align-items-start.mb-2
         [:h3.me-auto (:name @repo) " - " (:build-id params)]
         [co/reload-btn-sm [:build/reload] (when @reloading? {:disabled true})]]
        [:div.card
         [:div.card-body
          [co/alerts [:build/alerts]]
          [current-overview]
          [build-jobs]]]]])))
