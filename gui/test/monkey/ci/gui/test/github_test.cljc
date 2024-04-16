(ns monkey.ci.gui.test.github-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.github :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest github-process-response
  (testing "converts response body keys to kebab-case"
    (rf-test/run-test-sync
     (rf/reg-event-db
      ::test-event
      (fn [db [_ val]]
        (assoc db ::response val)))
     (rf/dispatch [:github/process-response [::test-event] {"test_key" "test value"}])
     (is (= {:test-key "test value"} (::response @app-db))))))
