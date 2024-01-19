(ns monkey.ci.gui.build.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.build.events]
            [monkey.ci.gui.build.subs]
            [re-frame.core :as rf]))

(defn build-details
  "Displays the build details by looking it up in the list of repo builds."
  []
  (let [d (rf/subscribe [:build/details])]
    (->> [:id :message :ref :timestamp]
         (select-keys @d)
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

(defn- elapsed [{s :startTime e :endTime}]
  (when (and s e)
    (str (- e s) " ms")))

(defn- render-step [s]
  [:tr
   [:td (or (:name s) (:index s))]
   [:td [co/build-result (:status s)]]
   [:td (elapsed s)]])

(defn- render-pipeline [p]
  [:div.accordion-item
   [:h4
    [:button.accordion-button {:type :button} (or (:name p) (str "Pipeline " (:index p)))]]
   [:div.accordion-collapse.collapse.show
    [:div.accordion-body
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
        [:th "Elapsed"]]]
      (->> (:steps p)
           (map render-step)
           (into [:tbody]))]]]])

(defn- build-pipelines []
  (let [d (rf/subscribe [:build/details])]
    [:div
     [:h3 "Pipelines: " (count (:pipelines @d))]
     (->> (:pipelines @d)
          (map render-pipeline)
          (into [:div.accordion.mb-2]))]))

(defn- log-path [route l]
  (str (build-path route)
       "/logs/download?path=" (js/encodeURIComponent l)))

(defn- log-row [{:keys [name size]}]
  (let [route (rf/subscribe [:route/current])]
    [:tr
     [:td [:a {:href (log-path @route name)
               :target "_blank"}
           name]]
     ;; TODO Make size human readable
     [:td size]]))

(defn logs-table []
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

(defn page [route]
  (let [params (r/path-params route)
        repo (rf/subscribe [:repo/info (:repo-id params)])]
    [l/default
     [:<>
      [:div.clearfix
       [:h2.float-start (:name @repo) " - " (:build-id params)]
       [:div.float-end
        [co/reload-btn [:build/reload]]]]
      [co/alerts [:build/alerts]]
      [build-details]
      [build-pipelines]
      [build-logs params]
      [:div
       [:a {:href (r/path-for :page/repo params)} "Back to repository"]]]]))
