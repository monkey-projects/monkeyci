(ns monkey.ci.gui.api-keys.subs
  (:require [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.utils :as u]))

(u/db-sub :org-tokens/items db/get-org-tokens)
(u/db-sub :org-tokens/loading? db/get-org-tokens-loading)

(u/db-sub :tokens/edit db/get-token-edit)
(u/db-sub :tokens/editing? (comp some? db/get-token-edit))
(u/db-sub :tokens/saving? db/saving?)
(u/db-sub :tokens/new db/get-new-token)

