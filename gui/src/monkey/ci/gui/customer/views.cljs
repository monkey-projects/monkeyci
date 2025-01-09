(ns monkey.ci.gui.customer.views
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.colors :as colors]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.apis.bitbucket]
            [monkey.ci.gui.apis.github]
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
  (let [stats (rf/subscribe [:customer/stats])]
    [charts/chart-component-2 :customer/builds (build-chart-config (:stats @stats))]))

(defn credits-chart-config [stats]
  (when-let [{:keys [consumed available]} stats]
    ;; TODO Colors
    {:type :doughnut
     :data {:labels [(str available " available")
                     (str consumed " consumed")]
            :datasets
            [{:label "Credits"
              :data [available consumed]}]}
     ;; TODO Either disable animations, or make transition work
     #_:options
     #_{:animation false}}))

(defn credits-chart []
  (let [stats (rf/subscribe [:customer/credit-stats])]
    [charts/chart-component-2 :customer/credits (credits-chart-config @stats)]))

(def stats-period-days 30)

(defn customer-stats [cust-id]
  (rf/dispatch [:customer/load-stats cust-id stats-period-days])
  (rf/dispatch [:customer/load-credits cust-id])
  (fn [cust-id]
    [:div.row
     [:div.col-8
      [:div.card
       [:div.card-body
        [:h5 "History"]
        [:p (str "Build elapsed times and consumed credits over the past " stats-period-days " days.")]
        [history-chart]]]]
     [:div.col-4
      [:div.card
       [:div.card-body
        [:h5 "Credits"]
        [:p "Credit consumption for this month."]
        [credits-chart]]]]]))

(defn- latest-build [r]
  (let [build (rf/subscribe [:customer/latest-build (:id r)])]
    (when @build
      [:a {:title (str "Latest build: " (:build-id @build))
           :href (r/path-for :page/build @build)}
       [co/build-result (:status @build)]])))

(defn- show-repo [c r]
  (let [repo-path (r/path-for :page/repo {:customer-id (:id c)
                                          :repo-id (:id r)})]
    [:div.card-body.border-top
     [:div.d-flex.flex-row.align-items-start
      [:div.me-auto
       [:h6 {:title (:id r)}
        [:span.me-2 co/repo-icon]
        [:a.link-dark.me-3 {:href repo-path} (:name r)]
        [latest-build r]]
       [:p "Url: " [:a {:href (:url r) :target :_blank} (:url r)]]]
      [:a.btn.btn-primary
       {:href repo-path}
       [co/icon :three-dots-vertical] " Details"]]]))

(defn- repo-group-card [cust title repos]
  (->> repos
       (sort-by :name)
       (map (partial show-repo cust))
       (into
        [:div.card.mb-3
         [:div.card-header
          (when title
            [:h5.card-header-title [:span.me-2 co/repo-group-icon] title])]])))

(defn- show-repo-group [cust [p repos]]
  (repo-group-card cust (or p "(No value)") repos))

(defn- add-github-repo-btn [id]
  (when @(rf/subscribe [:login/github-user?])
    [:a.btn.btn-outline-dark.bg-light.link-dark
     {:href (r/path-for :page/add-github-repo {:customer-id id})
      :title "Link an existing GitHub repository"}
     [:span.me-1 [co/icon :github]] "Watch Repository"]))

(defn- add-bitbucket-repo-btn [id]
  (when @(rf/subscribe [:login/bitbucket-user?])
    [:a.btn.btn-outline-dark.bg-light.link-dark
     {:href (r/path-for :page/add-bitbucket-repo {:customer-id id})
      :title "Link an existing Bitbucket repository"}
     [:img.me-2 {:src "/img/mark-gradient-blue-bitbucket.svg" :height "25px"}]
     "Watch Repository"]))

(defn- params-btn [id]
  [:a.btn.btn-soft-primary
   {:href (r/path-for :page/customer-params {:customer-id id})
    :title "Configure build parameters"}
   [:span.me-2 [co/icon :gear]] "Parameters"])

(defn- ssh-keys-btn [id]
  [:a.btn.btn-soft-primary
   {:href (r/path-for :page/customer-ssh-keys {:customer-id id})
    :title "Configure ssh keys to access private repositories"}
   [:span.me-2 [co/icon :key]] "SSH Keys"])

(defn- customer-actions [id]
  [:<>
   [add-github-repo-btn id]
   [add-bitbucket-repo-btn id]
   [params-btn id]
   [ssh-keys-btn id]])

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
                [:option {:value v} (str "Group by " v)]))
         (into [:select.form-select
                {:id :group-by-label
                 :aria-label "Label selector"
                 :value @sel
                 :on-change (u/form-evt-handler [:customer/group-by-lbl-changed])}
                [:option {:value nil} "Ungrouped"]]))))

(defn- repo-name-filter []
  (let [f (rf/subscribe [:customer/repo-filter])]
    [co/filter-input
     {:id :repo-name-filter
      :on-change (u/form-evt-handler [:customer/repo-filter-changed])
      :placeholder "Repository name"
      :value @f}]))

