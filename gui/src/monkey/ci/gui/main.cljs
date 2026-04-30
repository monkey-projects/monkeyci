(ns monkey.ci.gui.main
  "Main application site, for regular users"
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.events]
            [monkey.ci.gui.main.routing :as mr]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.pages :as p]
            [re-frame.core :as rf]))

(defn ^:dev/after-load reload []
  (c/reload [p/render]))

(defn init []
  (mr/start!)
  (rf/dispatch-sync [:initialize-db])
  (m/init)
  (rf/dispatch [:core/load-version])
  (reload))
