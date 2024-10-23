(ns monkey.ci.gui.customer.views
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.colors :as colors]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.views :as rv]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn customer-icon []
  [:span.me-2 co/customer-icon])

(defn- build-chart-config [{:keys [elapsed-seconds consumed-credits]}]
  (let [dates (->> (concat (map :date elapsed-seconds)
                           (map :date consumed-credits))
                   (set)
                   (sort))]
    {:type :line
     :data {:labels (->> dates
                         (map (comp time/format-date time/parse-epoch)))
            :datasets
            [{:label "Elapsed seconds"
              :data (map :seconds elapsed-seconds)
              :cubicInterpolationMode "monotone"
              :tension 0.4
              :yAxisID "y"}
             {:label "Consumed credits"
              :data (map :credits consumed-credits)
              :cubicInterpolationMode "monotone"
              :tension 0.4
              :yAxisID "y1"}]}
     :options
     {:scales
      {"y"
       {:type :linear
        :display true
        :position :left}
       "y1"
       {:type :linear
        :display true
        :position :right
        :grid
        {:drawOnChartArea false}}}}}))

(defn- credits-chart-config []
  (let [stats (rf/subscribe [:customer/credit-stats])]
    (when-let [{:keys [consumed available]} @stats]
      ;; TODO Colors
      {:type :doughnut
       :data {:labels [(str available " available")
                       (str consumed " consumed")]
              :datasets
              [{:label "Credits"
                :data [available consumed]}]}})))

(def stats-period-days 30)

(defn customer-stats [cust-id]
  (rf/dispatch [:customer/load-stats cust-id stats-period-days])
  (rf/dispatch [:customer/load-credits cust-id])
  (fn [cust-id]
    (let [stats (rf/subscribe [:customer/stats])]
      (rf/dispatch [:chart/update :customer/builds (build-chart-config (:stats @stats))])
      (rf/dispatch [:chart/update :customer/credits (credits-chart-config)])
      [:div.row
       [:div.col-8
        [:div.card
         [:div.card-body
          [:h5 "Statistics"]
          [:p (str "Build elapsed times and consumed credits over the past " stats-period-days " days.")]
          [charts/chart-component :customer/builds]]]]
       [:div.col-4
        [:div.card
         [:div.card-body
          [:h5 "Credits"]
          [:p "Credit consumption for this month."]
          [charts/chart-component :customer/credits]]]]])))

(defn- show-repo [c p r]
  (let [repo-path (r/path-for :page/repo {:customer-id (:id c)
                                          :repo-id (:id r)})]
    [:div.card-body.border-top
     [:div.d-flex.flex-row.align-items-start
      [:div.me-auto
       [:h6 {:title (:id r)}
        [:span.me-2 co/repo-icon]
        [:a.link-dark {:href repo-path} (:name r)]]
       [:p "Url: " [:a {:href (:url r) :target :_blank} (:url r)]]]
      [:a.btn.btn-primary
       {:href repo-path}
       [co/icon :three-dots-vertical] " Details"]]]))

(defn- show-repo-group [cust [p repos]]
  (->> repos
       (sort-by :name)
       (map (partial show-repo cust p))
       (into
        [:div.card.mb-3
         [:div.card-header
          [:h5.card-header-title [:span.me-2 co/repo-group-icon] (or p "(No value)")]]])))

(defn- add-repo-btn [id]
  [:a.btn.btn-outline-dark
   {:href (r/path-for :page/add-repo {:customer-id id})
    :title "Link an existing GitHub repository"}
   [:span.me-1 [co/icon :github]] "Follow Repository"])

(defn- params-btn [id]
  [:a.btn.btn-soft-primary
   {:href (r/path-for :page/customer-params {:customer-id id})
    :title "Configure build parameters"}
   [:span.me-2 [co/icon :gear]] "Parameters"])

(defn- customer-actions [id]
  [:<>
   [add-repo-btn id]
   [params-btn id]])

(defn- customer-header []
  (let [c (rf/subscribe [:customer/info])]
    [:div.d-flex.gap-2
     [:h3.me-auto [customer-icon] (:name @c)]
     [customer-actions (:id @c)]]))

(defn- label-selector []
  (let [l (rf/subscribe [:customer/labels])
        sel (rf/subscribe [:customer/group-by-lbl])]
    (->> @l
         (map (fn [v]
                [:option {:value v} v]))
         (into [:select.form-select
                {:id :group-by-label
                 :aria-label "Label selector"
                 :value @sel
                 :on-change (u/form-evt-handler [:customer/group-by-lbl-changed])}]))))

(defn- repo-name-filter []
  (let [f (rf/subscribe [:customer/repo-filter])]
    [:input.form-control
     {:id :repo-name-filter
      :on-change (u/form-evt-handler [:customer/repo-filter-changed])
      :value @f}]))

(defn- repos-action-bar
  "Display a small form on top of the repositories overview to group and filter repos."
  [cust]
  [:div.d-flex.flex-row.mb-2
   [:form.row.row-cols-lg-auto.g-2.align-items-center
    [:label.col {:for :group-by-label} "Repository overview, grouped by"]
    [:div.col
     [label-selector]]
    [:label.col {:for :repo-name-filter} "Filter by name"]
    [:div.col
     [repo-name-filter]]]
   [:div.ms-auto
    [co/reload-btn-sm [:customer/load (:id cust)]]]])

