(ns monkey.ci.gui.test.admin.credits.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.credits.events :as sut]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest credits-load-issues
  (testing "fetches customer credit issues from backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-credit-issues
                              {:status 200
                               :body []
                               :error-code :no-error}})
       (rf/dispatch [:credits/load-issues {:customer-id "test-cust"}])
       (is (= 1 (count @c)))))))

(deftest credits-load-issues--success
  (testing "stores credits in db"
    (rf/dispatch-sync [:credits/load-issues--success {:body [::test-credits]}])
    (is (= [::test-credits] (db/get-issues @app-db)))))

(deftest credits-load-issues--failed
  (testing "sets alert"
    (rf/dispatch-sync [:credits/load-issues--failed "test error"])
    (is (= 1 (count (db/get-issue-alerts @app-db))))))

(deftest credits-new-issue
  (testing "displays form"
    (rf/dispatch-sync [:credits/new-issue])
    (is (true? (db/show-issue-form? @app-db)))))

(deftest credits-cancel-issue
  (testing "hides credits form"
    (is (some? (reset! app-db (db/show-issue-form {}))))
    (rf/dispatch-sync [:credits/cancel-issue])
    (is (not (db/show-issue-form? @app-db)))))

(deftest credits-save-issue
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (r/set-current {} {:parameters
                                                  {:path
                                                   {:customer-id "test-cust"}}}))))
     (h/initialize-martian {:create-credit-issue
                            {:status 200
                             :body {}
                             :error-code :no-error}})
     (rf/dispatch [:credits/save-issue
                   {:amount [1000]
                    :reason ["test reason"]
                    :from-time ["2025-01-01"]}])
     (testing "saves to backend"
       (is (= 1 (count @c)))
       (is (= :create-credit-issue (-> @c first (nth 2)))))

     (testing "passes form params as body"
       (let [params (-> @c first (nth 3) :credits)]
         (is (= {:amount 1000
                 :reason "test reason"}
                (select-keys params [:amount :reason])))
         (is (number? (:from-time params)))))

     (testing "adds customer id"
       (is (= "test-cust"
              (-> @c first (nth 3) :customer-id))))

     (testing "marks saving"
       (is (db/issue-saving? @app-db))))))

(deftest credits-save-issue--success
  (let [cred {:id "test-cred"
              :amount 1000}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-issue-saving)
                                  (db/show-issue-form)))))
    (rf/dispatch-sync [:credits/save-issue--success {:body cred}])
    
    (testing "adds credits to db"
      (is (= [cred] (db/get-issues @app-db))))

    (testing "unmarks saving"
      (is (not (db/issue-saving? @app-db))))

    (testing "sets success alert"
      (is (= [:success]
             (->> (db/get-issue-alerts @app-db)
                  (map :type)))))

    (testing "hides form"
      (is (not (db/show-issue-form? @app-db))))))

(deftest credits-save-issue--failed
  (is (some? (reset! app-db (db/set-issue-saving {}))))
  
  (testing "sets alert"
    (rf/dispatch-sync [:credits/save-issue--failed "test error"])
    (is (= 1 (count (db/get-issue-alerts @app-db)))))

  (testing "unmarks saving"
    (is (not (db/issue-saving? @app-db)))))

(deftest credits-load-subs
  (testing "fetches customer credit subs from backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-credit-subs
                              {:status 200
                               :body []
                               :error-code :no-error}})
       (rf/dispatch [:credits/load-subs {:customer-id "test-cust"}])
       (is (= 1 (count @c)))))))

(deftest credits-load-subs--success
  (testing "stores credits in db"
    (rf/dispatch-sync [:credits/load-subs--success {:body [::test-credits]}])
    (is (= [::test-credits] (db/get-subs @app-db)))))

(deftest credits-load-subs--failed
  (testing "sets alert"
    (rf/dispatch-sync [:credits/load-subs--failed "test error"])
    (is (= 1 (count (db/get-sub-alerts @app-db))))))

(deftest credits-new-sub
  (testing "displays form"
    (rf/dispatch-sync [:credits/new-sub])
    (is (true? (db/show-sub-form? @app-db)))))

(deftest credits-cancel-sub
  (testing "hides credits form"
    (is (some? (reset! app-db (db/show-sub-form {}))))
    (rf/dispatch-sync [:credits/cancel-sub])
    (is (not (db/show-sub-form? @app-db)))))

(deftest credits-save-sub
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (r/set-current {} {:parameters
                                                  {:path
                                                   {:customer-id "test-cust"}}}))))
     (h/initialize-martian {:create-credit-sub
                            {:status 200
                             :body {}
                             :error-code :no-error}})
     (rf/dispatch [:credits/save-sub
                   {:amount [1000]
                    :valid-from ["2025-01-01"]
                    :valid-until [""]}])
     (testing "saves to backend"
       (is (= 1 (count @c)))
       (is (= :create-credit-sub (-> @c first (nth 2)))))

     (let [params (-> @c first (nth 3) :sub)]
       (testing "passes form params as body"
         (is (= 1000
                (:amount params)))
         (is (number? (:valid-from params))))

       (testing "does not pass empty `valid-until` date"
         (is (not (contains? params :valid-until)))))

     (testing "adds customer id"
       (is (= "test-cust"
              (-> @c first (nth 3) :customer-id))))

     (testing "marks saving"
       (is (db/sub-saving? @app-db))))))

(deftest credits-save-sub--success
  (let [cred {:id "test-cred"
              :amount 1000}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-sub-saving)
                                  (db/show-sub-form)))))
    (rf/dispatch-sync [:credits/save-sub--success {:body cred}])
    
    (testing "adds subscription to db"
      (is (= [cred] (db/get-subs @app-db))))

    (testing "unmarks saving"
      (is (not (db/sub-saving? @app-db))))

    (testing "sets success alert"
      (is (= [:success]
             (->> (db/get-sub-alerts @app-db)
                  (map :type)))))

    (testing "hides form"
      (is (not (db/show-sub-form? @app-db))))))

(deftest credits-save-sub--failed
  (is (some? (reset! app-db (db/set-sub-saving {}))))
  
  (testing "sets alert"
    (rf/dispatch-sync [:credits/save-sub--failed "test error"])
    (is (= 1 (count (db/get-sub-alerts @app-db)))))

  (testing "unmarks saving"
    (is (not (db/sub-saving? @app-db)))))
