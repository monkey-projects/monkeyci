(ns monkey.ci.gui.org.views
  (:require [monkey.ci.gui.charts :as charts]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.org.views-activity :as va]
            [monkey.ci.gui.org.views-repos :as vr]
            [monkey.ci.gui.org.views-stats :as vs]
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
            [monkey.ci.gui.template :as templ]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn org-icon []
  [:span.me-2 co/org-icon])

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

(defn- settings-btn [id]
  [:a.btn.btn-outline-primary
   {:href (r/path-for :page/org-settings {:org-id id})
    :title "Organization Settings"}
   [:span.me-2 [co/icon :gear]] "Settings"])

(defn- add-repo-btn [id]
  [:a.btn.btn-primary
   {:href (r/path-for :page/add-repo {:org-id id})}
   [:span.me-2 [co/icon :plus-square]] "Add Repository"])

(defn- overview-tabs
  "Displays tab pages for various organization overview screens"
  [id]
  [tabs/tabs ::overview
   [{:id :activity
     :header [:span [:span.me-2 [co/icon :activity]] "Activity"]
     :contents [va/recent-builds id]
     :current? true}
    {:id :stats
     :header [:span [:span.me-2 [co/icon :bar-chart]] "Statistics"]
     :contents [vs/org-stats id]}
    {:id :repos
     :header [:span [:span.me-2 co/repo-icon] "Repositories"]
     :contents [vr/org-repos]}]])

(defn- repo-intro [id]
  [:div.d-flex.gap-2.flex-column.mt-2
   [:div
    [:p
     "Looks like you have not configured any repositories yet.  You can add a new "
     "repository manually, or use the " [:a (vr/watch-repo-link id) "Watch Repository"]
     " button to find one in your repository manager."]
    [add-repo-btn id]]
   [:p
    "A repository points to a remote git site, that contains a MonkeyCI script.  See "
    [co/docs-link "articles/repos" "the documentation on repositories."]
    [:br]
    "Want to learn how to write your first build script?  Check out "
    [co/docs-link "categories/getting-started" "the getting started guide."]]
   [:p "Here are some useful pointers to get you started:"]
   [:ul
    [:li [templ/docs-link "/articles/intro/basic-example" "Your first build script"]]
    [:li [templ/docs-link "/articles/local-builds" "Running builds on your local machine"]]
    [:li [templ/docs-link "/articles/repos" "What are repositories?"]]
    [:li [templ/docs-link "/articles/jobs" "Intro on jobs"]]
    [:li [templ/docs-link "/categories/cookbook" "Script cookbook"]]]])

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
   [:p
    "A watched repository notifies " [:i "MonkeyCI"] " on every push "
    [:b "without having to install a webhook. "]
    "In order to be able to watch a Github repository, the "
    [:a {:href "https://github.com/apps/monkeyci-app"} "MonkeyCI Github app needs to be configured"]
    " on your organization. See the " [co/docs-link "articles/platforms" "documentation"]
    " for more details."]
   github-repo-table])

(defn add-bitbucket-repo-page []
  (rf/dispatch [:bitbucket/load-repos])
  (rf/dispatch [:org/load-bb-webhooks])
  [add-repo-page
   [:p
    "When watching a Bitbucket repository, " [:i "MonkeyCI"] " automatically installs a "
    "webhook. Unwatching it, removes the webhook. You can also do this manually. " 
    "See the " [co/docs-link "articles/platforms" "documentation"] " for more details."]
   bitbucket-repo-table])

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
