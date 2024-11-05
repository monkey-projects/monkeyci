(ns monkey.ci.gui.test.apis.github-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.apis.github :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest customer-load-github-repos
  (testing "invokes repos and orgs url from github user"
    (let [e (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db (ldb/set-github-user {} {:organizations-url "http://test-orgs"}))))
       (rf/dispatch [:github/load-repos])
       (is (= 2 (count @e)))
       (is (= #{"https://api.github.com/user/repos" "http://test-orgs"}
              (set (map :uri @e)))))))

  (testing "sets info alert"
    (let [_ (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db (ldb/set-github-user {} {:repos-url "http://test-repos"}))))
       (rf/dispatch [:github/load-repos])
       (let [a (sut/alerts @app-db)]
         (is (= 1 (count a)))
         (is (= :info (-> a first :type))))))))

(deftest customer-load-github-repos--success
  (testing "sets repos in db"
    (rf/dispatch-sync [:github/load-repos--success [{:id "test-repo"}]])
    (is (= 1 (count (sut/repos @app-db)))))

  (testing "sets success alert"
    (rf/dispatch-sync [:github/load-repos--success [{:id "test-repo"}]])
      (let [a (sut/alerts @app-db)]
        (is (= 1 (count a)))
        (is (= :success (-> a first :type))))))

(deftest customer-load-github-repos--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:github/load-repos--failed {:message "test error"}])
      (let [a (sut/alerts @app-db)]
        (is (= 1 (count a)))
        (is (= :danger (-> a first :type))))))

(deftest load-orgs
  (testing "invokes orgs url from github user"
    (let [e (h/catch-fx :http-xhrio)]
      (is (some? (reset! app-db (ldb/set-github-user {} {:organizations-url "http://test-orgs"}))))
      (rf/dispatch-sync [::sut/load-orgs])
      (is (= 1 (count @e)))
      (is (= "http://test-orgs" (-> @e first :uri))))))

(deftest load-orgs--success
  (testing "fetches repos for each org"
    (let [e (h/catch-fx :http-xhrio)]
      (rf-test/run-test-sync
       (rf/dispatch [::sut/load-orgs--success [{:repos-url "http://test-repos/org-1"}
                                               {:repos-url "http://test-repos/org-2"}]])
       (is (= 2 (count @e)))
       (is (= ["http://test-repos/org-1"
               "http://test-repos/org-2"]
              (map :uri @e)))))))

(deftest load-orgs--failed
  (testing "redirect to login on 401 error"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/load-orgs--failed {:status 401}])
       (is (= ["/login"] @e))))))

(deftest alerts
  (let [s (rf/subscribe [:github/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (sut/set-alerts {} a))))
        (is (= a @s))))))
