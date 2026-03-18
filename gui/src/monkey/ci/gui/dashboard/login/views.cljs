(ns monkey.ci.gui.dashboard.login.views
  "Login screen"
  (:require [monkey.ci.gui.dashboard.components :as co]
            [monkey.ci.gui.dashboard.icons :as i]
            [monkey.ci.gui.dashboard.login.events :as e]
            [monkey.ci.gui.dashboard.login.subs :as s]
            [re-frame.core :as rf]))

;; ── Sub-components ─────────────────────────────────────────────

(defn oauth-button [{:keys [provider label icon]}]
  (let [oauth-loading @(rf/subscribe [::s/oauth-loading])
        loading?      (= oauth-loading provider)]
    [:button.btn-oauth
     {:disabled loading?
      :on-click #(rf/dispatch [::e/oauth-login provider])}
     icon
     (if loading?
       (str "Connecting to " (name provider) "…")
       label)]))

(defn error-banner []
  (let [banner @(rf/subscribe [::s/error-banner])]
    (when banner
      [:div {:style {:display       "block"
                     :margin-top    "14px"
                     :background    "#fee2e2"
                     :border        "1px solid #fca5a5"
                     :border-left   "3px solid var(--accent2)"
                     :padding       "10px 14px"
                     :font-size     "11px"
                     :color         "#7f1d1d"
                     :border-radius "2px"}}
       "✗ Authentication failure. Please try again."])))

;; ── Login card ─────────────────────────────────────────────────

(defn login-card []
  [:div.login-card.md:basis-md
   ;; Header
   [:div {:style {:text-align "center" :margin-bottom "32px"}}
    [:div.logo {:style {:font-size "22px" :font-weight 800 :letter-spacing "-0.02em" :margin-bottom "6px"}}
     "🐒 MONKEY" [:span.color-info "CI"]]
    [:div {:style {:font-size "11px" :color "var(--text-dim)" :letter-spacing "0.06em"}}
     "Sign in to your account"]]

   ;; OAuth
   [:div.login-btns
    [oauth-button {:provider :github
                   :label    "Continue with GitHub"
                   :icon     i/logo-github}]
    [oauth-button {:provider :gitlab
                   :label    "Continue with GitLab"
                   :icon     i/logo-gitlab}]
    [oauth-button {:provider :bitbucket
                   :label    "Continue with Bitbucket"
                   :icon     i/logo-bitbucket}]
    [oauth-button {:provider :codeberg
                   :label    "Continue with Codeberg"
                   :icon     i/logo-codeberg}]]

   [error-banner]

   ;; Footer links
   [:div.text-center {:style {:margin-top "28px" :padding-top "20px"
                              :border-top "1px solid var(--border)"}}
    [:span.color-dim {:style {:font-size "11px"}} "No account yet? "]
    [:a.link {:href "#" :style {:font-size "11px"}} "Create one free →"]]])

;; ── Full page ──────────────────────────────────────────────────

(defn header []
  [:div.top-bar
   [:div.logo.shrink-0.font-extrabold.text-xl.color-text
    "🐒 MONKEY" [:span.color-info "CI"]]
   [:div.flex.gap-2.items-center.ms-auto
    [co/docs-btn]
    [:a.chip {:href "#"} "Status"]]])

(defn- footer-link [lbl url]
  [:a.color-dim.no-underline {:href url :style {:font-size "10.5px"}} lbl])

(defn footer []
  [:footer
   [:a {:href "https://www.monkey-projects.be"}
    [:span.color-muted.text-sm "© 2026 Monkey Projects BV"]]
   [:div.flex.items-center.gap-4
    [footer-link "Contact" "/contact"]
    [footer-link "Terms of Use" "/terms-of-use"]
    [footer-link "Privacy Policy" "/privacy-policy"]]])

(defn login-form []
  [:div.login-card.fade-up
   {:style {:animation-delay "0.05s"}}
   [:div.text-center.mb-5
    [:div.logo.text-2xl.font-extrabold.mb-1
     {:style {:letter-spacing "-0.02em"}}
     "MONKEY" [:span.color-info "CI"]]
    [:div.text-base.color-dim
     {:style {:letter-spacing "0.06em"}}
     "Sign in to your account"]]])

(defn login-page []
  [:div.page-wrap
   [header]
   ;; Three-column main
   [:div.login-main.flex-1
    [login-card]]
   [footer]])
