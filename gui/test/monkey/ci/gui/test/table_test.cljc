(ns monkey.ci.gui.test.table-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.table :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest pagination-info-sub
  (let [s (rf/subscribe [:pagination/info ::test-id])]

    (testing "exists"
      (is (some? s)))

    (testing "returns pagination info from db for given id"
      (let [p {:count 5
               :current 2}]
        (is (nil? @s))
        (reset! app-db (sut/set-pagination {} ::test-id p))
        (is (= p @s))))))

(deftest pagination-set-evt
  (testing "sets pagination info in db"
    (let [id ::test-id
          p {:count 5 :current 0}]
      (rf/dispatch-sync [:pagination/set id p])
      (is (= p (sut/get-pagination @app-db id))))))

(deftest pagination-next-evt
  (testing "increases current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 5}])
      (rf/dispatch-sync [:pagination/next id])
      (is (= 2 (:current (sut/get-pagination @app-db id))))))

  (testing "does not increase past page count"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:count 2
                                             :current 1}])
      (rf/dispatch-sync [:pagination/next id])
      (is (= 1 (:current (sut/get-pagination @app-db id)))))))

(deftest pagination-prev-evt
  (testing "decreases current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 3}])
      (rf/dispatch-sync [:pagination/prev id])
      (is (= 0 (:current (sut/get-pagination @app-db id))))))

  (testing "does not decrease below zero"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 0 :count 3}])
      (rf/dispatch-sync [:pagination/prev id])
      (is (= 0 (:current (sut/get-pagination @app-db id)))))))

(deftest pagination-goto-evt
  (testing "sets current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 10}])
      (rf/dispatch-sync [:pagination/goto id 4])
      (is (= 4 (:current (sut/get-pagination @app-db id))))))

  (testing "does not set invalid page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 3}])
      (rf/dispatch-sync [:pagination/goto id 4])
      (is (= 1 (:current (sut/get-pagination @app-db id)))))))

(deftest table-sorting-sub
  (let [id ::test-table
        ts (rf/subscribe [:table/sorting id])]
    (testing "exists"
      (is (some? ts)))
    
    (testing "initially empty"
      (is (empty? @ts)))

    (testing "returns current table sorting for id"
      (let [sorting {:col-idx 0
                     :sorting :asc}]
        (is (some? (reset! app-db (sut/set-sorting {} id sorting))))
        (is (= sorting @ts))))))

(deftest sorting-toggled-evt
  (let [id ::test-table]
    (testing "sets sorting to ascending on given column index"
      (rf/dispatch-sync [:table/sorting-toggled id 1])
      (is (= {:col-idx 1 :sorting :asc}
             (sut/get-sorting @app-db id))))

    (testing "changes sorting to descending on same column index"
      (rf/dispatch-sync [:table/sorting-toggled id 1])
      (is (= {:col-idx 1 :sorting :desc}
             (sut/get-sorting @app-db id))))

    (testing "sets sorting to ascending on other column index"
      (rf/dispatch-sync [:table/sorting-toggled id 0])
      (is (= {:col-idx 0 :sorting :asc}
             (sut/get-sorting @app-db id))))))

(deftest sorter-fn
  (let [items [3 1 2]
        s (sut/sorter-fn sort)]
    
    (testing "invokes sorter on `:asc`"
      (is (= [1 2 3] (sut/invoke-sorter s :asc items))))

    (testing "reverses items on `:desc`"
      (is (= [3 2 1] (sut/invoke-sorter s :desc items))))))

(deftest apply-sorting
  (let [cols [{:label "Id"
               :sorter (sut/prop-sorter :id)}
              {:label "Name"
               :sorter (sut/prop-sorter :name)}
              {:label "Date"}]
        [i1 i2 i3 :as items] [{:id 1
                               :name "C"}
                              {:id 3
                               :name "A"}
                              {:id 2
                               :name "B"}]]
    (testing "returns items as-is when no sorting"
      (is (= items
             (sut/apply-sorting {} cols items))))

    (testing "uses column sorter ascending"
      (is (= [i1 i3 i2]
             (sut/apply-sorting {:col-idx 0
                                 :sorting :asc}
                                cols items))))

    (testing "uses column sorter descending"
      (is (= [i1 i3 i2]
             (sut/apply-sorting {:col-idx 1
                                 :sorting :desc}
                                cols items))))

    (testing "returns items as-is when no sorter"
      (is (= items
             (sut/apply-sorting {:col-idx 2
                                 :sorting :desc}
                                cols items))))))
