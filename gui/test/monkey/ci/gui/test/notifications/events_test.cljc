(ns monkey.ci.gui.test.notifications.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.notifications.db :as db]
            [monkey.ci.gui.notifications.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest unregister-email
  (rf-test/run-test-sync
   (let [org {:name "test org"}
         c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:unregister-email
                            {:status 204
                             :error-code :no-error}})
     (is (some? (:martian.re-frame/martian @app-db)))
     (rf/dispatch [:notifications/unregister-email {:id "test-id"}])

     (testing "sends unregister request to backend"
       (is (= 1 (count @c)))
       (is (= :unregister-email (-> @c first (nth 2)))))

     (testing "passes params"
       (is (= "test-id" (-> @c first (nth 3) :id))))

     (testing "marks unregistering"
       (is (true? (db/unregistering? @app-db)))))))
