(ns monkey.ci.gui.test.params.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.params.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest customer-load-params
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [params [{:parameters [{:name "test-param" :value "test-val"}]}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-params {:status 200
                                                    :body params
                                                    :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/load "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-params (-> @c first (nth 2)))))))
  
  (testing "marks loading"
    (rf/dispatch-sync [:params/load "test-cust"])
    (is (true? (db/loading? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:params/load "test-cust"])
    (is (nil? (db/alerts @app-db)))))

(deftest load-params--success
  (testing "sets params in db"
    (rf/dispatch-sync [:params/load--success {:body [::test-params]}])
    (is (= [::test-params] (db/params @app-db))))

  (testing "sets edit params in db"
    (rf/dispatch-sync [:params/load--success {:body [::test-params]}])
    (is (= [::test-params] (db/edit-params @app-db))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--success {:body [::test-params]}])
    (is (not (db/loading? @app-db)))))

(deftest load-params--failed
  (testing "sets alert in db"
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (not (db/loading? @app-db)))))

(deftest new-set
  (testing "adds new empty set to db"
    (is (some? (reset! app-db (db/set-edit-params {} [{:description "set 1"}]))))
    (rf/dispatch-sync [:params/new-set])
    (is (= [{:description "set 1"}
            {}]
           (db/edit-params @app-db)))))

(deftest cancel-set
  (testing "resets form data to original db values"
    (is (some? (reset! app-db (-> {}
                                  (db/set-params [{:description "original set"}
                                                  {:description "second set"}])
                                  (db/set-edit-params [{:description "updated set"}
                                                       {:description "second updated set"}])))))
    (rf/dispatch-sync [:params/cancel-set 0])
    (is (= [{:description "original set"}
            {:description "second updated set"}]
           (db/edit-params @app-db))))

  (testing "removes set that does not occur in original"
    (is (some? (reset! app-db (-> {}
                                  (db/set-params [{:description "original set"}])
                                  (db/set-edit-params [{:description "updated set"}
                                                       {:description "new set"}])))))
    (rf/dispatch-sync [:params/cancel-set 1])
    (is (= [{:description "updated set"}]
           (db/edit-params @app-db)))))

(deftest delete-set
  (testing "removes set from db"
    (is (some? (reset! app-db (db/set-edit-params {} [{:description "first set"}
                                                      {:description "second set"}]))))
    (rf/dispatch-sync [:params/delete-set 0])
    (is (= [{:description "second set"}]
           (db/edit-params @app-db)))))

(deftest new-param
  (testing "adds empty param to set in db"
    (is (some? (reset! app-db (db/set-edit-params {} [{:description "first set"
                                                       :parameters [{:name "first param"
                                                                     :value "first value"}]}
                                                      {:description "second set"}]))))
    (rf/dispatch-sync [:params/new-param 0])
    (is (= {:description "first set"
            :parameters [{:name "first param"
                          :value "first value"}
                         {}]}
           (-> (db/edit-params @app-db)
               first)))))

(deftest delete-param
  (testing "removes param at idx from set"
    (is (some? (reset! app-db (db/set-edit-params {} [{:description "first set"
                                                       :parameters [{:name "first param"
                                                                     :value "first value"}]}
                                                      {:description "second set"}]))))
    (rf/dispatch-sync [:params/delete-param 0 0])
    (is (= [{:description "first set"
             :parameters []}
            {:description "second set"}]
           (db/edit-params @app-db)))))

(deftest save-all
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [params [{:description "test params"
                    :parameters [{:name "test-param" :value "test-val"}]}]
           c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (db/set-edit-params {} params))
       (h/initialize-martian {:update-customer-params {:status 200
                                                       :body params
                                                       :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/save-all])
       (is (= 1 (count @c)))
       (is (= :update-customer-params (-> @c first (nth 2)))))))

  (testing "marks saving"
    (rf/dispatch-sync [:params/save-all])
    (is (true? (db/saving? @app-db))))

  (testing "clears alerts"
    (reset! app-db (db/set-alerts {} [{:type :info}]))
    (rf/dispatch-sync [:params/save-all])
    (is (empty? (db/alerts @app-db)))))

(deftest save-all--success
  (testing "unmarks saving"
    (reset! app-db (db/mark-saving {}))
    (rf/dispatch-sync [:params/save-all--success {}])
    (is (not (db/saving? @app-db))))

  (testing "sets params and edit params to result"
    (let [params [::params]]
      (rf/dispatch-sync [:params/save-all--success {:body params}])
      (is (= params (db/params @app-db)))
      (is (= params (db/edit-params @app-db))))))

(deftest save-all--failed
  (testing "sets alert in db"
    (rf/dispatch-sync [:params/save-all--failed "test error"])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type))))

  (testing "unmarks saving"
    (is (some? (reset! app-db (db/mark-saving {}))))
    (rf/dispatch-sync [:params/save-all--failed "test error"])
    (is (not (db/saving? @app-db)))))

(deftest cancel-all
  (testing "resets all form data to original db values"
    (is (some? (reset! app-db (-> {}
                                  (db/set-params [{:description "original set"}
                                                  {:description "second set"}])
                                  (db/set-edit-params [{:description "updated set"}
                                                       {:description "second updated set"}])))))
    (rf/dispatch-sync [:params/cancel-all])
    (is (= [{:description "original set"}
            {:description "second set"}]
           (db/edit-params @app-db)))))

(deftest description-changed
  (testing "updates set description"
    (is (some? (reset! app-db (db/set-edit-params {} [{:description "original desc"}]))))
    (rf/dispatch-sync [:params/description-changed 0 "updated desc"])
    (is (= [{:description "updated desc"}]
           (db/edit-params @app-db)))))

(deftest label-changed
  (testing "updates param label"
    (is (some? (reset! app-db (db/set-edit-params {}
                                                  [{:parameters
                                                    [{:name "original label"
                                                      :value "original value"}]}]))))
    (rf/dispatch-sync [:params/label-changed 0 0 "updated label"])
    (is (= [{:parameters
             [{:name "updated label"
               :value "original value"}]}]
           (db/edit-params @app-db)))))

(deftest value-changed
  (testing "updates param value"
    (is (some? (reset! app-db (db/set-edit-params {}
                                                  [{:parameters
                                                    [{:name "original label"
                                                      :value "original value"}]}]))))
    (rf/dispatch-sync [:params/value-changed 0 0 "updated value"])
    (is (= [{:parameters
             [{:name "original label"
               :value "updated value"}]}]
           (db/edit-params @app-db)))))
