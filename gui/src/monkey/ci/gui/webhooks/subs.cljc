(ns monkey.ci.gui.webhooks.subs
  (:require [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.webhooks.db :as db]
            [re-frame.core :as rf]))

(u/db-sub :repo/webhooks db/get-webhooks)
