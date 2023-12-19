(ns monkey.ci.gui.test.martian-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.martian :as sut]
            [re-frame.db :refer [app-db]]))

(deftest init
  (testing "initializes default martian in db"
    (sut/init)
    (let [m (get-in @app-db [:martian.re-frame/martian :martian.re-frame/default-id :m])]
      (is (some? m))
      (is (not-empty (:handlers m)))
      (is (= (set (map :route-name (:handlers m)))
             (set (map :route-name sut/routes)))))))
