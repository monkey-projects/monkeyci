(ns monkey.ci.gui.login.views
  (:require [monkey.ci.gui.layout :as l]
            [re-frame.core :as rf]))

(defn login-form []
  [:form
   [:div.mb-1
    [:label.form-label {:for "username"} "Username"]
    [:input#username.form-control]]
   [:div.mb-3
    [:label.form-label {:for "password"} "Password"]
    [:input#password.form-control {:type :password}]]
   [:button.btn.btn-primary {:type :submit} "Login"]])

(defn page []
  (l/welcome [login-form]))
