(ns monkey.ci.gui.test-results
  "Displays test results"
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def success? (comp (partial every? empty?) (juxt :errors :failures)))

(defn- test-row [{:keys [test-case class-name time] :as tc}]
  [:tr
   [:td test-case]
   [:td (co/build-result (if (success? tc) "success" "failure")) ]
   [:td time "s"]])

(defn- suite-rows [suite]
  (map test-row (:test-cases suite)))

(defn test-results [suites]
  [:table.table.table-striped
   [:thead
    [:tr
     [:th "Test case"]
     [:th "Result"]
     [:th "Elapsed"]]]
   (->> suites
        (mapcat suite-rows)
        (into [:tbody]))])

(defn- timings->chart [results n]
  (let [cases (->> results
                   (mapcat :test-cases)
                   (remove (comp nil? :time))
                   (take n)
                   (sort-by :time)
                   (reverse))]
    {:type "bar"
     :data {:labels (map :test-case cases)
            :datasets [{:label "Seconds"
                        :data (map :time cases)}]}}))

(def default-chart-form {:count 5})

(defn- timing-chart-form [db id]
  (get-in db [::timing-chart id] default-chart-form))

(defn- timing-chart-results [db id]
  (get-in db [::timing-chart-results id]))

(rf/reg-sub
 ::timing-chart-form
 (fn [db [_ id]]
   (timing-chart-form db id)))

(rf/reg-sub
 ::timing-chart-val
 (fn [db [_ id prop]]
   (prop (timing-chart-form db id))))

(rf/reg-event-fx
 ::timing-chart-init
 (fn [{:keys [db]} [_ id results]]
   {:db (assoc-in db [::timing-chart-results id] results)
    :dispatch [:chart/update id (timings->chart results (:count (timing-chart-form db id)))]}))

(rf/reg-event-fx
 ::timing-chart-changed
 (fn [{:keys [db]} [_ id prop v]]
   (let [results (timing-chart-results db id)
         db (assoc-in db [::timing-chart id prop] v)]
     {:db db
      :dispatch [:chart/update id (timings->chart results (:count (timing-chart-form db id)))]})))

(defn suite-dropdown [suites id name]
  (let [v (rf/subscribe [::timing-chart-val id :suite])]
    (->> (concat [nil] suites)
         (map (fn [{:keys [name]}]
                [:option {:value name} (or name "All")]))
         (into [:select.form-select
                {:name name
                 :aria-label "suite"
                 :selected @v
                 :on-change (u/form-evt-handler [::timing-chart-changed id :suite])}]))))

(defn test-count-dropdown [id name]
  (let [v (rf/subscribe [::timing-chart-val id :count])]
    (->> [5 10 100]
         (map (fn [n]
                [:option {:value n} n]))
         (into [:select.form-select
                {:name name
                 :selected @v
                 :aria-label "test count"
                 :on-change (u/form-evt-handler [::timing-chart-changed id :count]
                                                (comp u/parse-int u/evt->value))}]))))

(defn timing-chart
  "Displays a bar chart that shows the times for the slowest tests.  By default this is for
   all suites, the top 100, but the user can configure this."
  [id results]
  (let [suite-id (str (name id) "-suite-dropdown")
        count-id (str (name id) "-count-dropdown")
        form (rf/subscribe [::timing-chart-form id])]
    [:div
     [:h4 "Test Timings"]
     [:form
      [:div.row
       [:label.form-label.col-2.col-form-label {:for suite-id} "Suite:"]
       [:div.col-2
        [suite-dropdown results id suite-id]]
       [:label.form-label.col-2.col-form-label {:for count-id} "Show top:"]
       [:div.col-2
        [test-count-dropdown id count-id]]]]
     (rf/dispatch [::timing-chart-init id results])
     [charts/chart-component id]]))
