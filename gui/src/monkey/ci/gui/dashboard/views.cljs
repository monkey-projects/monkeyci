(ns monkey.ci.gui.dashboard.views
  (:require [monkey.ci.gui.dashboard.events :as e]
            [monkey.ci.gui.dashboard.icons :as i]
            [monkey.ci.gui.dashboard.subs :as s]
            [re-frame.core :as rf]))

(defn running-indicator []
  (let [n (rf/subscribe [::s/n-running-builds])]
    [:div.flex.items-center.gap-2
     [:div.dot-running.status-dot]
     [:span.status-text.font-semibold (str @n " RUNNING")]]))

(defn header []
  [:div.top-bar
   [:div.logo.shrink-0.font-extrabold.text-xl.color-text
    {:style {:letter-spacing "-0.02em"}}
    "MONKEY" [:span.color-info "CI"]]
   [:div.my-0.mx-6px.bg-border {:style {:width "1px"
                                        :height "20px"}}]
   [:div.flex.gap-6.flex-1.items-center
    [:span.color-dim {:style {:font-size "10px"}}
     "▸"]
    [:input.cmd-input {:placeholder "Search pipelines, runs, artifacts…"}]]

   [:div.flex.items-center.gap-3.ms-auto
    [running-indicator]
    [:div.chip "⌥K"]
    [:div.chip "DOCS"]
    [:div.avatar "WN"]]])

(defn- nav-item [{:keys [lbl active]}]
  [:div.nav-item (when active {:class :active}) lbl])

(defn- section-title [lbl opts]
  [:div.title.section-label.pt-4 opts lbl])

(defn- nav-section [{:keys [lbl items]}]
  (->> (map nav-item items)
       (into [:<>
              [section-title lbl {}]])))

(defn workspace-section []
  [nav-section
   {:lbl "Workspace"
    :items [{:lbl [:<> i/icon-overview "Overview"] :active true}
            {:lbl [:<> i/icon-pipelines "Repositories"]}
            {:lbl [:<> i/icon-history "Run History"]}
            {:lbl [:<> i/icon-artifacts "Artifacts"]}
            {:lbl [:<> i/icon-envs "Environments"]}]}])

(defn project-lbl [{:keys [id status builds]}]
  [:div.nav-item.justify-between
   [:span.flex.items-center.gap-2
    [:span.project-dot {:class (case status
                                 :success :project-success
                                 :error :project-fail
                                 :project-running)}]
    id]
   [:span.px-1.text-xs.color-success {:class ["bg-[rgba(0,212,170,0.1)]"]}
    builds]])

(defn active-repos-section []
  (let [repos (rf/subscribe [::s/active-repos])]
    (->> @repos
         (map project-lbl)
         (into
          [:<> [section-title "Active Repos" {:title "All repositories that have seen action the past week"}]]))))

(defn sidebar-actions []
  [:div.mt-auto.border-t.border-t-solid {:class "border-t-(--border)"}
   [:div.nav-item
    i/icon-security "Security"]
   [:div.nav-item
    i/icon-settings "Settings"]])

(defn sidebar []
  [:div.sidebar.flex.flex-col.overflow-y-auto
   [workspace-section]
   [active-repos-section]
   [sidebar-actions]])

(defn dashboard-header []
  [:div.dashboard-header.px-6
   [:div.flex.items-center.justify-between
    [:div
     [:div.title.dashboard-title "Overview / Dashboard"]
     [:h1.font-extrabold.text-2xl {:style {:letter-spacing "-0.02em"}}
      "Build Activity"]]
    [:div.flex.gap-2.items-center
     [:div.chip "Last 24h ▾"]
     [:div.chip "All Branches ▾"]
     [:button.run-btn "+ New Repo"]]]])

(defn metrics-card [{:keys [title value details progress anim-delay status]}]
  [:div.metric-card.dashboard-card.animate-in
   {:class [(str "delay-" anim-delay) (str "metric-" (name status))]}
   [:div.title.mb-2 title]
   [:div.metric-value value]
   [:div.metric-details details]
   [:div.progress-track
    [:div.progress-fill {:style {:width (str (* progress 100) "%")}}]]])

(defn- total-runs-metrics []
  (let [v (rf/subscribe [::s/metrics-total-runs])]
    [metrics-card
     {:title "Total Runs"
      :value (:value @v)
      :details [:span.color-success (str "↑ " (:diff @v) "% vs yesterday")]
      :progress (:progress @v)
      :status (:status @v)
      :anim-delay 1}]))

(defn dashboard-metrics []
  (let [metrics [{:title "Success Rate"
                  :value [:span.color-success "94.2" [:span.text-base "%"]]
                  :details "↑ 2.1% improvement"
                  :progress 0.942
                  :status :success}
                 {:title "Avg Duration"
                  :value "4m38s"
                  :details [:span.color-danger "↓ 8% slower today"]
                  :progress 0
                  :status :neutral}
                 {:title "Failures"
                  :value "74"
                  :details "↑ 5 new in last hour"
                  :progress 0.22
                  :status :danger}]]
    [:div.dashboard-metrics.flex.flex-col.gap-4.px-6
     (->> metrics
          (map-indexed (fn [idx m]
                         [metrics-card (assoc m :anim-delay (inc idx))]))
          (into [:div.grid.grid-cols-4.gap-3.animate-in
                 [total-runs-metrics]]))]))

(defn builds-filter-btn [lbl]
  [:button.chip.uppercase lbl])

