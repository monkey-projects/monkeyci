(ns monkey.ci.gui.org.views-repos
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- latest-build [r]
  (let [build (rf/subscribe [:org/latest-build (:id r)])]
    (when @build
      [:a {:title (str "Latest build: " (:build-id @build))
           :href (r/path-for :page/build @build)}
       [co/build-result (:status @build)]])))

(defn- show-repo [c r]
  (let [repo-path (r/path-for :page/repo {:org-id (u/org-id c)
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

(defn watch-repo-link [id]
  (cond
    @(rf/subscribe [:login/github-user?])
    {:href (r/path-for :page/add-github-repo {:org-id id})
     :title "Link an existing GitHub repository"}
    @(rf/subscribe [:login/bitbucket-user?])
    {:href (r/path-for :page/add-bitbucket-repo {:org-id id})
     :title "Link an existing Bitbucket repository"}))

(defn org-repos
  "Displays a list of org repositories, grouped by selected label"
  []
  (rf/dispatch [:org/load-latest-builds])
  (let [c (rf/subscribe [:org/info])]
    (if (empty? (:repos @c))
      [:p "No repositories configured for this organization.  You can start by"
       [:a.mx-1 (watch-repo-link (:id @c)) "watching one."]]
      [repos-list @c])))
