(ns monkey.ci.gui.test.job.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.job.events :as sut]
            [monkey.ci.gui.test.fixtures :as tf]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest job-init
  (testing "loads job details if not present in db")
  (testing "loads job logs"))
