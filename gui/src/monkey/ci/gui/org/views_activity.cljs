(ns monkey.ci.gui.org.views-activity
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.repo.views :as rv]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- with-recent-reload [id msg]
  [:div.d-flex
   [:p msg]
   [:div.ms-auto [co/reload-btn-sm [:org/load-recent-builds id]]]])

(defn- wrap-clickable [f]
  "Updates given hiccup structure to make it clickable for given org"
  (fn [build]
    [:td
     {:on-click (u/link-evt-handler [:route/goto :page/build build])}
     (f build)]))

(defn- make-clickable [col]
  (update col :value wrap-clickable))

(defn recent-builds [id]
  (rf/dispatch [:org/load-recent-builds id])
  (fn [id]
    (let [loaded? (rf/subscribe [:loader/loaded? db/recent-builds])
          recent (rf/subscribe [:org/recent-builds])
          org-id (rf/subscribe [:route/org-id])]
      (if (and @loaded? (empty? @recent))
        [with-recent-reload id "No recent builds found for this organization."]
        [:<>
         (if @loaded?
           [with-recent-reload id
            [:<> "Recent " [co/docs-link "articles/builds" "builds"] " for all "
             [co/docs-link "articles/repos" "repositories."]]]
           [:p "Loading recent builds for all repositories..."])
         [:div.card
          [:div.card-body
           [t/paged-table
            {:id ::recent-builds
             :items-sub [:org/recent-builds]
             :columns (concat
                       [{:label "Repository"
                         :value (fn [b]
                                  [:a {:href (r/path-for :page/repo {:org-id @org-id
                                                                     :repo-id (:repo-id b)})}
                                   (get-in b [:repo :name])])}]
                       (->> rv/table-columns
                            (map make-clickable)))
             :loading {:sub [:loader/init-loading? db/recent-builds]}}]]]]))))
