(ns monkey.ci.gui.test.admin.invoicing.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.invoicing.db :as db]
            [monkey.ci.gui.admin.invoicing.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest invoices
  (h/verify-sub [::sut/invoices] #(db/set-invoices % ::test-invoices) ::test-invoices nil))

(deftest loading?
  (h/verify-sub [::sut/loading?] #(lo/set-loading % db/id) true false))

(deftest alerts
  (h/verify-sub [::sut/alerts] #(db/set-alerts % ::test-alerts) ::test-alerts nil))
