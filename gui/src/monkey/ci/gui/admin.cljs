(ns monkey.ci.gui.admin
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.core :as c]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]))

(defn action-card [icon title desc link url]
  [:div.col-sm-6.col-lg-4.mb-3.mb-lg-5
   [:div.card.h-100.p-2
    [:div.card-img.text-center.text-primary.pt-2
     {:style {:font-size "6em"}}
     [co/icon icon]]
    [:div.card-body
     [:h5.card-title title]
     [:p.card-text desc]
     [:a.card-link
      {:href url}
      link
      [:i.ms-1.bi-chevron-right.small]]]]])

(defn render-admin []
  [l/default
   [:div.container.content-space-1
    [:div.w-lg-65.text-center.mx-lg-auto.mb-7
     [:h3 "Administration Area"]
     [:p
      "Welcome to the administration site. "
      [:br]
      "With great power comes great responsibility, so " [:b.text-primary "please be careful."]]]
    ;; Admin actions
    [:div.row.mb-5.mb-sm-5
     [action-card
      :piggy-bank
      "Customer Credits"
      "Manage recurring or one-time customer credits."
      "Manage credits"
      (r/path-for :admin/credits)]

     [action-card
      :balloon
      "Dangling Builds"
      "Clean any dangling build containers that have been running for too long."
      "Clean builds"
      (r/path-for :admin/clean-builds)]

     [action-card
      :trash
      "Forget Users"
      "Delete any information still referring to users that have requested to be forgotten."
      "Forget users"
      (r/path-for :admin/forget-users)]

     [action-card
      :currency-euro
      "Invoicing"
      "Overview of created invoices, or create manual invoices or credit notes."
      "Go to Invoicing"
      (r/path-for :admin/invoicing)]]]])

(defn ^:dev/after-load reload []
  (c/reload [render-admin]))

(defn init []
  (r/start-admin!)
  (reload))
