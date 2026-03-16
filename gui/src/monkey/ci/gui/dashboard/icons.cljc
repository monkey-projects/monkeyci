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
