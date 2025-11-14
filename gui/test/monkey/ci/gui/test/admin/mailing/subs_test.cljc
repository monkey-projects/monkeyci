(ns monkey.ci.gui.test.admin.mailing.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.admin.mailing.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]))

(deftest mailing-list
  (h/verify-sub [::sut/mailing-list] #(db/set-mailings % ::mailings) ::mailings nil))
