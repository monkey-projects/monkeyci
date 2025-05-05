(ns monkey.ci.gui.test.admin.search-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.search :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest org-search
  (testing "searches for org by name and id"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:search-orgs {:status 200
                                                 :body "ok"
                                                 :error-code :no-error}})
       (rf/dispatch [:admin/org-search {:org-filter ["test-org"]}])
       (is (= 2 (count @c))))))

  (testing "clears orgs"
    (is (some? (reset! app-db (lo/set-value {} sut/org-by-name [::initial-orgs]))))
    (rf/dispatch-sync [:admin/org-search {}])
    (is (empty? (sut/get-orgs @app-db)))))

(deftest org-search--success
  (testing "when by name, adds orgs to db"
    (is (some? (reset! app-db (lo/set-value {} sut/org-by-id [::initial]))))
    (rf/dispatch-sync [:admin/org-search--success sut/org-by-name {:body [::new]}])
    (is (= #{::initial ::new} (set (sut/get-orgs @app-db)))))

  (testing "when by id, adds orgs to db"
    (is (some? (reset! app-db (lo/set-value {} sut/org-by-name [::initial]))))
    (rf/dispatch-sync [:admin/org-search--success sut/org-by-id {:body [::new]}])
    (is (= #{::initial ::new} (set (sut/get-orgs @app-db))))))

(deftest org-search--failed
  (testing "sets alert for id"
    (rf/dispatch-sync [:admin/org-search--failed sut/org-by-name "test error"])
    (is (= 1 (count (lo/get-alerts @app-db sut/org-by-name))))))

(deftest orgs-loading?
  (let [l (rf/subscribe [:admin/orgs-loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "initially false"
      (is (false? @l)))
    
    (testing "true if loading by id"
      (is (some? (reset! app-db (lo/set-loading {} sut/org-by-id))))
      (is (true? @l)))

    (testing "true if loading by name"
      (is (some? (reset! app-db (lo/set-loading {} sut/org-by-name))))
      (is (true? @l)))))

(deftest orgs
  (let [c (rf/subscribe [:admin/orgs])]
    (testing "exists"
      (is (some? c)))

    (testing "initially empty"
      (is (empty? @c)))
    
    (testing "contains orgs by id"
      (is (some? (reset! app-db (lo/set-value {} sut/org-by-id [::org]))))
      (is (= [::org])))

    (testing "contains orgs by name"
      (is (some? (reset! app-db (lo/set-value {} sut/org-by-name [::org]))))
      (is (= [::org])))))

(deftest orgs-loaded?
  (h/verify-sub
   [:admin/orgs-loaded?]
   #(lo/set-loaded % sut/org-by-id)
   true
   false))
