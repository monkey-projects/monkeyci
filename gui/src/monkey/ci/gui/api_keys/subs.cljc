(ns monkey.ci.gui.api-keys.subs
  (:require [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :org-tokens/items db/get-org-tokens)
(u/db-sub :org-tokens/loading? db/get-org-tokens-loading)

(rf/reg-sub
 :tokens/edit
 (fn [db [_ id]]
   (db/get-token-edit db id)))

(rf/reg-sub
 :tokens/editing?
 (fn [db [_ id]]
   (some? (db/get-token-edit db id))))
