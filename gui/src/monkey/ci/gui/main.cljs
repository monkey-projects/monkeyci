(ns monkey.ci.gui.main
  "Main application site, for regular users"
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.pages :as p]
            [monkey.ci.gui.routing :as routing]
            [re-frame.core :as rf]))

(defn ^:dev/after-load reload []
  (c/reload [p/render]))

(defn init []
  (routing/start!)
  (rf/dispatch-sync [:initialize-db])
  (m/init)
  (rf/dispatch [:core/load-version])
  (reload))
