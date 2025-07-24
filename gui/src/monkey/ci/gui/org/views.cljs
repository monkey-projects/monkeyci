(ns monkey.ci.gui.org.views
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.apis.bitbucket]
            [monkey.ci.gui.apis.github]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.repo.views :as rv]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.org-settings.views :as settings]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn org-icon []
  [:span.me-2 co/org-icon])

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

(defn- latest-build [r]
  (let [build (rf/subscribe [:org/latest-build (:id r)])]
    (when @build
      [:a {:title (str "Latest build: " (:build-id @build))
           :href (r/path-for :page/build @build)}
       [co/build-result (:status @build)]])))

(defn- show-repo [c r]
  (let [repo-path (r/path-for :page/repo {:org-id (:id c)
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

(defn- repo-group-card [org title repos]
  (->> repos
       (sort-by :name)
       (map (partial show-repo org))
       (into
        [:div.card.mb-3
         [:div.card-header
          (when title
            [:h5.card-header-title [:span.me-2 co/repo-group-icon] title])]])))

(defn- show-repo-group [org [p repos]]
  (repo-group-card org (or p "(No value)") repos))

(defn- add-github-repo-btn [id]
  (when @(rf/subscribe [:login/github-user?])
    [:a.btn.btn-outline-dark.bg-light.link-dark
     {:href (r/path-for :page/add-github-repo {:org-id id})
      :title "Link an existing GitHub repository"}
     [:span.me-1 [co/icon :github]] "Watch Repository"]))

(defn- add-bitbucket-repo-btn [id]
  (when @(rf/subscribe [:login/bitbucket-user?])
    [:a.btn.btn-outline-dark.bg-light.link-dark
     {:href (r/path-for :page/add-bitbucket-repo {:org-id id})
      :title "Link an existing Bitbucket repository"}
     [:img.me-2 {:src "/img/mark-gradient-blue-bitbucket.svg" :height "25px"}]
     "Watch Repository"]))

(defn- watch-repo-btn [id]
  [:<>
   [add-github-repo-btn id]
   [add-bitbucket-repo-btn id]])

(defn- watch-repo-link [id]
  (cond
    @(rf/subscribe [:login/github-user?])
    {:href (r/path-for :page/add-github-repo {:org-id id})
     :title "Link an existing GitHub repository"}
    @(rf/subscribe [:login/bitbucket-user?])
    {:href (r/path-for :page/add-bitbucket-repo {:org-id id})
     :title "Link an existing Bitbucket repository"}))

(defn- settings-btn [id]
  [:a.btn.btn-outline-primary
   {:href (r/path-for :page/org-settings {:org-id id})
    :title "Organization Settings"}
   [:span.me-2 [co/icon :gear]] "Settings"])

(defn- add-repo-btn [id]
  [:a.btn.btn-primary
   {:href (r/path-for :page/add-repo {:org-id id})}
   [:span.me-2 [co/icon :plus-square]] "Add Repository"])

(defn- org-actions [id]
  [:div.d-flex.gap-2
   [settings-btn id]
   [add-repo-btn id]
   [watch-repo-btn id]])

(defn- org-header []
  (let [c (rf/subscribe [:org/info])]
    [:div.d-flex.gap-2
     [co/page-title {:class :me-auto} [org-icon] (:name @c)]
     [org-actions (:id @c)]]))

(defn- label-selector []
  (let [l (rf/subscribe [:org/labels])
        sel (rf/subscribe [:org/group-by-lbl])]
    (->> @l
         (map (fn [v]
                [:option {:value v} (str "Group by " v)]))
         (into [:select.form-select
                {:id :group-by-label
                 :aria-label "Label selector"
                 :value @sel
                 :on-change (u/form-evt-handler [:org/group-by-lbl-changed])}
                [:option {:value nil} "Ungrouped"]]))))

(defn- repo-name-filter []
  (let [f (rf/subscribe [:org/repo-filter])]
    [co/filter-input
     {:id :repo-name-filter
      :on-change (u/form-evt-handler [:org/repo-filter-changed])
      :placeholder "Repository name"
      :value @f}]))

(defn- repos-action-bar
  "Display a small form on top of the repositories overview to group and filter repos."
  [org]
  [:div.d-flex.flex-row.mb-2
   [:div.row.row-cols-lg-auto.g-2.align-items-center
    [:label.col {:for :group-by-label} "Repository overview"]
    [:div.col
     [label-selector]]
    [:div.col
     [repo-name-filter]]]
   [:div.ms-auto
    [co/reload-btn-sm [:org/load (:id org)]]]])

(defn- repos-list [org]
  (let [r (rf/subscribe [:org/grouped-repos])]
    (into [:<> [repos-action-bar org]]
          (if (= 1 (count @r))
            [(repo-group-card org "All Repositories" (first (vals  @r)))]
            (->> @r
                 (sort-by first)
                 (map (partial show-repo-group org)))))))

(defn- org-repos
  "Displays a list of org repositories, grouped by selected label"
  []
  (rf/dispatch [:org/load-latest-builds])
  (let [c (rf/subscribe [:org/info])]
    (if (empty? (:repos @c))
      [:p "No repositories configured for this organization.  You can start by"
       [:a.mx-1 (watch-repo-link (:id @c)) "watching one."]]
      [repos-list @c])))

(defn- with-recent-reload [id msg]
  [:div.d-flex
   [:p msg]
   [:div.ms-auto [co/reload-btn-sm [:org/load-recent-builds id]]]])

(defn- recent-builds [id]
  (rf/dispatch [:org/load-recent-builds id])
  (fn [id]
    (let [loaded? (rf/subscribe [:loader/loaded? db/recent-builds])
          recent (rf/subscribe [:org/recent-builds])]
      (if (and @loaded? (empty? @recent))
        [with-recent-reload id "No recent builds found for this organization."]
        [:<>
         (if @loaded?
           [with-recent-reload id "Recent builds for all repositories."]
           [:p "Loading recent builds for all repositories..."])
         [:div.card
          [:div.card-body
           [t/paged-table
            {:id ::recent-builds
             :items-sub [:org/recent-builds]
             :columns (concat [{:label "Repository"
                                :value (fn [b]
                                         [:a {:href (r/path-for :page/repo (u/cust->org b))}
                                          (get-in b [:repo :name])])}]
                              rv/table-columns)
             :loading {:sub [:loader/init-loading? db/recent-builds]}}]]]]))))

(defn- overview-tabs
  "Displays tab pages for various organization overview screens"
  [id]
  [tabs/tabs ::overview
   [{:id :activity
     :header [:span [:span.me-2 [co/icon :activity]] "Activity"]
     :contents [recent-builds id]
     :current? true}
    {:id :stats
     :header [:span [:span.me-2 [co/icon :bar-chart]] "Statistics"]
     :contents [org-stats id]}
    {:id :repos
     :header [:span [:span.me-2 co/repo-icon] "Repositories"]
     :contents [org-repos]}]])

(defn- repo-intro [id]
  [:<>
   [:p.mt-2
    "Looks like you have not configured any repositories yet.  You can add a new "
    "repository manually, or use the " [:a (watch-repo-link id) "Watch Repository"]
    " button to find one in your repository manager."]
   [add-repo-btn id]
   [:p.mt-2
    "A repository points to a remote git site, that contains a MonkeyCI script.  See "
    [co/docs-link "articles/repos" "the documentation on repositories."]]])

(defn- org-details [id]
  (let [o (rf/subscribe [:org/info])]
    [:<>
     [org-header]
     (if (empty? (:repos @o))
       [repo-intro id]
       [overview-tabs id])]))

(defn page
  "Organization overview page"
  [route]
  (let [id (-> route (r/path-params) :org-id)]
    (rf/dispatch [:org/init id])
    (l/default
     [:<>
      [co/alerts [:org/alerts]]
      [org-details id]])))

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
      :items-sub [:org/github-repos]
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
      :items-sub [:org/bitbucket-repos]
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
  (let [v (rf/subscribe [:org/ext-repo-filter])]
    [:form.row.row-cols-lg-auto.g-2.align-items-center.mb-2
     [:div.col
      [co/filter-input
       {:id :github-repo-name
        :placeholder "Repository name"
        :value @v
        :on-change (u/form-evt-handler [:org/ext-repo-filter-changed])}]]]))

(defn add-repo-page
  [intro table]
  (let [route (rf/subscribe [:route/current])]
    (l/default
     [:<>
      [co/page-title "Add Repository to Watch"]
      intro
      [co/alerts [:org/repo-alerts]]
      [ext-repo-filter]
      [:div.card.mb-2
       [:div.card-body
        [table]]]
      [:a {:href (r/path-for :page/org (r/path-params @route))}
       [:span.me-1 [co/icon :chevron-left]] "Back to organization"]])))

(defn add-github-repo-page []
  (rf/dispatch [:github/load-repos])
  [add-repo-page
   [:p "In order to be able to watch a Github repository, the "
    [:a {:href "https://github.com/apps/monkeyci-app"} "MonkeyCI Github app needs to be configured"]
    " on your organization."]
   github-repo-table])

(defn add-bitbucket-repo-page []
  (rf/dispatch [:bitbucket/load-repos])
  (rf/dispatch [:org/load-bb-webhooks])
  [add-repo-page nil bitbucket-repo-table])

(defn- org-id-input [org]
  [f/form-input {:id :id
                 :label "Id"
                 :value (:id org)
                 :extra-opts {:disabled true}
                 :help-msg "The internal id for this organization, used in API calls."}])

(defn- org-name-input [org]
  [f/form-input {:id :name
                 :label "Name"
                 :value (:name org)
                 :help-msg "The organization name.  We recommend to make it as unique as possible."}])

(defn page-new
  "New org page"
  []
  (l/default
   [:<>
    [co/page-title [org-icon] "New Organization"]
    [:form.mb-3
     {:on-submit (f/submit-handler [:org/create])}
     [:div.mb-3
      [org-name-input {}]]
     [:div
      [:button.btn.btn-primary.me-2 {:type :submit} [:span.me-2 [co/icon :save]] "Create Organization"]
      [co/cancel-btn [:route/goto :page/root]]]]
    [co/alerts [:org/create-alerts]]]))

(defn page-edit
  "Edit organization page (general settings)"
  [route]
  (let [org (rf/subscribe [:org/info])]
    [settings/settings-page
     ::settings/general
     [:<>
      [co/page-title [org-icon] (:name @org) ": General Settings"]
      [:form.mb-3
       {:on-submit (f/submit-handler [:org/save])}
       [:div.mb-3
        [org-id-input @org]]
       [:div.mb-3
        [org-name-input @org]]
       [:div
        [:button.btn.btn-primary.me-2 {:type :submit} [:span.me-2 [co/icon :save]] "Save Changes"]
        [co/cancel-btn [:route/goto :page/org (r/path-params route)]]]]
      [co/alerts [:org/edit-alerts]]]]))
