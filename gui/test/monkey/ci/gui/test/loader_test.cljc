(ns monkey.ci.gui.test.loader-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.loader :as sut]
            [monkey.ci.gui.routing :as r] 
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest clear-all
  (testing "removes all from db for id"
    (let [id ::some-id
          db (-> {}
                 (sut/set-loading id)
                 (sut/set-value id "test-value")
                 (sut/set-alerts id [{:type :warning}])
                 (sut/clear-all id))]
      (is (false? (sut/loading? db id)))
      (is (nil? (sut/get-value db id)))
      (is (nil? (sut/get-alerts db id))))))

(deftest before-request
  (let [id ::test-id]
    (testing "marks loading"
      (is (sut/loading? (sut/before-request {} id) id)))

    (testing "clears alerts"
      (is (empty? (-> {}
                      (sut/set-alerts id [{:type :info
                                           :message "test alert"}])
                      (sut/before-request id)
                      (sut/get-alerts id)))))))

(deftest loader-evt-handler
  (let [loader (sut/loader-evt-handler ::test-id (constantly ::request))]
    (testing "returns a fn"
      (is (fn? loader)))

    (testing "updates db"
      (is (some? (:db (loader {} [])))))

    (testing "marks loading"
      (is (-> (loader {} [])
              :db
              (sut/loading? ::test-id))))

    (testing "dispatches generated event"
      (is (= ::request (:dispatch (loader {} [])))))))

(deftest on-success
  (testing "sets value for id"
    (is (= ::test-value
           (-> (sut/on-success {} ::test-id {:body ::test-value})
               (sut/get-value ::test-id)))))

  (testing "unmarks loading"
    (let [id ::test-id]
      (is (not (-> {}
                   (sut/set-loading id)
                   (sut/on-success id {:body ::test-value})
                   (sut/loading? id))))))

  (testing "marks loaded"
    (is (sut/loaded? (sut/on-success {} ::test-id {}) ::test-id))))

(deftest on-failure
  (let [id ::test-id]
    (testing "unmarks loading"
      (is (not (-> {}
                   (sut/set-loading id)
                   (sut/on-failure id "test error" ::test-error)
                   (sut/loading? id)))))

    (testing "sets alert"
      (is (= [:danger]
             (-> {}
                 (sut/on-failure id "test error" ::test-error)
                 (sut/get-alerts id)
                 (as-> a (map :type a))))))))

(deftest on-initialize
  (let [id ::init-id]
    (testing "`nil` when already initialized"
      (is (nil? (-> {}
                    (sut/set-initialized id)
                    (sut/on-initialize id {})))))

    (testing "marks initialized"
      (is (sut/initialized? (-> {}
                                (sut/on-initialize id {})
                                :db)
                            id)))

    (testing "dispatches init events"
      (let [init-events [[:test/load]
                         [:test/other-load]]]
        (is (= init-events (-> {}
                               (sut/on-initialize id {:init-events init-events})
                               :dispatch-n)))))

    (testing "dispatches leave event if provided"
      (is (= [[:route/on-page-leave [::leave-evt]]]
             (-> {}
                 (sut/on-initialize id {:leave-event [::leave-evt]})
                 :dispatch-n))))

    (testing "dispatches stream start event for customer if handler provided"
      (is (= [[:event-stream/start id "test-customer" [::handler-evt]]]
             (-> {}
                 (r/set-current {:parameters {:path {:customer-id "test-customer"}}}) 
                 (sut/on-initialize id {:event-handler-event [::handler-evt]})
                 :dispatch-n))))))

(deftest on-leave
  (let [id ::leave-test]
    (testing "unmarks initialized"
      (is (not (sut/initialized?
                (-> {}
                    (sut/set-initialized id)
                    (sut/on-leave id)
                    :db)
                id))))

    (testing "dispatches stream stop event"
      (is (= [:event-stream/stop id]
             (-> (sut/on-leave {} id)
                 :dispatch))))))

(deftest alerts-sub
  (let [id ::test-alerts
        a (rf/subscribe [:loader/alerts id])]
    (testing "exists"
      (is (some? a)))

    (testing "returns alerts for id"
      (let [alerts [{:type :info}]]
        (is (nil? @a))
        (is (some? (reset! app-db (sut/set-alerts {} id alerts))))
        (is (= alerts @a))))))

(deftest loading-sub
  (let [id ::test-id
        l (rf/subscribe [:loader/loading? id])]
    (testing "exists"
      (is (some? l)))

    (testing "returns loading state id"
      (is (false? @l))
      (is (some? (reset! app-db (sut/set-loading {} id))))
      (is (true? @l)))))

(deftest value-sub
  (let [id ::test-value
        v (rf/subscribe [:loader/value id])]
    (testing "exists"
      (is (some? v)))

    (testing "returns value for id"
      (let [value ::test-value]
        (is (nil? @v))
        (is (some? (reset! app-db (sut/set-value {} id value))))
        (is (= value @v))))))
