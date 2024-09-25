(ns monkey.ci.gui.subs
  "Subs that are globally useful"
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :version :version)
