(ns monkey.ci.gui.admin.credits.subs
  (:require [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :credits/customers-loading? db/customers-loading?)
(u/db-sub :credits/customers-loaded? db/customers-loaded?)
(u/db-sub :credits/customers db/get-customers)
