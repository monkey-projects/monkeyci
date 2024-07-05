(ns monkey.ci.gui.params.subs
  (:require [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/params db/params)
(u/db-sub :params/alerts db/alerts)
(u/db-sub :params/loading? (comp true? db/loading?))
