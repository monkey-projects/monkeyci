(ns monkey.ci.gui.test.api-keys.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.api-keys.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest org-tokens-items
  (h/verify-sub [:org-tokens/items]
                #(db/set-org-tokens % ::test-tokens)
                ::test-tokens
                nil))

(deftest org-tokens-loading?
  (h/verify-sub [:org-tokens/loading?]
                db/set-org-tokens-loading
                true
                false))

(deftest tokens-edit
  (let [t {:description "test"}]
    (h/verify-sub [:tokens/edit ::test-id]
                  #(db/set-token-edit % ::test-id t)
                  t
                  nil)))

(deftest tokens-editing?
  (h/verify-sub [:tokens/editing? ::test-id]
                #(db/set-token-edit % ::test-id {})
                true
                false))