(defn- repos-list [cust]
  (let [r (rf/subscribe [:customer/grouped-repos])]
    (->> @r
         (sort-by first)
         (map (partial show-repo-group cust))
         (into [:<> [repos-action-bar cust]]))))

(defn- customer-repos
  "Displays a list of customer repositories, grouped by selected label"
  []
  (let [c (rf/subscribe [:customer/info])]
    (if (empty? (:repos @c))
      [:p "No repositories configured for this customer.  You can start by"
       [:a.mx-1 {:href (r/path-for :page/add-repo {:customer-id (:id @c)})} "following one."]]
      [repos-list @c])))

(defn- recent-builds [id]
  (rf/dispatch [:customer/load-recent-builds id])
  (fn [id]
    (let [loaded? (rf/subscribe [:loader/loaded? db/recent-builds])
          recent (rf/subscribe [:customer/recent-builds])]
      (if (and @loaded? (empty? @recent))
        [:p "No recent builds found for this customer."]
        [:<>
         (if @loaded?
           [:p "Recent builds for all repositories."]
           [:p "Loading recent builds for all repositories..."])
         [:div.card
          [:div.card-body
           [t/paged-table
            {:id ::recent-builds
             :items-sub [:customer/recent-builds]
             :columns (concat [{:label "Repository"
                                :value (fn [b]
                                         [:a {:href (r/path-for :page/repo b)} (get-in b [:repo :name])])}]
                              rv/table-columns)
             :loading {:sub [:loader/init-loading? db/recent-builds]}}]]]]))))

(defn- overview-tabs
  "Displays tab pages for various customer overview screens"
  [id]
  [tabs/tabs ::overview
   [{:id :overview
     :header [:span [:span.me-2 co/overview-icon] "Overview"]
     :contents [customer-stats id]}
    {:id :repos
     :header [:span [:span.me-2 co/repo-icon] "Repositories"]
     :contents [customer-repos]}
    {:id :recent
     :header [:span [:span.me-2 co/build-icon] "Recent Builds"]
     :contents [recent-builds id]
     :current? true}]])

(defn- customer-details [id]
  [:<>
   [customer-header]
   [overview-tabs id]])

(defn page
  "Customer overview page"
  [route]
  (let [id (-> route (r/path-params) :customer-id)]
    (rf/dispatch [:customer/init id])
    (l/default
     [:<>
      [co/alerts [:customer/alerts]]
      [customer-details id]])))

(defn- repo-table []
  (letfn [(name+url [{:keys [name html-url]}]
            [:a {:href html-url :target "_blank"} name])
          (visibility [{:keys [visibility]}]
            [:span.badge {:class (if (= "public" visibility)
                                   :text-bg-success
                                   :text-bg-warning)}
             visibility])
          (actions [{:keys [:monkeyci/watched?] :as repo}]
            (if watched?
              [:button.btn.btn-sm.btn-danger
               {:on-click #(rf/dispatch [:repo/unwatch (:monkeyci/repo repo)])}
               [:span.me-1.text-nowrap [co/icon :stop-circle-fill]] "Unwatch"]
              [:button.btn.btn-sm.btn-primary
               {:on-click #(rf/dispatch [:repo/watch repo])}
               [:span.me-1.text-nowrap [co/icon :binoculars-fill]] "Watch"]))]
    [t/paged-table
     {:id ::repos
      :items-sub [:customer/github-repos]
      :columns [{:label "Name"
                 :value name+url}
                {:label "Owner"
                 :value (comp :login :owner)}
                {:label "Description"
                 :value :description}
                {:label "Visibility"
                 :value visibility}
                {:label "Actions"
                 :value actions}]}]))

(defn github-repo-filter []
  (let [v (rf/subscribe [:customer/github-repo-filter])]
    [:form.row.row-cols-lg-auto.g-2.align-items-center.mb-2
     [:label.col {:for :github-repo-name} "Filter by name:"]
     [:div.col
      [:input.form-control
       {:id :github-repo-name
        :value @v
        :on-change (u/form-evt-handler [:customer/github-repo-filter-changed])}]]]))

(defn add-repo-page
  []
  (let [route (rf/subscribe [:route/current])]
    (rf/dispatch [:customer/load-github-repos])
    (l/default
     [:<>
      [:h3 "Add Repository to Watch"]
      [co/alerts [:customer/repo-alerts]]
      [github-repo-filter]
      [:div.card.mb-2
       [:div.card-body
        [repo-table]]]
      [:a {:href (r/path-for :page/customer (r/path-params @route))}
       [:span.me-1 [co/icon :chevron-left]] "Back to customer"]])))

(defn page-new
  "New customer page"
  []
  (l/default
   [:<>
    [:h3 [customer-icon] "New Customer"]
    [:form.mb-3
     {:on-submit (f/submit-handler [:customer/create])}
     [:div.mb-3
      [:label.form-label {:for :name} "Name"]
      [:input#name.form-control {:aria-describedby :name-help :name :name}]
      [:div#name-help.form-text "The customer name.  We recommend to make it as unique as possible."]]
     [:div
      [:button.btn.btn-primary.me-2 {:type :submit} [:span.me-2 [co/icon :save]] "Create Customer"]
      [co/cancel-btn [:route/goto :page/root]]]]
    [co/alerts [:customer/create-alerts]]]))
