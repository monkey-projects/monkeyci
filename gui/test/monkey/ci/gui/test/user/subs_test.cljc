(ns monkey.ci.gui.test.user.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.user.db :as db]
            [monkey.ci.gui.user.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(h/verify-sub [::sut/general-edit] db/get-general-edit-merged {:receive-mailing true} {:receive-mailing true})
(h/verify-sub [::sut/general-alerts] db/get-general-alerts ::test-alerts nil)
(h/verify-sub [::sut/general-saving?] db/general-saving? true false)
