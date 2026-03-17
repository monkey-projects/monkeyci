(ns monkey.ci.gui.dashboard.login.views
  "Login screen"
  (:require [monkey.ci.gui.dashboard.login.events :as e]
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
       "✗ Invalid email or password. Please try again."])))

;; ── Login card ─────────────────────────────────────────────────

(defn login-card []
  [:div.login-card.md:basis-md
   ;; Header
   [:div {:style {:text-align "center" :margin-bottom "32px"}}
    [:div.logo {:style {:font-size "22px" :font-weight 800 :letter-spacing "-0.02em" :margin-bottom "6px"}}
     "🐒 MONKEY" [:span {:style {:color "var(--accent)"}} "CI"]]
    [:div {:style {:font-size "11px" :color "var(--text-dim)" :letter-spacing "0.06em"}}
     "Sign in to your account"]]

   ;; OAuth
   [:div.login-btns
    [oauth-button {:provider :github
                   :label    "Continue with GitHub"
                   :icon     [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "currentColor"}
                              [:path {:d "M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"}]]}]
    [oauth-button {:provider :gitlab
                   :label    "Continue with GitLab"
                   :icon     [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "#e24329"}
                              [:path {:d "M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 01-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 014.82 2a.43.43 0 01.58 0 .42.42 0 01.11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0118.6 2a.43.43 0 01.58 0 .42.42 0 01.11.18l2.44 7.51L23 13.45a.84.84 0 01-.35.94z"}]]}]
    [oauth-button {:provider :bitbucket
                   :label    "Continue with Bitbucket"
                   :icon     [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "#0052CC"}
                              [:path {:d "M.778 1.213a.768.768 0 00-.768.892l3.263 19.81c.084.5.515.868 1.022.873H19.77a.768.768 0 00.768-.646l3.263-20.037a.768.768 0 00-.768-.892zM14.52 15.53H9.522L8.17 8.466h7.696z"}]]}]
    [oauth-button {:provider :codeberg
                   :label    "Continue with Codeberg"
                   ;; TODO Replace with correct codeberg logo
                   :icon     [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "#2185D0"}
                              [:path {:d "M11.955.48C5.317.48 0 5.797 0 12.435c0 5.172 3.327 9.568 7.937 11.18.055.02.11.029.162.029.319 0 .59-.264.59-.59v-2.057c-.268.06-.51.085-.73.085-1.386 0-1.894-1.074-2.013-1.72-.134-.713-.49-1.22-.87-1.53a.54.54 0 01-.11-.09c0-.04.13-.07.26-.07.578 0 1.308.602 1.54 1.002.49.857 1.055 1.25 1.818 1.25.443 0 .892-.14 1.255-.388.14-.952.577-1.768 1.243-2.273-3.156-.522-4.973-2.09-4.973-5.082 0-1.317.52-2.567 1.478-3.554-.2-.713-.444-2.095.104-2.837 1.42 0 2.312.876 2.565 1.185.81-.266 1.7-.41 2.636-.41.943 0 1.838.146 2.65.414.252-.308 1.145-1.189 2.568-1.189.547.742.303 2.12.102 2.834.963.988 1.485 2.24 1.485 3.557 0 2.994-1.82 4.563-4.98 5.082.81.64 1.305 1.733 1.305 2.95v3.451c0 .33.27.593.593.593.051 0 .105-.01.157-.028C20.658 22.01 24 17.61 24 12.435 24 5.797 18.593.48 11.955.48z"}]]}]]

   [error-banner]

   ;; Footer links
   [:div {:style {:margin-top "28px" :padding-top "20px"
                  :border-top "1px solid var(--border)" :text-align "center"}}
    [:span {:style {:font-size "11px" :color "var(--text-dim)"}} "No account yet? "]
    [:a.link {:href "#" :style {:font-size "11px"}} "Create one free →"]]

   #_[:div {:style {:margin-top "10px" :text-align "center"}}
    [:a {:href  "#"
         :style {:font-size "10.5px" :color "var(--muted)" :text-decoration "none"
                 :border-bottom "1px solid transparent" :transition "all 0.15s"}}
     "Sign in with SSO / SAML"]]])

;; ── Full page ──────────────────────────────────────────────────

(defn header []
  [:div.top-bar
   [:div.logo.shrink-0.font-extrabold.text-xl.color-text
    "🐒 MONKEY" [:span.color-info "CI"]]
   [:div.flex.gap-2.items-center.ms-auto
    [:a.chip {:href "#"} "Docs"]
    [:a.chip {:href "#"} "Status"]]])

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
   [:div.login-main
    [login-card]]

   ;; Footer
   [:footer
    [:span {:style {:font-size "10px" :color "var(--muted)"}} "© 2026 MonkeyCI, Inc."]
    [:div {:style {:display "flex" :align-items "center" :gap "18px"}}
     [:a {:href "#" :style {:font-size "10.5px" :color "var(--text-dim)" :text-decoration "none"}} "Contact"]
     [:a {:href "#" :style {:font-size "10.5px" :color "var(--text-dim)" :text-decoration "none"}} "Terms of Use"]
     [:a {:href "#" :style {:font-size "10.5px" :color "var(--text-dim)" :text-decoration "none"}} "Privacy Policy"]]]])
