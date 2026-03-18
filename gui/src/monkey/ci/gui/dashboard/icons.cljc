(ns monkey.ci.gui.dashboard.icons)

(def icon-overview
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:rect {:x "3" :y "3" :width "7" :height "7" :rx "1"}]
   [:rect {:x "14" :y "3" :width "7" :height "7" :rx "1"}]
   [:rect {:x "3" :y "14" :width "7" :height "7" :rx "1"}]
   [:rect {:x "14" :y "14" :width "7" :height "7" :rx "1"}]])

(def icon-pipelines
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:path {:d "M5 3l14 9-14 9V3z"}]])

(def icon-history
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:circle {:cx "12" :cy "12" :r "9"}]
   [:path {:d "M12 7v5l3 3"}]])

(def icon-artifacts
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:path
    {:d
     "M20 7H4a2 2 0 00-2 2v6a2 2 0 002 2h16a2 2 0 002-2V9a2 2 0 00-2-2z"}]
   [:path {:d "M16 7V5a2 2 0 00-2-2h-4a2 2 0 00-2 2v2"}]])

(def icon-envs
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:path {:d "M9 17H5a2 2 0 01-2-2V5a2 2 0 012-2h4"}]
   [:path {:d "M15 17h4a2 2 0 002-2V5a2 2 0 00-2-2h-4"}]
   [:line {:x1 "12" :y1 "22" :x2 "12" :y2 "17"}]])

(def icon-security
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:path {:d "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"}]])

(def icon-settings
  [:svg
   {:width "14"
    :height "14"
    :fill "none"
    :stroke "currentColor"
    :stroke-width "1.5"
    :view-box "0 0 24 24"}
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path
    {:d "M19.07 4.93a10 10 0 010 14.14M4.93 4.93a10 10 0 000 14.14"}]])

(def logo-github
  [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "currentColor"}
   [:path {:d "M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"}]])

(def logo-gitlab
  [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "#e24329"}
   [:path {:d "M22.65 14.39L12 22.13 1.35 14.39a.84.84 0 01-.3-.94l1.22-3.78 2.44-7.51A.42.42 0 014.82 2a.43.43 0 01.58 0 .42.42 0 01.11.18l2.44 7.49h8.1l2.44-7.51A.42.42 0 0118.6 2a.43.43 0 01.58 0 .42.42 0 01.11.18l2.44 7.51L23 13.45a.84.84 0 01-.35.94z"}]])

(def logo-bitbucket
  [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "#0052CC"}
   [:path {:d "M.778 1.213a.768.768 0 00-.768.892l3.263 19.81c.084.5.515.868 1.022.873H19.77a.768.768 0 00.768-.646l3.263-20.037a.768.768 0 00-.768-.892zM14.52 15.53H9.522L8.17 8.466h7.696z"}]])

(def logo-codeberg
  [:img {:src "/img/codeberg.svg"}])
