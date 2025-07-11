(ns monkey.ci.gui.admin.login.views
  (:require [monkey.ci.gui.admin.login.events :as e]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.subs]))

(defn login-form []
  [:form#admin-login
   {:on-submit (f/submit-handler [::e/submit])}
   [:div.mb-4
    [:label.form-label
     {:for :username}
     "Username"]
    [:input.form-control
     {:type :text
      :name :username
      :id :username}]]
   [:div.mb-4
    [:label.form-label
     {:for :password}
     "Password"]
    [:input.form-control
     {:type :password
      :name :password
      :id :password}]]
   [:div.d-grid.mb-4
    [:button.btn.btn-primary.btn-lg
     {:type :submit}
     "Log in"]]])

(defn login []
  [:<>
   [:div.bg-soft-success
    [:div.container.content-space-1.content-space-t-md-3
     [:div.mx-auto
      {:style {:max-width "30rem"}}
      [:div.card.card-lg.zi-2
       [:div.card-header.text-center
        [:h4.cardtitle "Log in"]
        [:p.card-text "This area is accessible to system administrators only."]]
       [:div.card-body
        [co/alerts [:login/alerts]]
        [login-form]]]]]]
   [co/bg-shape]])

(defn page []
  [:<>
   [l/header]
   [login]
   [l/footer]])
