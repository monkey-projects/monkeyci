(ns monkey.ci.gui.admin
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.core :as c]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.admin.clean.views :as clean]
            [monkey.ci.gui.admin.credits.views :as credits]
            [monkey.ci.gui.admin.invoicing.views :as inv]
            [monkey.ci.gui.admin.login.views :as login]            
            [re-frame.core :as rf]))

(defn action-card [icon title desc link url]
  [:div.col-sm-6.col-lg-4.mb-3.mb-lg-5
   [:div.card.h-100.p-2
    [:div.card-img.text-center.text-primary.pt-2
     {:style {:font-size "6em"}}
     [:a {:href url} [co/icon icon]]]
    [:div.card-body
     [:h5.card-title title]
     [:p.card-text desc]
     [:a.card-link
      {:href url}
      link
      [:i.ms-1.bi-chevron-right.small]]]]])

(defn admin-root []
  [l/default
   [:div.container.content-space-1
    [:div.w-lg-65.text-center.mx-lg-auto.mb-7
     [:h2 "Administration Area"]
     [:p
      "Welcome to the administration site. "
      [:br]
      "Remember, with great power comes great responsibility, so " [:b.text-primary "please be careful."]]]
    ;; Admin actions
    [:div.row.mb-5.mb-sm-5
     [action-card
      :piggy-bank
      "Organization Credits"
      "Manage recurring or one-time organization credits."
      "Manage credits"
      (r/path-for :admin/credits)]

     [action-card
      :balloon
      "Dangling Builds"
      "Clean any dangling build containers that have been running for too long."
      "Clean builds"
      (r/path-for :admin/clean-builds)]

     [action-card
      :currency-euro
      "Invoicing"
      "Overview of created invoices, or create manual invoices or credit notes."
      "Go to Invoicing"
      (r/path-for :admin/invoicing)]

     [action-card
      :trash
      "Forget Users"
      "Delete any information still referring to users that have requested to be forgotten."
      "Forget users"
      (r/path-for :admin/forget-users)]]]])

(defn not-implemented []
  [l/default
   [:<>
    [:h3 "Not Implemented"]
    [:p "Oops!  Looks like this page has not been implemented yet."]]])

(def pages
  {:admin/root admin-root
   :admin/login login/page
   :admin/credits credits/overview
   :admin/org-credits credits/org-credits
   :admin/invoicing inv/page
   :admin/org-invoices inv/org-invoices
   :admin/clean-builds clean/page})

(defn render-page [route]
  (let [p (get pages (r/route-name route) not-implemented)]
    [p route]))

(def public? #{:admin/login})

(defn render-admin []
  (let [r (rf/subscribe [:route/current])
        u (rf/subscribe [:login/user])]
    (if (or (public? (r/route-name @r)) @u)
      (if @r
        (render-page @r)
        (admin-root))
      (rf/dispatch [:route/goto :admin/login]))))

(defn ^:dev/after-load reload []
  (c/reload [render-admin]))

(defn init []
  (r/start-admin!)
  (m/init)
  (reload))
