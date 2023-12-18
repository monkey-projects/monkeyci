(ns monkey.ci.gui.test.login.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.login.events :as sut]
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each
  f/reset-db)

(deftest login-submit
  (testing "updates state"
    (rf/dispatch-sync [:login/submit])
    (is (true? (db/submitting? @app-db)))))

(deftest login-authenticated
  (testing "sets user in state"
    (let [user {:username "testuser"}]
      (rf/dispatch-sync [:login/authenticated user])
      (is (= user (db/user @app-db)))))

  (testing "unsets submitting state"
    (is (map? (reset! app-db (db/set-submitting {}))))
    (rf/dispatch-sync [:login/authenticated "some user"])
    (is (false? (db/submitting? @app-db)))))
