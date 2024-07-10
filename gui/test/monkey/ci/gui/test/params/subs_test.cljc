(ns monkey.ci.gui.test.params.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.params.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest customer-params
  (h/verify-sub [:customer/params]
                #(db/set-edit-params % [::test-params])
                [::test-params]
                nil))

(deftest customer-param
  (let [cp (rf/subscribe [:customer/param 0 0])]
    (testing "exists"
      (is (some? cp)))

    (testing "returns customer edit param in set"
      (is (some? (reset! app-db (db/set-edit-params {} [{:parameters
                                                         [{:name "param name"
                                                           :value "param value"}]}]))))
      (is (= {:name "param name"
              :value "param value"}
             @cp)))))

(deftest params-alerts
  (h/verify-sub [:params/alerts]
                #(db/set-alerts % ::test-alerts)
                ::test-alerts
                nil))

(deftest params-loading?
  (h/verify-sub [:params/loading?]
                #(db/mark-loading %)
                true
                false))

(deftest params-saving?
  (h/verify-sub [:params/saving?]
                #(db/mark-saving %)
                true
                false))
