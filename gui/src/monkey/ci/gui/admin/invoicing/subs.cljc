(ns monkey.ci.gui.admin.invoicing.subs
  (:require [monkey.ci.gui.admin.invoicing.db :as db]
            [monkey.ci.gui.utils :as u]))

(u/db-sub ::invoices db/get-invoices)
(u/db-sub ::loading? db/loading?)
(u/db-sub ::alerts db/get-alerts)
