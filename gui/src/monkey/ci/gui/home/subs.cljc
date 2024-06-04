(ns monkey.ci.gui.home.subs
  (:require [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :user/customers db/customers)
(u/db-sub :user/alerts db/alerts)

