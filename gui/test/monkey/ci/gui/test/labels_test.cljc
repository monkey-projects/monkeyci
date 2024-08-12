(ns monkey.ci.gui.test.labels-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.labels :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest labels-add-disjunction
  (let [id (random-uuid)]
    (testing "adds disjunction with single label to empty db"
      (rf/dispatch-sync [:labels/add-disjunction id])
      (is (= [[{:label "" :value ""}]]
             (sut/get-labels @app-db id))))

    (testing "adds disjunction with single label to empty db"
      (is (some? (reset! app-db (sut/set-labels {} id [[{:name "first" :value "first value"}]]))))
      (rf/dispatch-sync [:labels/add-disjunction id])
      (is (= [[{:name "first" :value "first value"}]
              [{:label "" :value ""}]]
             (sut/get-labels @app-db id))))))

(deftest label-changed
  (let [id (random-uuid)]
    (testing "sets label value at specified indices"
      (is (some? (reset! app-db (sut/set-labels {} id
                                                [[{:label "0-0" :value "first value"}
                                                  {:label "0-1" :value "second value"}]
                                                 [{:label "1-0" :value "third value"}]]))))
      (rf/dispatch-sync [:labels/label-changed id 0 1 "updated value"])
      (is (= [[{:label "0-0" :value "first value"}
               {:label "updated value" :value "second value"}]
              [{:label "1-0" :value "third value"}]]
             (sut/get-labels @app-db id))))))

(deftest value-changed
  (let [id (random-uuid)]
    (testing "sets value value at specified indices"
      (is (some? (reset! app-db (sut/set-labels {} id
                                                [[{:label "0-0" :value "first value"}
                                                  {:label "0-1" :value "second value"}]
                                                 [{:label "1-0" :value "third value"}]]))))
      (rf/dispatch-sync [:labels/value-changed id 0 1 "updated value"])
      (is (= [[{:label "0-0" :value "first value"}
               {:label "0-1" :value "updated value"}]
              [{:label "1-0" :value "third value"}]]
             (sut/get-labels @app-db id))))))

(deftest labels-add-conjunction
  (let [id (random-uuid)]
    (testing "adds empty row to disjunction at index"
      (is (some? (reset! app-db (sut/set-labels {} id
                                                [[{:label "0-0" :value "first value"}
                                                  {:label "0-1" :value "second value"}]
                                                 [{:label "1-0" :value "third value"}]]))))
      (rf/dispatch-sync [:labels/add-conjunction id 0 1])
      (is (= [[{:label "0-0" :value "first value"}
               {:label "" :value ""}
               {:label "0-1" :value "second value"}]
              [{:label "1-0" :value "third value"}]]
             (sut/get-labels @app-db id))))))

(deftest labels-remove-conjunction
  (let [id (random-uuid)]
    (is (some? (reset! app-db (sut/set-labels {} id
                                              [[{:label "0-0" :value "first value"}
                                                {:label "0-1" :value "second value"}]
                                               [{:label "1-0" :value "third value"}]]))))
    
    (testing "removes row from disjunction at index"
      (rf/dispatch-sync [:labels/remove-conjunction id 0 1])
      (is (= [[{:label "0-0" :value "first value"}]
              [{:label "1-0" :value "third value"}]]
             (sut/get-labels @app-db id))))

    (testing "removes disjunction when last conjunction removed"
      (rf/dispatch-sync [:labels/remove-conjunction id 1 0])
      (is (= [[{:label "0-0" :value "first value"}]]
             (sut/get-labels @app-db id))))))

(deftest labels-edit-sub
  (let [id (random-uuid)
        e (rf/subscribe [:labels/edit id])]
    (testing "exists"
      (is (some? e)))

    (testing "contains labels for id"
      (is (empty? @e))
      (is (some? (reset! app-db (sut/set-labels {} id ::test-labels))))
      (is (= ::test-labels @e)))))
