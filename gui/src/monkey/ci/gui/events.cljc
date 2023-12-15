(ns monkey.ci.gui.events
  (:require [re-frame.core :as rc]))

(rc/reg-event-db
 :initialize-db
 (fn [_ _]
   {}))
