(ns monkey.ci.gui.admin.credits.subs
  (:require [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :credits/issues db/get-issues)
(u/db-sub :credits/issues-loading? db/issues-loading?)
(u/db-sub :credits/issue-alerts db/get-issue-alerts)
(u/db-sub :credits/issue-saving? (comp true? db/issue-saving?))
(u/db-sub :credits/show-issue-form? (comp true? db/show-issue-form?))

(u/db-sub :credits/subs db/get-subs)
(u/db-sub :credits/subs-loading? db/subs-loading?)
(u/db-sub :credits/sub-alerts db/get-sub-alerts)
(u/db-sub :credits/sub-saving? (comp true? db/sub-saving?))
(u/db-sub :credits/show-sub-form? (comp true? db/show-sub-form?))

(u/db-sub :credits/issue-all-alerts db/issue-all-alerts)
(u/db-sub :credits/issuing-all? (comp true? db/issuing-all?))
