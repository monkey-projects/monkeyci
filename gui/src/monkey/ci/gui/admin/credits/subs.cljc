(ns monkey.ci.gui.admin.credits.subs
  (:require [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :credits/customers-loading? db/customers-loading?)
(u/db-sub :credits/customers-loaded? db/customers-loaded?)
(u/db-sub :credits/customers db/get-customers)
(u/db-sub :credits/credits db/get-credits)
(u/db-sub :credits/credits-loading? db/credits-loading?)
(u/db-sub :credits/credit-alerts db/get-credit-alerts)
(u/db-sub :credits/saving? (comp true? db/saving?))
(u/db-sub :credits/show-form? (comp true? db/show-credits-form?))
