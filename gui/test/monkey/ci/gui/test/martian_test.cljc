(ns monkey.ci.gui.test.martian-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.martian :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :once f/restore-rf)

(deftest init
  (testing "initializes default martian in db"
    (sut/init)
    (let [m (get-in @app-db [:martian.re-frame/martian :martian.re-frame/default-id :m])]
      (is (some? m))
      (is (not-empty (:handlers m)))
      (is (= (set (map :route-name (:handlers m)))
             (set (map :route-name sut/routes)))))))

(deftest secure-request
  (testing "dispatches martian request"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (rf/dispatch [:secure-request :get-customer {:key "value"} [::on-success] [::on-failure]])
       (is (= 1 (count @e)))
       (is (= :get-customer (-> @e first (nth 2)))))))

  (testing "adds auth token as authorization header"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (is (map? (swap! app-db assoc :auth/token "test-token")))
       (rf/dispatch [:secure-request :get-customer {} [::on-success] [::on-failure]])
       (is (= 1 (count @e)))
       (is (= {:authorization "Bearer test-token"}
              (-> @e first (nth 3)))))))

  (testing "wraps error handler"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (rf/dispatch [:secure-request :test-request {} [::on-success] [::on-failure]])
       (is (= [::sut/error-handler
               [:martian.re-frame/request :test-request {} [::on-success] [::on-failure]]
               [::on-failure]]
              (-> @e first last)))))))

(deftest error-handler
  (testing "dispatches target event when no 401 error"
    (rf-test/run-test-sync
     (rf/reg-event-db ::on-failure #(assoc % ::err-invoked? true))
     (rf/dispatch [::sut/error-handler [::orig-evt] [::on-failure] {:status 500}])
     (is (true? (::err-invoked? @app-db)))))

  (testing "on 401 error and refresh token is provided"
    (testing "refreshes github token"
      (rf-test/run-test-sync
       (rf/reg-cofx :local-storage (fn [cofx id]
                                     (assoc cofx :local-storage {:refresh-token "test-refresh-token"})))
    
       (let [c (h/catch-fx :martian.re-frame/request)]
         (is (some? (reset! app-db (ldb/set-github-token {} "test-github-token"))))
         (h/initialize-martian {:github-refresh {:status 200
                                                 :body {:token "new-token"}
                                                 :error-code :no-error}})
         (rf/dispatch [::sut/error-handler [::orig-req] [::on-failure] {:status 401}])
         (is (= 1 (count @c)))
         (is (= :github-refresh (-> @c first (nth 2)))))))

    (testing "refreshes bitbucket token")))

(deftest refresh-token--success
  (rf-test/run-test-sync
   (let [e (h/catch-fx :local-storage)]
     (is (some? (reset! app-db (-> {}
                                   (ldb/set-token "old-token")
                                   (ldb/set-github-token "test-github-token")))))
     (rf/reg-event-db ::martian-request #(assoc % ::orig-invoked? true))
     (rf/reg-cofx :local-storage (fn [cofx id]
                                   (assoc cofx :local-storage {:user-id "test-id"
                                                               :token "old-token"})))
     (rf/dispatch [::sut/refresh-token--success
                   [::martian-request ::orig-req {} [::on-success] [::on-failure]]
                   {:body
                    {:token "new-token"
                     :github-token "new-github-token"}}])
     
     (testing "stores received tokens"
       (is (= "new-token" (ldb/token @app-db))))
     
     (testing "re-invokes original request"
       (is (true? (::orig-invoked? @app-db))))

     (testing "stores new provider and refresh tokens in local storage"
       (is (= 1 (count @e)))
       (is (= ldb/storage-token-id (ffirst @e)))
       (let [v (-> @e first second)]
         (is (= "new-token" (:token v)))
         (is (= "new-github-token" (:github-token v)))
         (is (= "test-id" (:user-id v)) "Keep original user fields"))))))

(deftest refresh-token--failed
  (testing "redirects to login page on 401"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/refresh-token--failed [::on-failure] {:status 401}])
       (is (= ["/login"] @e)))))

  (testing "redirects to login page on other error"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :route/goto)]
       (rf/dispatch [::sut/refresh-token--failed [::on-failure] {:status 500}])
       (is (= ["/login"] @e))))))

(deftest api-url
  (testing "constructs url using api url"
    (is (= "http://test:3000/test-path"
           (sut/api-url "/test-path")))))
