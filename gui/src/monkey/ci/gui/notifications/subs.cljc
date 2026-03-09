(ns monkey.ci.gui.notifications.subs
  (:require [monkey.ci.gui.notifications.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub ::unregistering? (comp true? db/unregistering?))
(u/db-sub ::alerts db/alerts)
