(ns monkey.ci.gui.test.customer.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.subs :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest alerts
  (let [s (rf/subscribe [:customer/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (db/set-alerts {} a))))
        (is (= a @s))))))

(deftest customer-info
  (let [ci (rf/subscribe [:customer/info])]
    (testing "exists"
      (is (some? ci)))

    (testing "holds customer info from db"
      (is (nil? @ci))
      (is (map? (reset! app-db (db/set-customer {} ::test-customer))))
      (is (= ::test-customer @ci)))))

(deftest customer-loading?
  (let [l (rf/subscribe [:customer/loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "holds loading state from db"
      (is (false? @l))
      (is (map? (reset! app-db (db/set-loading {}))))
      (is (true? @l)))))
