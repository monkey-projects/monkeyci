(ns monkey.ci.gui.test.login.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.login.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest submitting?
  (let [s (rf/subscribe [:login/submitting?])]
    (testing "exists"
      (is (some? s)))

    (testing "returns state from db"
      (is (false? @s))
      (is (map? (reset! app-db (db/set-submitting {}))))
      (is (true? @s)))))

(deftest user
  (let [s (rf/subscribe [:login/user])]
    (testing "exists"
      (is (some? s)))

    (testing "returns user from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-user {} {:name "test-user"}))))
      (is (= "test-user" (:name @s))))

    (testing "adds github user details"
      (is (some? (reset! app-db (-> {}
                                    (db/set-user {:id "test-user"})
                                    (db/set-github-user {:name "github user"})))))
      (is (= "github user" (-> @s :github :name))))

    (testing "adds avatar url and name to user from github"
      (is (some? (reset! app-db (-> {}
                                    (db/set-user {:id "test-user"})
                                    (db/set-github-user {:name "github user"
                                                         :avatar-url "http://test-avatar"})))))
      (is (= {:name "github user"
              :avatar-url "http://test-avatar"}
             (select-keys @s [:name :avatar-url]))))))

(deftest alerts
  (let [s (rf/subscribe [:login/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-alerts {} "test-alerts"))))
      (is (= "test-alerts" @s)))))

(deftest token
  (let [s (rf/subscribe [:login/token])]
    (testing "exists"
      (is (some? s)))

    (testing "returns token from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-token {} "test-token"))))
      (is (= "test-token" @s)))))

(deftest github-client-id
  (let [s (rf/subscribe [:login/github-client-id])]
    (testing "exists"
      (is (some? s)))

    (testing "returns client-id from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-github-config {} {:client-id "test-github-client-id"}))))
      (is (= "test-github-client-id" @s)))))

(deftest bitbucket-client-id
  (let [s (rf/subscribe [:login/bitbucket-client-id])]
    (testing "exists"
      (is (some? s)))

    (testing "returns client-id from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-bitbucket-config {} {:client-id "test-bitbucket-client-id"}))))
      (is (= "test-bitbucket-client-id" @s)))))
