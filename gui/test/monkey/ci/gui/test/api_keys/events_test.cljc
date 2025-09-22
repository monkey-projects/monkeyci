(ns monkey.ci.gui.test.api-keys.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.api-keys.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest tokens-load
  (testing "loads tokens from backend using configured request"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org-tokens
                              {:body {:description "test token"}
                               :error-code :no-error}})
       
       (rf/dispatch [:tokens/load db/org-id {:org-id "test-org"}])
       (is (= 1 (count @c)))
       (is (= :get-org-tokens (-> @c first (nth 2))))))))

(deftest tokens-new
  (testing "sets token edit object"
    (let [id "test-id"]
      (is (nil? (db/get-token-edit @app-db id)))
      (rf/dispatch-sync [:tokens/new id])
      (is (some? (db/get-token-edit @app-db id))))))

(deftest tokens-cancel-edit
  (testing "clears token edit object"
    (let [id "test-id"]
      (is (some? (db/set-token-edit @app-db id {:description "test"})))
      (rf/dispatch-sync [:tokens/cancel-edit id])
      (is (nil? (db/get-token-edit @app-db id))))))

(deftest tokens-edit-changed
  (testing "updates property in edit obj"
    (let [id "test-id"]
      (rf/dispatch-sync [:tokens/edit-changed id :description "updated desc"])
      (is (= "updated desc" (-> (db/get-token-edit @app-db id)
                                :description))))))

(deftest tokens-save
  (rft/run-test-sync
   (let [id db/org-id
         c (h/catch-fx :martian.re-frame/request)]
     (is (some? (reset! app-db (-> {}
                                   (db/set-token-edit id {:description "test desc"})
                                   (r/set-current {:parameters
                                                   {:path
                                                    {:org-id "test-org"}}})))))
     (h/initialize-martian {:create-org-token
                            {:body {:description "test token"}
                             :error-code :no-error}})
     
     (rf/dispatch [:tokens/save id])

     (testing "saves token to backend using configured request"
       (is (= 1 (count @c)))
       (is (= :create-org-token (-> @c first (nth 2)))))

     (testing "passes path params and form contents"
       (is (= {:org-id "test-org"
               :token {:description "test desc"}}
              (-> @c first (nth 3))))))))

(deftest tokens-save--success
  (let [id "test-id"
        token {:secret "test-secret"}]
    (testing "sets new token in db"
      (rf/dispatch-sync [:tokens/save--success id {:body token}])
      (is (= token (db/get-new-token @app-db id))))))

(deftest tokens-save--failed
  (let [id "test-id"]
    (testing "sets error in db"
      (rf/dispatch-sync [:tokens/save--failed id "test-error"])
      (is (= [:danger]
             (->> (db/get-alerts @app-db id)
                  (map :type)))))))
