(ns monkey.ci.gui.test.notifications.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.notifications.db :as db]
            [monkey.ci.gui.notifications.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest confirm-email
  (rf-test/run-test-sync
   (let [org {:name "test org"}
         c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:confirm-email
                            {:status 200
                             :error-code :no-error}})
     (is (some? (:martian.re-frame/martian @app-db)))
     (is (some? (swap! app-db db/set-alerts [{:type :info :message "test alert"}])))
     (rf/dispatch [::sut/confirm-email {:code (u/->b64 (pr-str {:id "test-id" :code "test-code"}))}])

     (testing "sends confirm request to backend"
       (is (= 1 (count @c)))
       (is (= :confirm-email (-> @c first (nth 2)))))

     (testing "passes parsed code"
       (let [params (-> @c first (nth 3))]
         (is (= "test-id" (:id params)))
         (is (= "test-code" (:code params)))))

     (testing "marks confirming"
       (is (true? (db/confirming? @app-db))))

     (testing "clears alerts"
       (is (empty? (db/alerts @app-db)))))))

(deftest confirm-email--success
  (is (some? (reset! app-db (db/set-confirming {}))))
  (rf/dispatch-sync [::sut/confirm-email--success {}])
  
  (testing "unmarks confirming"
    (is (not (db/confirming? @app-db)))))

(deftest confirm-email--failed
  (is (some? (reset! app-db (db/set-confirming {}))))
  (rf/dispatch-sync [::sut/confirm-email--failure {:message "test error"}]))

(deftest unregister-email
  (rf-test/run-test-sync
   (let [org {:name "test org"}
         c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:unregister-email
                            {:status 204
                             :error-code :no-error}})
     (is (some? (:martian.re-frame/martian @app-db)))
     (is (some? (swap! app-db db/set-alerts [{:type :info :message "test alert"}])))
     (rf/dispatch [::sut/unregister-email {:id "test-id"}])

     (testing "sends unregister request to backend"
       (is (= 1 (count @c)))
       (is (= :unregister-email (-> @c first (nth 2)))))

     (testing "passes params"
       (is (= "test-id" (-> @c first (nth 3) :id))))

     (testing "marks unregistering"
       (is (true? (db/unregistering? @app-db))))

     (testing "clears alerts"
       (is (empty? (db/alerts @app-db)))))))

(deftest unregister-email--success
  (is (some? (reset! app-db (db/set-unregistering {}))))
  (rf/dispatch-sync [::sut/unregister-email--success {}])
  
  (testing "unmarks unregistering"
    (is (not (db/unregistering? @app-db)))))

(deftest unregister-email--failed
  (is (some? (reset! app-db (db/set-unregistering {}))))
  (rf/dispatch-sync [::sut/unregister-email--failure {:message "test error"}])
  
  (testing "unmarks unregistering"
    (is (not (db/unregistering? @app-db))))

  (testing "sets alert"
    (is (= [:danger]
           (->> (db/alerts @app-db)
                (map :type))))))
