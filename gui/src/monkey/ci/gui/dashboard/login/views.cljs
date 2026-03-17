(ns monkey.ci.gui.dashboard.login.views
  "Login screen")

(defn header []
  [:div.top-bar
   [:div.logo.shrink-0.font-extrabold.text-xl.color-text
    "MONKEY" [:span.color-info "CI"]]
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

(defn contents []
  [:div.main
   [login-form]])

(defn login-page []
  [:<>
   [header]
   [contents]])
