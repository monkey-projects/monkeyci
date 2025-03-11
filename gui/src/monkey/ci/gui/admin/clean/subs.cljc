(ns monkey.ci.gui.admin.clean.subs
  (:require [monkey.ci.gui.admin.clean.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub ::clean-results db/get-cleaned-processes)
(u/db-sub ::clean-alerts db/get-alerts)
(u/db-sub ::cleaning? db/cleaning?)
(u/db-sub ::cleaned? db/cleaned?)
