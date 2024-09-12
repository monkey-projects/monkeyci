(ns monkey.ci.gui.login.views
  (:require [monkey.ci.gui.components :as c]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn- login-btn [url contents disabled?]
  [:a.btn.btn-outline-dark
   (cond-> {:href url
            :title "Redirects you to GitHub for authentication, then takes you back here."}
     disabled? (assoc :class :disabled))
   contents])

(defn- github-btn []
  (rf/dispatch [:login/load-github-config])
  (let [callback-url (str (r/origin) (r/path-for :page/github-callback))
        github-client-id (rf/subscribe [:login/github-client-id])]
    [login-btn
     (str "https://github.com/login/oauth/authorize?client_id=" @github-client-id
          "&redirect_uri=" (r/uri-encode callback-url))
     [:<> [:img.me-2 {:src "/img/github-mark.svg" :height "20px"}] "Login with GitHub"]
     (nil? @github-client-id)]))

(defn- bitbucket-btn []
  (rf/dispatch [:login/load-bitbucket-config])
  (let [bitbucket-client-id (rf/subscribe [:login/bitbucket-client-id])]
    ;; Unfortunately, bitbucket does not allow to specify callback url, so it's the one
    ;; that is configured in the app.
    [login-btn
     (str "https://bitbucket.com/site/oauth2/authorize?client_id=" @bitbucket-client-id
          "&response_type=code")
     [:<> [:img.me-2 {:src "/img/mark-gradient-blue-bitbucket.svg" :height "20px"}] "Login with Bitbucket"]
     (nil? @bitbucket-client-id)]))

(defn login-form []
  [:div.d-flex.flex-wrap.gap-2
   [github-btn]
   [bitbucket-btn]])

(defn page [_]
  [:<>
   [:div.bg-soft-primary-light
    [:div.container.content-space-1.content-space-t-md-3
     [:div.row.justify-content-center.align-items-lg-center
      [:div.col-md-8.col-lg-6.mb-7.mb-lg-0
       [:img.img-fluid.rounded {:src "/img/monkeyci-bw-small.png" :title "Placeholder Logo"}]
       [:h1 "Welcome to MonkeyCI"]
       [:p.lead
        "A CI/CD tool designed to give you full power when building your applications."]]
      [:div.col-md-8.col-lg-6
       [:div.ps-lg-5
        [:div.card.card-lg
         [:div.card-body
          [:h3.mt-2 "Sign On"]
          [:p "Login using your existing account from one of these tools."]
          [login-form]]]]]]]]
   [l/footer]])

(defn- callback-page [req evt]
  (let [q (get-in req [:parameters :query])]
    (if-let [code (:code q)]
      (do
        (rf/dispatch (conj evt code))
        [l/default
         [:<>
          [:p "Authentication succeeded, logging in to MonkeyCI..."]
          [c/alerts [:login/alerts]]]])
      [l/default
       [c/render-alert {:type :danger
                        :message [:<>
                                  [:h4 "Unable to Authenticate"]
                                  [:p (or (:error_description q) q)]]}]])))

(defn github-callback [req]
  (callback-page req [:login/github-code-received]))

(defn bitbucket-callback [req]
  (callback-page req [:login/bitbucket-code-received]))
