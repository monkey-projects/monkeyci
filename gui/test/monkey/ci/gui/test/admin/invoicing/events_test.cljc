(ns monkey.ci.gui.test.admin.invoicing.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]            
            [monkey.ci.gui.admin.invoicing.db :as db]
            [monkey.ci.gui.admin.invoicing.events :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest load
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:get-org-invoices
                            {:status 200
                             :body []
                             :error-code :no-error}})
     (rf/dispatch [::sut/load "test-org"])

     (testing "retrieves org invoices from backend"
       (is (= 1 (count @c))))

     (testing "passes org id"
       (is (= "test-org" (-> @c first (nth 3) :org-id))))

     (testing "marks loading"
       (is (db/loading? @app-db)))

     (testing "clears alerts"
       (is (empty? (db/get-alerts @app-db)))))))

(deftest load--success
  (let [inv [{:invoice-nr "1"}]]
    (is (some? (reset! app-db (lo/set-loading {} db/id))))
    (rf/dispatch-sync [::sut/load--success {:body inv}])
    
    (testing "sets invoices in db"
      (is (= inv (db/get-invoices @app-db))))

    (testing "unmarks loading"
      (is (not (db/loading? @app-db))))))

(deftest load--failure
  (is (some? (reset! app-db (lo/set-loading {} db/id))))
  (rf/dispatch-sync [::sut/load--failure {:message "test error"}])

  (testing "sets error alert"
    (is (= [:danger] (map :type (db/get-alerts @app-db)))))

  (testing "unmarks loading"
    (is (not (db/loading? @app-db)))))
