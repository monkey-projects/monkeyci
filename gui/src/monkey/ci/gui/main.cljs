(ns monkey.ci.gui.main
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as routing]
            [re-frame.core :as rf]))

(defn init []
  (routing/start!)
  (rf/dispatch-sync [:initialize-db])
  (m/init)
  (rf/dispatch [:core/load-version])
  (c/reload))
