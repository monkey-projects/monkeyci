(ns monkey.ci.gui.org.views-stats
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.time :as time]
            [re-frame.core :as rf]))


(defn build-chart-config [{:keys [elapsed-seconds consumed-credits]}]
  (let [dates (->> (concat (map :date elapsed-seconds)
                           (map :date consumed-credits))
                   (set)
                   (sort))]
    {:type :bar
     :data {:labels (->> dates
                         (map (comp time/format-date time/parse-epoch)))
            :datasets
            [{:label "Elapsed minutes"
              :data (map (comp #(/ % 60) :seconds) elapsed-seconds)}
             {:label "Consumed credits"
              :data (map (comp - :credits) consumed-credits)}]}
     :options
     {:scales
      {"x"
       {:stacked true}
       "y"
       {:type :linear
        :display true
        :position :left
        :stacked true}}}}))

(defn history-chart []
  (let [stats (rf/subscribe [:org/stats])]
    [charts/chart-component :org/builds (build-chart-config (:stats @stats))]))

(defn credits-chart-config [stats]
  (when-let [{:keys [consumed available]} stats]
    ;; TODO Colors
    {:type :doughnut
     :data {:labels [(str available " available")
                     (str consumed " consumed")]
            :datasets
            [{:label "Credits"
              :data [available consumed]}]}}))

(defn credits-chart []
  (let [stats (rf/subscribe [:org/credit-stats])]
    (when @stats
      [charts/chart-component :org/credits (credits-chart-config @stats)])))

(def stats-period-days 30)

(defn org-stats [org-id]
  (rf/dispatch [:org/load-stats org-id stats-period-days])
  (rf/dispatch [:org/load-credits org-id])
  (fn [org-id]
    [:div.row
     [:div.col-8
      [:div.card
       [:div.card-body
        [:h5 "History"]
        [:p
         (str "Build elapsed times and consumed credits over the past " stats-period-days " days.")]
        [history-chart]]]]
     [:div.col-4
      [:div.card
       [:div.card-body
        [:h5 "Credits"]
        [:p "Credit consumption for this month."]
        [credits-chart]]]]]))
