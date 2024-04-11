(ns monkey.ci.gui.customer.subs
  (:require [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/info db/customer)
(u/db-sub :customer/alerts db/alerts)
(u/db-sub :customer/repo-alerts db/repo-alerts)
(u/db-sub :customer/loading? db/loading?)
(u/db-sub :customer/github-repos db/github-repos)
