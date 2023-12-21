(ns monkey.ci.gui.test.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer-macros [deftest testing is]])
            [monkey.ci.gui.events]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest initialize-db
  (testing "initializes db"
    (is (nil? (reset! app-db nil)))
    (rf/dispatch-sync [:initialize-db])
    (is (some? @app-db))))
