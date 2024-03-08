(ns monkey.ci.gui.test.server-events-test
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [monkey.ci.gui.server-events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest event-stream--start
  (testing "connects and adds to db using id"
    (rf/reg-cofx :event-stream/connector (fn [cofx _]
                                           (assoc cofx ::sut/connector (constantly ::test-connector))))
    (is (nil? (rf/dispatch-sync [:event-stream/start ::test-id [::test-evt]])))
    (is (= {:handler-evt [::test-evt]
            :source ::test-connector}
           (sut/stream-config @app-db ::test-id)))))

(deftest event-stream--stop
  (rf/reg-cofx :event-stream/connector (fn [cofx _]
                                         (assoc cofx ::sut/connector (constantly ::test-connector))))
  
  (testing "removes config from db"
    (let [_ (h/catch-fx :event-stream/close)]
      (is (nil? (rf/dispatch-sync [:event-stream/start ::test-id [::test-evt]])))
      (is (some? (sut/stream-config @app-db ::test-id)))
      (is (nil? (rf/dispatch-sync [:event-stream/stop ::test-id])))
      (is (nil? (sut/stream-config @app-db ::test-id)))))

  (testing "closes event source"
    (let [c (h/catch-fx :event-stream/close)]
      (is (nil? (rf/dispatch-sync [:event-stream/start ::test-id [::test-evt]])))
      (is (nil? (rf/dispatch-sync [:event-stream/stop ::test-id])))
      (is (= [::test-connector] @c)))))
