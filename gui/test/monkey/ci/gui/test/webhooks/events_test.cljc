(ns monkey.ci.gui.test.webhooks.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.webhooks.db :as db]
            [monkey.ci.gui.webhooks.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)
(use-fixtures :each f/restore-rf f/reset-db)

(deftest webhooks-init
  (testing "does nothing if initialized"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (lo/set-initialized {} db/id))
       (h/initialize-martian {:get-repo-webhooks {:error-code :unexpected}})

       (rf/dispatch [:webhooks/init])
       (is (empty? @c)))))

  (testing "when not initialized"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-repo-webhooks {:body {}
                                                  :error-code :no-error}})
       (rf/dispatch [:webhooks/init])
       
       (testing "loads webhooks"
         (is (= 1 (count @c))))

       (testing "sets initialized"
         (is (lo/initialized? @app-db db/id)))))))

(deftest webhooks-load
  (testing "invokes `get-repo-webhooks` endpoint"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/set-repo-path! "test-org" "test-repo")
       (h/initialize-martian {:get-repo-webhooks {:body [{:id "test-wh"}]
                                                  :error-code :no-error}})
       (rf/dispatch [:webhooks/load])
       (is (= 1 (count @c)))
       (is (= :get-repo-webhooks
              (-> @c first (nth 2))))))))

(deftest webhooks-load--success
  (testing "sets webhooks in db"
    (let [wh [{:id ::test-wh}]]
      (rf/dispatch-sync [:webhooks/load--success {:body wh}])
      (is (= wh (db/get-webhooks @app-db))))))

(deftest webhooks-load--failed
  (testing "sets error message"
    (rf/dispatch-sync [:webhooks/load--failed {:error-message "test error"}])
    (is (= [:danger]
           (->> (db/get-alerts @app-db)
                (map :type))))))

(deftest webhooks-new
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/set-repo-path! "test-org" "test-repo")
     (h/initialize-martian {:create-webhook {:body {:id "test-wh"}
                                             :error-code :no-error}})
     (rf/dispatch [:webhooks/new])

     (testing "invokes `create-webhook` endpoint for repo"
       (is (= 1 (count @c)))
       (is (= :create-webhook
              (-> @c first (nth 2)))))

     (testing "passes org and repo id in body"
       (is (= {:org-id "test-org"
               :repo-id "test-repo"}
              (-> @c first (nth 3) :webhook))))

     (testing "marks creating"
       (is (true? (db/creating? @app-db)))))))

(deftest webhooks-new--success
  (is (nil? (db/get-new @app-db)))
  (is (some? (reset! app-db (db/set-creating {}))))
  (rf/dispatch-sync [:webhooks/new--success {:body ::created}])
  
  (testing "sets new webhook in db"
    (is (= ::created (db/get-new @app-db))))

  (testing "adds to list of webhooks"
    (is (= [::created] (db/get-webhooks @app-db))))

  (testing "unmarks creating"
    (is (not (db/creating? @app-db)))))

(deftest webhooks-new--failed
  (is (some? (reset! app-db (db/set-creating {}))))
  (rf/dispatch-sync [:webhooks/new--failed {:error-message "test error"}])
  
  (testing "sets error message"
    (is (= [:danger]
           (->> (db/get-alerts @app-db)
                (map :type)))))
  
  (testing "unmarks creating"
    (is (not (db/creating? @app-db)))))

(deftest webhooks-close-new
  (testing "clears new webhook from db"
    (is (some? (reset! app-db (db/set-new {} ::new))))
    (rf/dispatch-sync [:webhooks/close-new])
    (is (nil? (db/get-new @app-db)))))

(deftest webhooks-delete-confirm
  (testing "sets current delete id in db"
    (rf/dispatch-sync [:webhooks/delete-confirm "test-wh"])
    (is (= "test-wh" (db/get-delete-curr @app-db)))))

(deftest webhooks-delete
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (-> {}
                                   (h/set-repo-path "test-org" "test-repo")
                                   (db/set-delete-curr "test-wh")))))
     (h/initialize-martian {:delete-webhook {:body {}
                                             :error-code :no-error}})
     (rf/dispatch [:webhooks/delete])

     (testing "invokes `delete-webhook` endpoint"
       (is (= 1 (count @c)))
       (is (= :delete-webhook
              (-> @c first (nth 2)))))

     (testing "passes webhook id in path"
       (is (= "test-wh"
              (-> @c first (nth 3) :webhook-id))))

     (testing "marks deleting"
       (is (true? (db/deleting? @app-db "test-wh")))))))

(deftest webhooks-delete--success
  (let [id (random-uuid)]
    (is (some? (reset! app-db (-> {}
                                  (db/set-deleting id)
                                  (db/set-webhooks [{:id ::other}
                                                    {:id id}])))))
    (rf/dispatch-sync [:webhooks/delete--success id])
    
    (testing "removes from list of webhooks"
      (is (= [{:id ::other}] (db/get-webhooks @app-db))))

    (testing "sets alert message"
      (is (= [:success]
             (->> (db/get-alerts @app-db)
                  (map :type)))))
    
    (testing "unmarks deleting"
      (is (not (db/deleting? @app-db id))))))

(deftest webhooks-delete--failed
  (let [id (random-uuid)]
    (is (some? (reset! app-db (db/set-deleting {} id))))
    (rf/dispatch-sync [:webhooks/delete--failed id {:error-message "test error"}])
    
    (testing "sets error message"
      (is (= [:danger]
             (->> (db/get-alerts @app-db)
                  (map :type)))))
    
    (testing "unmarks deleting"
      (is (not (db/deleting? @app-db id))))))
