(ns monkey.ci.gui.dashboard.core
  "Dashboard entry point"
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.dashboard.events :as e]
            [monkey.ci.gui.dashboard.views :as v]
            [monkey.ci.gui.events]
            [monkey.ci.gui.martian :as m]
            #_[monkey.ci.gui.routing :as routing]
            [re-frame.core :as rf]))

(defn ^:dev/after-load reload []
  (c/reload [v/main-page]))

(defn init []
  #_(routing/start!)
  (rf/dispatch-sync [::e/initialize-db])
  (m/init)
  (reload))
