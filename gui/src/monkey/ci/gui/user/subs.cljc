(ns monkey.ci.gui.user.subs
  (:require [monkey.ci.gui.user.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub ::general-edit db/get-general-edit-merged)
(u/db-sub ::general-alerts db/get-general-alerts)
(u/db-sub ::general-saving? db/general-saving?)
