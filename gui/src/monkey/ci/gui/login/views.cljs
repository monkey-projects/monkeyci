(ns monkey.ci.gui.login.views
  (:require [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

;; TODO Get from backend
(def github-client-id "Iv1.9b303bbea88afe94")

(defn origin []
  (.-origin js/location))

(defn uri-encode [s]
  (js/encodeURIComponent s))

(defn login-form []
  (let [submitting? (rf/subscribe [:login/submitting?])
        callback-url (str (origin) (r/path-for :page/github-callback))]
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
     [:a.btn.btn-secondary
      {:href (str "https://github.com/login/oauth/authorize?client_id=" github-client-id
                  "&redirect_uri=" (uri-encode callback-url))}
      "Login with GitHub"]]))

(defn page [_]
  [l/welcome [login-form]])

(defn github-callback [req]
  (let [code (get-in req [:parameters :query :code])]
    (println "Github authorization code:" code)
    [:p "This is the github callback page"]
    (rf/dispatch [:login/github-code-received code])))
