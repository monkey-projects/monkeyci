(ns monkey.ci.gui.subs
  "Subs that are globally useful"
  (:require [monkey.ci.gui.utils :as u]))

(u/db-sub :version :version)
