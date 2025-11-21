(ns monkey.ci.gui.test.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.events]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db f/restore-rf)

(deftest initialize-db
  (testing "clears db"
    (rf/reg-cofx :local-storage (fn [cofx id] cofx))
    (is (some? (reset! app-db {::test-key ::test-value})))
    (rf/dispatch-sync [:initialize-db])
    (is (nil? (::test-key @app-db)))))

#_(deftest core-init-user
  (testing "loads tokens from local storage"
    (rf/reg-cofx :local-storage (fn [cofx id]
                                  (cond-> cofx
                                    (= ldb/storage-token-id id)
                                    (assoc :local-storage {:token "test-app-token"}))))
    (rf/dispatch-sync [:core/init-user])
    (is (= "test-app-token" (ldb/token @app-db))))

  (testing "loads github user details if token provided"
    (rf/reg-cofx :local-storage (fn [cofx id]
                                  (cond-> cofx
                                    (= ldb/storage-token-id id)
                                    (assoc :local-storage {:token "test-app-token"
                                                           :github-token "test-github-token"}))))
    (let [c (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [:core/init-user])
      (is (= 1 (count @c)))
      (is (= "https://api.github.com/user" (:uri (first @c))))
      (is (= :github (ldb/provider @app-db)))))

  (testing "loads bitbucket user details if token provided"
    (rf/reg-cofx :local-storage (fn [cofx id]
                                  (cond-> cofx
                                    (= ldb/storage-token-id id)
                                    (assoc :local-storage {:token "test-app-token"
                                                           :bitbucket-token "test-github-token"}))))
    (let [c (h/catch-fx :http-xhrio)]
      (rf/dispatch-sync [:core/init-user])
      (is (= 1 (count @c)))
      (is (= "https://api.bitbucket.org/2.0/user" (:uri (first @c))))
      (is (= :bitbucket (ldb/provider @app-db))))))

(deftest load-version
  (testing "sends request to backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-version {:status 200
                                            :body "test-version"
                                            :error-code :no-error}})
       (rf/dispatch [:core/load-version])
       (is (= 1 (count @c)))
       (is (= :get-version (-> @c first (nth 2))))))))

(deftest load-version--success
  (testing "sets version in db"
    (rf/dispatch-sync [:core/load-version--success {:body "test-version"}])
    (is (= "test-version" (:version @app-db)))))

(deftest load-version--failed
  (testing "clears version"
    (is (some? (reset! app-db {:version "previous-version"})))
    (rf/dispatch-sync [:core/load-version--failed "test error"])
    (is (nil? (:version @app-db)))))
