(ns monkey.ci.gui.test.artifact.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as bdb]
            [monkey.ci.gui.artifact.db :as db]
            [monkey.ci.gui.artifact.subs :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(rf/clear-subscription-cache!)

(deftest artifact-alerts
  (let [a (rf/subscribe [:artifact/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns alerts from db"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest artifact-downloading?
  (let [art-id (str (random-uuid))
        b (rf/subscribe [:artifact/downloading? art-id])]
    (testing "exists"
      (is (some? b)))

    (testing "returns downloading state from db"
      (is (false? @b))
      (is (some? (reset! app-db (db/set-downloading {} art-id))))
      (is (true? @b)))))
