(ns monkey.ci.gui.test.dashboard.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.dashboard.db :as db]
            [monkey.ci.gui.dashboard.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest recent-builds
  (h/verify-sub [::sut/recent-builds] #(db/set-recent-builds % ::recent) ::recent nil))
