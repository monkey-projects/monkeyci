(ns monkey.ci.gui.test-results
  "Displays test results"
  (:require [clojure.string :as cs]
            [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.colors :as colors]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.modals :as m]
            [monkey.ci.gui.table :as t]
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

(rf/reg-sub
 :test/selected
 (fn [db _]
   (::selected-test db)))

(rf/reg-event-db
 :test/select
 (fn [db [_ tc]]
   (assoc db ::selected-test tc)))

(defn test-details-modal []
  (let [tc (rf/subscribe [:test/selected])]
    [m/modal
     ::test-details
     [:h3 (:test-case @tc)]
     [co/log-contents (->> (concat (:failures @tc) (:errors @tc))
                           (mapcat (fn [err]
                                     (some-> (or (:description err) (:message err))
                                             (cs/split #"\n"))))
                           (interpose [:br]))]]))

(defn test-results
  "Renders a paged table with given id that retrieves test result info from 
   the specified sub."
  [id tr-sub]
  (letfn [(result-val [tc]
            (co/build-result (if (success? tc) "success" "failure")))
          (show-test-details [tc]
            (when-not (success? tc)
              (rf/dispatch [:test/select tc])
              #?(:cljs (-> (js/bootstrap.Modal. (u/->dom-id ::test-details))
                           (.show)))))]
    [:<>
     [test-details-modal]
     [t/paged-table {:id id
                     :items-sub tr-sub
                     :columns [{:label "Test case"
                                :value :test-case}
                               {:label "Result"
                                :value result-val}
                               {:label "Elapsed"
                                :value #(str (:time %) "s")}]
                     :on-row-click show-test-details}]]))

(def all-suites "$all$")

(defn- timings->chart
  "Converts test result information into bar chart data for the top `n` slowest tests
   matching the suite (if any)."
  [results {:keys [suite] n :count}]
  (let [matches-suite? (fn [{:keys [name]}]
                         (or (nil? suite) (= all-suites suite) (= suite name)))
        cases (->> results
                   (filter matches-suite?)
                   (mapcat :test-cases)
                   (remove (comp nil? :time))
                   (sort-by :time)
                   (reverse)
                   (take n))]
    {:type "bar"
     :data {:labels (map :test-case cases)
            :datasets [{:label "Seconds Elapsed"
                        :data (map :time cases)
                        :backgroundColor colors/primary}]}
     :options {:indexAxis "y"
               :maxBarThickness 40}}))

(def default-chart-form {:count 5})

(defn- timing-chart-form [db id]
  (merge default-chart-form
         (get-in db [::timing-chart id])))

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
 ::timing-chart-changed
 (fn [{:keys [db]} [_ id prop v]]
   (let [results (timing-chart-results db id)]
     {:db (assoc-in db [::timing-chart id prop] v)})))

(defn suite-dropdown [suites id name]
  (let [v (rf/subscribe [::timing-chart-val id :suite])]
    (->> (concat [nil] suites)
         (map (fn [{:keys [name]}]
                [:option {:value (or name all-suites)} (or name "All")]))
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
        conf (rf/subscribe [::timing-chart-form id])]
    [:div
     [:h4 "Test Timings"]
     [:form
      [:div.d-flex.gap-2
       [:label.form-label.col-form-label {:for suite-id} "Suite:"]
       [:div
        [suite-dropdown results id suite-id]]
       [:label.form-label.col-form-label {:for count-id} "Show top:"]
       [:div
        [test-count-dropdown id count-id]]]]
     [:div #_{:style {:height "20em"}}
      [charts/chart-component id (timings->chart results @conf)]]]))
