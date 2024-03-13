(ns monkey.ci.gui.test.repo.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.repo.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(defn- set-repo-path! [cust repo]
  (reset! app-db {:route/current
                  {:parameters
                   {:path 
                    {:customer-id cust
                     :repo-id repo}}}}))

(defn- test-repo-path!
  "Generate random ids for customer, repo and build, and sets the current
   route to the generate path.  Returns the generated ids."
  []
  (let [[cust repo _ :as r] (repeatedly 3 random-uuid)]
    (set-repo-path! cust repo)
    r))

(deftest repo-load
  (testing "loads customer if not existing"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-customer-id"])
       (is (= 1 (count @c)))
       (is (= :get-customer (-> @c first (nth 2)))))))

  (testing "does not load customer if already loaded"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (cdb/set-customer {} {:name "existing customer"}))
       (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-customer-id"])
       (is (empty? @c)))))

  (testing "clears builds"
    (is (some? (reset! app-db (db/set-builds {} ::test-builds))))
    (rf/dispatch-sync [:repo/load "other-customer-id"])
    (is (nil? (db/builds @app-db)))))

(deftest builds-load
  (testing "sets alert"
    (rf/dispatch-sync [:builds/load])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (set-repo-path! "test-cust" "test-repo")
       (h/initialize-martian {:get-builds {:body [{:id "test-build"}]
                                           :error-code :no-error}})
       (rf/dispatch [:builds/load])
       (is (= 1 (count @c)))
       (is (= :get-builds (-> @c first (nth 2)))))))

  (testing "clears current builds"
    (is (map? (reset! app-db (db/set-builds {} [{:id "initial-build"}]))))
    (rf/dispatch-sync [:builds/load])
    (is (nil? (db/builds @app-db)))))

(deftest build-load-success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info
                                                 :message "test notification"}]))))
    (rf/dispatch-sync [:builds/load--success {:body []}])
    (is (nil? (db/alerts @app-db)))))

(deftest handle-event
  (testing "ignores events for other repos"
    (let [[cust] (test-repo-path!)]
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/start
                                                       :build {:customer-id cust
                                                               :repo-id "other-repo"
                                                               :build-id "other-build"
                                                               :git {:ref "main"}}}])))
      (is (empty? (db/builds @app-db)))))

  (testing "updates build list when build is started"
    (let [[cust repo build] (test-repo-path!)]
      (is (empty? (db/builds @app-db)))
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/start
                                                       :build {:customer-id cust
                                                               :repo-id repo
                                                               :build-id build
                                                               :git {:ref "main"}}}])))
      (is (= [{:customer-id cust
               :repo-id repo
               :build-id build
               :git {:ref "main"}}]
             (db/builds @app-db)))))

  (testing "updates build list when build has completed"
    (let [[cust repo build] (test-repo-path!)
          upd {:customer-id cust
               :repo-id repo
               :build-id build
               :git {:ref "main"}
               :status :success}]
      (is (some? (swap! app-db db/set-builds [{:customer-id cust
                                               :repo-id repo
                                               :build-id build}])))
      (is (nil? (rf/dispatch-sync [:repo/handle-event {:type :build/end
                                                       :build upd}])))
      (is (= [upd]
             (db/builds @app-db))))))
