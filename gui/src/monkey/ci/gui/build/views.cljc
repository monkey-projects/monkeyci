(ns monkey.ci.gui.build.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.build.events]
            [monkey.ci.gui.build.subs]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.timer :as timer]
            [re-frame.core :as rf]
            #?@(:node [] ; Exclude ansi_up when building for node
                :cljs [["ansi_up" :refer [AnsiUp]]])))

(def log-modal-id :log-dialog)

(defn- modal-title []
  (let [p (rf/subscribe [:build/log-path])]
    [:h5 "Log for " @p]))

(defn- show-downloading []
  (let [d? (rf/subscribe [:build/downloading?])]
    (when @d?
      [co/render-alert {:type :info
                        :message "Downloading log file, one moment..."}])))

;; Node does not support ESM modules, so we need to apply this workaround
#?(:node
   (defn ansi->html [l]
     l)
   :cljs
   (do
     (def ansi-up (AnsiUp.))
     (defn- ansi->html [l]
       (.ansi_to_html ansi-up l))))

(defn- ->html [l]
  (if (string? l)
    [:span
     {:dangerouslySetInnerHTML {:__html (ansi->html l)}}]
    l))

(defn- log-contents []
  (let [c (rf/subscribe [:build/current-log])]
    (->> @c
         (map ->html)
         (into [:p.text-bg-dark.font-monospace.overflow-auto.text-nowrap.h-100]))))

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
  (let [d (rf/subscribe [:build/details])
        v (-> @d
              (select-keys [:id :message :ref :timestamp])
              (update :timestamp t/reformat))]
    (->> v
         (map (fn [[k v]]
                [:li [:b k ": "] v]))
         (concat [[:li [:b "Result: "] [co/build-result (:result @d)]]])
         (into [:ul]))))

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

(defn- render-log-link [{:keys [name size path]}]
  [:span.me-1
   [:a.me-1 {:href (u/->dom-id log-modal-id)
             :data-bs-toggle "modal"
             :on-click (u/link-evt-handler [:build/download-log path])}
    name]
   (str "(" size " bytes)")])

(defn- elapsed-final [s]
  [:span (calc-elapsed s)])

(defn- elapsed-running [s]
  (let [t (rf/subscribe [:build/last-reload-time])]
    [:span (calc-elapsed (assoc s :end-time @t))]))

(defn- elapsed [x]
  (if (u/running? x)
    [elapsed-running x]
    [elapsed-final x]))

(defn- render-step [s]
  [:tr
   [:td (or (:name s) (:index s))]
   [:td [co/build-result (:status s)]]
   [:td (elapsed s)]
   [:td (->> (:logs s)
             (map render-log-link)
             (into [:span]))]])

(defn- pipeline-details [p]
  [:<>
   [:ul
    [:li
     [:b "Elapsed: "]
     [:span
      {:title "Each pipeline incurs a small startup time, that's why the elapsed time is higher than the sum of the steps' times."}
      (elapsed p)]]
    [:li [:b "Steps: "] (count (:steps p))]]
   [:table.table.table-striped
    [:thead
     [:tr
      [:th "Step"]
      [:th "Result"]
      [:th "Elapsed"]
      [:th "Logs"]]]
    (->> (:steps p)
         (map render-step)
         (into [:tbody]))]])

(defn- as-accordion-item [p]
  {:title [:span
           [:b.me-1 (or (:name p) (str "Pipeline " (:index p)))]
           [:span.badge.text-bg-secondary.me-1 (str (count (:steps p)))]
           [co/build-result (:status p)]]
   :collapsed true
   :contents [pipeline-details p]})

(defn- build-pipelines []
  (let [d (rf/subscribe [:build/details])]
    [:div.mb-2
     [:h3 "Pipelines"]
     [co/accordion ::pipelines
      (->> (:pipelines @d)
           (map as-accordion-item))]]))

(defn- log-row [{:keys [name size] :as l}]
  (let [route (rf/subscribe [:route/current])]
    [:tr
     [:td [:a {:href (u/->dom-id log-modal-id)
               :data-bs-toggle "modal"
               :on-click (u/link-evt-handler [:build/download-log name])}
           name]]
     ;; TODO Make size human readable
     [:td size]]))

(defn logs-table []
  ;; TODO Remove logs that are linked to a job
  (let [l (rf/subscribe [:build/logs])]
    [:table.table.table-striped
     [:thead
      [:tr
       [:th {:scope :col} "Log file"]
       [:th {:scope :col} "Size"]]]
     (->> @l
          (map log-row)
          (into [:tbody]))]))

(defn- build-logs [params]
  (rf/dispatch [:build/load-logs])
  (fn [params]
    [:<>
     [:h3.float-start "Captured Logs"]
     [logs-table]]))

(defn- auto-reload-check []
  (let [r (rf/subscribe [:build/auto-reload?])]
    [:div.form-check
     [:input#auto-reload.form-check-input
      {:type :checkbox
       :on-change (u/form-evt-handler [:build/auto-reload-changed] u/evt->checked)}]
     [:label.form-check-label {:for :auto-reload} "Auto reload"]
     (when @r
       [timer/timer ::auto-reload 5000 [:build/reload]])]))

(defn page [route]
  (rf/dispatch [:build/load])
  (fn [route]
    (let [params (r/path-params route)
          repo (rf/subscribe [:repo/info (:repo-id params)])
          reloading? (rf/subscribe [:build/reloading?])]
      [l/default
       [:<>
        [:div.clearfix
         [:h2.float-start (:name @repo) " - " (:build-id params)]
         [:div.float-end
          [co/reload-btn [:build/reload] (when @reloading? {:disabled true})]
          [auto-reload-check]]]
        [co/alerts [:build/alerts]]
        [build-details]
        [build-pipelines]
        [build-logs params]
        [log-modal]
        [:div
         [:a {:href (r/path-for :page/repo params)} "Back to repository"]]]])))