(defn- repos-action-bar
  "Display a small form on top of the repositories overview to group and filter repos."
  [cust]
  [:div.d-flex.flex-row.mb-2
   [:form.row.row-cols-lg-auto.g-2.align-items-center
    [:label.col {:for :group-by-label} "Repository overview"]
    [:div.col
     [label-selector]]
    [:div.col
     [repo-name-filter]]]
   [:div.ms-auto
    [co/reload-btn-sm [:customer/load (:id cust)]]]])

(defn- repos-list [cust]
  (let [r (rf/subscribe [:customer/grouped-repos])]
    (into [:<> [repos-action-bar cust]]
          (if (= 1 (count @r))
            [(repo-group-card cust "All Repositories" (first (vals  @r)))]
            (->> @r
                 (sort-by first)
                 (map (partial show-repo-group cust)))))))

(defn- customer-repos
  "Displays a list of customer repositories, grouped by selected label"
  []
  (rf/dispatch [:customer/load-latest-builds])
  (let [c (rf/subscribe [:customer/info])]
    (if (empty? (:repos @c))
      [:p "No repositories configured for this customer.  You can start by"
       [:a.mx-1 {:href (r/path-for :page/add-github-repo {:customer-id (:id @c)})} "watching one."]]
      [repos-list @c])))

(defn- with-recent-reload [id msg]
  [:div.d-flex
   [:p msg]
   [:div.ms-auto [co/reload-btn-sm [:customer/load-recent-builds id]]]])

(defn- recent-builds [id]
  (rf/dispatch [:customer/load-recent-builds id])
  (fn [id]
    (let [loaded? (rf/subscribe [:loader/loaded? db/recent-builds])
          recent (rf/subscribe [:customer/recent-builds])]
      (if (and @loaded? (empty? @recent))
        [with-recent-reload id "No recent builds found for this customer."]
        [:<>
         (if @loaded?
           [with-recent-reload id "Recent builds for all repositories."]
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
    {:id :recent
     :header [:span [:span.me-2 co/build-icon] "Recent Builds"]
     :contents [recent-builds id]
     :current? true}
    {:id :repos
     :header [:span [:span.me-2 co/repo-icon] "Repositories"]
     :contents [customer-repos]}]])

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

(defn- ext-repo-actions [watch-evt unwatch-evt {:keys [:monkeyci/watched?] :as repo}]
  (if watched?
    [:button.btn.btn-sm.btn-danger
     {:on-click #(rf/dispatch [unwatch-evt repo])}
     [:span.me-1.text-nowrap [co/icon :stop-circle-fill]] "Unwatch"]
    [:button.btn.btn-sm.btn-primary
     {:on-click #(rf/dispatch [watch-evt repo])}
     [:span.me-1.text-nowrap [co/icon :binoculars-fill]] "Watch"]))

(defn- github-repo-table []
  (letfn [(name+url [{:keys [name html-url]}]
            [:a {:href html-url :target "_blank"} name])
          (visibility [{:keys [visibility]}]
            [:span.badge {:class (if (= "public" visibility)
                                   :bg-success
                                   :bg-warning)}
             visibility])]
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
                 :value (partial ext-repo-actions
                                 :repo/watch-github
                                 :repo/unwatch-github)}]}]))

(defn- bitbucket-repo-table []
  (letfn [(name+url [{:keys [name links]}]
            [:a {:href (get-in links [:html :href]) :target "_blank"} name])
          (visibility [{:keys [is-private]}]
            [:span.badge {:class (if is-private
                                   :bg-warning
                                   :bg-success)}
             (if is-private "private" "public")])]
    [t/paged-table
     {:id ::repos
      :items-sub [:customer/bitbucket-repos]
      :columns [{:label "Name"
                 :value name+url}
                {:label "Workspace"
                 :value (comp :name :workspace)}
                {:label "Description"
                 :value :description}
                {:label "Visibility"
                 :value visibility}
                {:label "Actions"
                 :value (partial ext-repo-actions
                                 :repo/watch-bitbucket
                                 :repo/unwatch-bitbucket)}]}]))

(defn ext-repo-filter []
  (let [v (rf/subscribe [:customer/ext-repo-filter])]
    [:form.row.row-cols-lg-auto.g-2.align-items-center.mb-2
     [:div.col
      [co/filter-input
       {:id :github-repo-name
        :placeholder "Repository name"
        :value @v
        :on-change (u/form-evt-handler [:customer/ext-repo-filter-changed])}]]]))

(defn add-repo-page
  [table]
  (let [route (rf/subscribe [:route/current])]
    (l/default
     [:<>
      [:h3 "Add Repository to Watch"]
      [co/alerts [:customer/repo-alerts]]
      [ext-repo-filter]
      [:div.card.mb-2
       [:div.card-body
        [table]]]
      [:a {:href (r/path-for :page/customer (r/path-params @route))}
       [:span.me-1 [co/icon :chevron-left]] "Back to customer"]])))

(defn add-github-repo-page []
  (rf/dispatch [:github/load-repos])
  [add-repo-page github-repo-table])

(defn add-bitbucket-repo-page []
  (rf/dispatch [:bitbucket/load-repos])
  (rf/dispatch [:customer/load-bb-webhooks])
  [add-repo-page bitbucket-repo-table])

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
