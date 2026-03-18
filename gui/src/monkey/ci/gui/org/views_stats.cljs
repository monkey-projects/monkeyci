(ns monkey.ci.gui.org.views-stats
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.time :as time]
            [re-frame.core :as rf]))

(def stats-period-days 30)
(def color-ok "#91c9ae")
(def color-ok-2 "#5ea814")
(def color-err "#FFB1C1")

(defn elapsed-chart-config [{:keys [elapsed-seconds consumed-credits]}]
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

(defn elapsed-chart [org-id]
  (rf/dispatch [:org/load-stats org-id stats-period-days])
  (fn [_]
    (let [stats (rf/subscribe [:org/stats])]
      [charts/chart-component :org/builds (elapsed-chart-config (:stats @stats))])))

(defn credits-chart-config [stats]
  (when-let [{:keys [consumed available]} stats]
    ;; TODO Colors
    {:type :doughnut
     :data {:labels [(str available " available")
                     (str consumed " consumed")]
            :datasets
            [{:label "Credits"
              :data [available consumed]}]}}))

(defn credits-chart [org-id]
  (rf/dispatch [:org/load-credits org-id])
  (fn [_]
    (let [stats (rf/subscribe [:org/credit-stats])]
      (when @stats
        [charts/chart-component :org/credits (credits-chart-config @stats)]))))

(defn builds-chart-config [{:keys [results]}]
  (let [dates (->> (map :date results)
                   (set)
                   (sort))]
    {:type :bar
     :data {:labels (->> dates
                         (map (comp time/format-date time/parse-epoch)))
            :datasets
            [{:label "Successful builds"
              :data (map :success results)
              :backgroundColor color-ok}
             {:label "Failed builds"
              :data (map :error results)
              :backgroundColor color-err}]}
     :options
     {:scales
      {"x"
       {:stacked true}
       "y"
       {:type :linear
        :display true
        :position :left
        :stacked true}}}}))

(defn builds-history-chart []
  (let [stats (rf/subscribe [:org/build-stats])]
    (when @stats
      [charts/chart-component :org/build-success-history-stats (builds-chart-config @stats)])))

(defn build-success-chart-config [stats]
  (when-let [{:keys [results]} stats]
    (let [s (reduce + (map :success results))
          e (reduce + (map :error results))]
      {:type :doughnut
       :data {:labels [(str s " passed")
                       (str e " failed")]
              :datasets
              [{:label "Builds"
                :data [s e]
                :backgroundColor [color-ok color-err]}]}})))

(defn builds-success-chart []
  (let [stats (rf/subscribe [:org/build-stats])]
    (when @stats
      [charts/chart-component :org/build-success-stats (build-success-chart-config @stats)])))

(defn jobs-chart-config [{:keys [results]}]
  (let [dates (->> (map :date results)
                   (set)
                   (sort))]
    {:type :bar
     :data {:labels (->> dates
                         (map (comp time/format-date time/parse-epoch)))
            :datasets
            [{:label "Successful jobs"
              :data (map :success results)
              :backgroundColor color-ok-2}
             {:label "Failed jobs"
              :data (map :failure results)
              :backgroundColor color-err}]}
     :options
     {:scales
      {"x"
       {:stacked true}
       "y"
       {:type :linear
        :display true
        :position :left
        :stacked true}}}}))

(defn jobs-history-chart []
  (let [stats (rf/subscribe [:org/job-stats])]
    (when @stats
      [charts/chart-component :org/job-success-history-stats (jobs-chart-config @stats)])))

(defn job-success-chart-config [stats]
  (when-let [{:keys [results]} stats]
    (let [s (reduce + (map :success results))
          e (reduce + (map :failure results))]
      {:type :doughnut
       :data {:labels [(str s " passed")
                       (str e " failed")]
              :datasets
              [{:label "Jobs"
                :data [s e]
                :backgroundColor [color-ok-2 color-err]}]}})))

(defn jobs-success-chart []
  (let [stats (rf/subscribe [:org/job-stats])]
    (when @stats
      [charts/chart-component :org/job-success-stats (job-success-chart-config @stats)])))

(defn stats-card [title desc contents]
  [:div.card.h-100
   [:div.card-body
    [:h5 title]
    [:p desc]
    contents]])

(defn builds-charts [org-id]
  (rf/dispatch [:org/load-build-stats org-id stats-period-days])
  (fn [_]
    [:div.row.mb-4
     [:div.col-4
      [stats-card
       "Build Success Overall"
       "Percentage of successful builds"
       [builds-success-chart]]]
     [:div.col-8
      [stats-card
       "Build Success per Day"
       "The number of successful builds for this period."
       [builds-history-chart]]]]))

(defn jobs-charts [org-id]
  (rf/dispatch [:org/load-job-stats org-id stats-period-days])
  (fn [_]
    [:div.row
     [:div.col-8
      [stats-card
       "Job Success per Day"
       "The number of successful jobs for this period."
       [jobs-history-chart]]]
     [:div.col-4
      [stats-card
       "Job Success Overall"
       "Percentage of successful jobs"
       [jobs-success-chart]]]]))

(defn org-stats [org-id]
  [:<>
   [:div.row.mb-4
    [:div.col-8
     [stats-card
      "Times and Credits"
      (str "Build elapsed times and consumed credits over the past " stats-period-days " days.")
      [elapsed-chart org-id]]]
    [:div.col-4
     [stats-card
      "Available Credits"
      [:p "Credit consumption for this month."]
       [credits-chart org-id]]]]
   [builds-charts org-id]
   [jobs-charts org-id]])
