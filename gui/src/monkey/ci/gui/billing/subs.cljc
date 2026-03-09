(ns monkey.ci.gui.billing.subs
  (:require [monkey.ci.gui.billing.db :as db]
            [monkey.ci.gui.utils :as u]))

(u/db-sub ::billing-alerts db/get-billing-alerts)
(u/db-sub ::billing-loading? db/billing-loading?)
(u/db-sub ::invoicing-settings db/get-invoicing-settings)
(u/db-sub ::saving? db/saving?)