(defn table-header []
  (->> ["repo / branch"
        "trigger"
        "duration"
        "progress"
        "status"
        ""]
       (map (partial vector :span))
       (into [:div.pipeline-row.header])))

(defn build-progress [parts]
  (->> parts
       (map (fn [p]
              [:div.stage-pill {:class (case p
                                         :success :stage-success
                                         :error :stage-danger
                                         :running :stage-running
                                         :stage-none)}]))
       (into [:div.stage-bar])))

(defn- build->progress-stages [{:keys [status] p :progress}]
  (let [n 4
        p (or p 0)]
    (if (= :success status)
      (repeat n :success)
      (->> (concat
            (repeat (int (* n p)) :success)
            (when (= :error status) [:error])
            (when (= :running status) [:running])
            (repeat :none))
           (remove nil?)
           (take n)))))

(defn abort-btn []
  [:button.run-btn.build-action-btn.danger "Abort"])

(defn logs-btn []
  [:button.run-btn.build-action-btn "Logs"])

(defn cancel-btn []
  [:button.run-btn.build-action-btn "Cancel"])

(defn build-row [{:keys [repo git-ref build-idx trigger-type elapsed status] :as b}]
  [:div.pipeline-row
   [:div
    [:div.font-medium.text-base.mb-1.color-text
     repo]
    [:div.text-sm.color-dim
     (str "⎇ " git-ref " · #" build-idx)]]
   [:div.text-sm.color-dim (name trigger-type)]
   [:div.text-sm.color-info
    (or elapsed "-")
    (when (= :running status)
      [:span.blink "_"])]
   [:div [build-progress (build->progress-stages b)]]
   [:div [:span.badge {:class (case status
                                :running :badge-running
                                :success :badge-success
                                :error :badge-fail
                                :badge-queued)}
          (case status
            :running "Running"
            :success "Passed"
            :error "Failed"
            :queued "Queued"
            "Unknown")]]
   [:div.text-right
    (case status
      :running [abort-btn]
      :success [logs-btn]
      :error [logs-btn]
      :queued [cancel-btn]
      nil)]])

(defn active-builds-table []
  (let [builds (rf/subscribe [::s/active-builds])]
    [:div.active-builds-table.dashboard-card
     [:div.px-4.py-3.flex.items-center.justify-between.border-b-1.border-b-solid
      {:class "border-b-(--border)"}
      [:div.font-semibold.uppercase.text-base
       {:style {:letter-spacing "0.06em"}}
       "Active Builds"]
      [:div.flex.gap-2
       [builds-filter-btn "All"]
       [builds-filter-btn "Running"]
       [builds-filter-btn "Failed"]]]
     [table-header]
     (->> @builds
          (map build-row)
          (into [:<>]))]))

(defn- live-log-entries []
  (let [entries [["09:14:02" :info "Pulling docker image node:20-alpine"]
                 ["09:14:05" :ok "Image cached, skipping pull"]
                 ["09:14:05" :notify "Starting unit-tests job..."]
                 ["09:14:06" :ok "Caches restored"]
                 ["09:14:21" :warn "Outdated dependencies found"]
                 ["09:14:29" :info "Running unit tests (app)"]
                 ["09:15:35" :ok "215/215 tests passed"]
                 ["09:15:46" :ok "Artifacts saved"]
                 ["09:15:47" :running "Starting publish job..."]]]
    (letfn [(log-entry [[ts lvl msg]]
              [:div.log-line {:class lvl}
               [:span.ts ts]
               (condp = lvl
                 :ok "✓ "
                 :warn "⚠ "
                 :running "▶ "
                 "")
               msg
               (when (= :running lvl)
                 [:span.blink "█"])])]
      (->> entries
           (map log-entry)
           (into [:div.overflow-y-auto.scrollbar-hide.px-3.py-4
                  {:class "max-h-[240px]"}])))))

(defn live-log []
  [:div.live-log.flex-1.dashboard-card
   [:div.header
    [:div.title "Live Log"]
    ;; Selected build
    [:div.flex.items-center.gap-1
     [:div.dot-running.status-dot]
     [:div.color-info.font-semibold.text-xs
      "monkeyci #1256"]]]
   [live-log-entries]])

(defn job-throughput []
  (let [throughput [0.00 0.05 0.06 0.15 0.25 0.26 0.35 0.60 0.80 0.75 0.40 0.55]]
    (letfn [(throughput-bar [v]
              [:div.spark-bar {:style {:height (str (* v 100) "%")}}])]
      [:div.dashboard-card.bg-surface.p-3
       [:div.title.mb-2 "Job Throughput - 24h"]
       (->> throughput
            (map throughput-bar)
            (into [:div.spark]))
       (->> ["00:00" "12:00" "now"]
            (map (fn [v]
                   [:span.text-xs.color-dim v]))
            (into [:div.flex.justify-between.mt-2]))])))

(defn main []
  [:div.flex.flex-1.overflow-y-auto.flex-col.grid-bg
   [dashboard-header]
   [dashboard-metrics]
   [:div.grid.gap-4.px-6.flex-1.pb-5 {:class "grid-cols-[1fr_320px]"}
    [active-builds-table]
    [:div.flex.flex-col.gap-3
     [live-log]
     [job-throughput]]]])

(defn dashboard []
  [:div.flex.flex-1.overflow-hidden
   [sidebar]
   [main]])

(defn main-page []
  [:<>
   [header]
   [dashboard]])
