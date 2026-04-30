(ns monkey.ci.gui.test.scenes.notification-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.notifications.views :as sut]))

(defscene unsubscribe-status-busy
  "Email unsubscribe in progress"
  [sut/status-desc true])

(defscene unsubscribe-status-complete
  "Email unsubscribe in completed"
  [sut/status-desc false])

(defscene confirm-busy
  "Email confirmation in progress"
  [sut/confirm-status true])

(defscene confirm-complete
  "Email confirmation in completed"
  [sut/confirm-status false])
