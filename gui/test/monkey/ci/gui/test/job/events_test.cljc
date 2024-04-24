(ns monkey.ci.gui.test.job.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.job.events :as sut]
            [monkey.ci.gui.test.fixtures :as tf]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(deftest job-init
  (letfn [(mock-handlers []
            (rf/reg-event-db
             :customer/maybe-load
             (fn [db _] (assoc db ::customer ::loaded)))
            (rf/reg-event-db
             :build/maybe-load
             (fn [db _] (assoc db ::build ::loaded))))]
    
    (testing "loads customer if not loaded"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::customer @app-db)))))
    
    (testing "loads build details if not present in db"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::build @app-db)))))
    
    (testing "loads job logs")))
