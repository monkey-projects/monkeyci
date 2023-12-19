(ns monkey.ci.gui.login.views
  (:require [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.login.subs]
            [re-frame.core :as rf]))

(defn login-form []
  (let [submitting? (rf/subscribe [:login/submitting?])]
    [:form#login-form {:on-submit (f/submit-handler [:login/submit])}
     [:div.mb-1
      [:label.form-label {:for "username"} "Username"]
      [:input#username.form-control {:name :username}]]
     [:div.mb-3
      [:label.form-label {:for "password"} "Password"]
      [:input#password.form-control {:name :password :type :password}]]
     [:button.btn.btn-primary
      (cond-> {:type :submit}
        @submitting? (assoc :disabled true))
      "Login"]]))

(defn page [_]
  [l/welcome [login-form]])
