(ns monkey.ci.gui.login.views
  (:require [monkey.ci.gui.components :as c]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn login-form []
  (rf/dispatch [:login/load-github-config])
  (fn []
    (let [submitting? (rf/subscribe [:login/submitting?])
          callback-url (str (r/origin) (r/path-for :page/github-callback))
          github-client-id (rf/subscribe [:login/github-client-id])]
      [:form#login-form {:on-submit (f/submit-handler [:login/submit])}
       [:div.mb-1
        [:label.form-label {:for "username"} "Username"]
        [:input#username.form-control {:name :username}]]
       [:div.mb-3
        [:label.form-label {:for "password"} "Password"]
        [:input#password.form-control {:name :password :type :password}]]
       [:button.btn.btn-primary.me-1
        (cond-> {:type :submit}
          @submitting? (assoc :disabled true))
        "Login"]
       [:a.btn.btn-outline-dark
        (cond-> {:href (str "https://github.com/login/oauth/authorize?client_id=" @github-client-id
                            "&redirect_uri=" (r/uri-encode callback-url))
                 :title "Redirects you to GitHub for authentication, then takes you back here."}
          (nil? @github-client-id) (assoc :class :disabled))
        [:img.me-2 {:src "/img/github-mark.svg" :height "20px"}]
        "Login with GitHub"]])))

(defn page [_]
  [l/welcome [login-form]])

(defn github-callback [req]
  (let [q (get-in req [:parameters :query])]
    (if-let [code (:code q)]
      (do
        (rf/dispatch [:login/github-code-received code])
        [l/default
         [:<>
          [:p "Authentication succeeded, logging in to MonkeyCI..."]
          [c/alerts [:login/alerts]]]])
      [l/default
       [c/render-alert {:type :danger
                        :message [:<>
                                  [:h4 "Unable to Authenticate"]
                                  [:p (:error_description q)]]}]])))
