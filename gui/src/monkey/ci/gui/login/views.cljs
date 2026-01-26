(ns monkey.ci.gui.login.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.components :as c]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.events]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.template :as t]
            [re-frame.core :as rf]))

(defn- login-btn [lbl url contents disabled?]
  [:a.btn.btn-outline-dark
   (cond-> {:href url
            :title (str "Redirects you to " lbl " for authentication, then takes you back here.")}
     disabled? (assoc :class :disabled))
   contents])

(defn- oidc-login-btn [{:keys [label url path sub logo loader extra-params]}]
  (rf/dispatch loader)
  (fn []
    (let [callback-url (str (r/origin) (r/path-for path))
          client-id (rf/subscribe sub)
          params (->> {"client_id" @client-id
                       "redirect_uri" (r/uri-encode callback-url)}
                      (merge extra-params)
                      (map #(str (first %) "=" (second %)))
                      (cs/join "&"))]
      [login-btn
       label
       (str url "?" params)
       [:<> [:img.me-2 {:src logo :height "20px"}] "Login with " label]
       (nil? @client-id)])))

(defn- github-btn []
  (oidc-login-btn {:label "Github"
                   :url "https://github.com/login/oauth/authorize"
                   :path :page/github-callback-old
                   :sub [:login/github-client-id]
                   :loader [:login/load-github-config]
                   :logo "/img/github-mark.svg"}))

(defn- codeberg-btn []
  (oidc-login-btn {:label "Codeberg"
                   :url "https://codeberg.org/login/oauth/authorize"
                   :path :page/codeberg-callback
                   :sub [:login/codeberg-client-id]
                   :loader [:login/load-codeberg-config]
                   :logo "/img/codeberg.svg"
                   ;; TODO Also add state param to protect against CSRF attacks
                   :extra-params {"response_type" "code"}}))

(defn- bitbucket-btn []
  (rf/dispatch [:login/load-bitbucket-config])
  (fn []
    (let [bitbucket-client-id (rf/subscribe [:login/bitbucket-client-id])]
      ;; Unfortunately, bitbucket does not allow to specify callback url, so it's the one
      ;; that is configured in the app.
      [login-btn
       "Bitbucket"
       (str "https://bitbucket.com/site/oauth2/authorize?client_id=" @bitbucket-client-id
            "&response_type=code")
       [:<> [:img.me-2 {:src "/img/mark-gradient-blue-bitbucket.svg" :height "20px"}] "Login with Bitbucket"]
       (nil? @bitbucket-client-id)])))

(defn login-form []
  [:div.d-flex.flex-wrap.gap-2
   [github-btn]
   [bitbucket-btn]
   [codeberg-btn]])

(defn page [_]
  [:<>
   [:div.bg-soft-primary-light.flex-fill
    [:div.container.content-space-1.content-space-t-md-3
     [:div.row.justify-content-center.align-items-lg-center
      [:div.col-md-8.col-lg-6.mb-7.mb-lg-0
       [t/logo]
       [:h1 "Welcome to MonkeyCI"]
       [:p.lead
        "A" [:span.text-primary.mx-1 "CI/CD tool"] "designed to give you"
        [:span.text-primary.mx-1 "full power"] "when building your applications."]]
      [:div.col-md-8.col-lg-6
       [:div.ps-lg-5
        [:div.card.card-lg
         [:div.card-body
          [:h3.mt-2 "Sign On"]
          [:p "Login using your existing account from one of these tools."]
          [login-form]
          [:p.mt-3.small "By logging in or signing up, you are agreeing to our "
           [:a {:href (t/site-url "/terms-of-use") :target :_blank} "terms of use"] " and our "
           [:a {:href (t/site-url "/privacy-policy") :target :_blank} "privacy policy."]]]]]]]]]
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
                                  [:h4.text-white "Unable to Authenticate"]
                                  [:p (or (:error_description q) q)]]}]])))

(defn github-callback [req]
  (callback-page req [:login/github-code-received]))

(defn bitbucket-callback [req]
  (callback-page req [:login/bitbucket-code-received]))

(defn codeberg-callback [req]
  (callback-page req [:login/codeberg-code-received]))
