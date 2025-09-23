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
  (let [id "test-id"]
    (is (some? (reset! app-db (db/set-new-token {} id ::new-token))))
    (is (nil? (db/get-token-edit @app-db id)))
    (rf/dispatch-sync [:tokens/new id])

    (testing "sets token edit object"
      (is (some? (db/get-token-edit @app-db id))))

    (testing "clears new token"
      (is (nil? (db/get-new-token @app-db id))))))

(deftest tokens-cancel-edit
  (let [id "test-id"]
    (is (some? (reset! app-db (-> {}
                                  (db/set-token-edit id {:description "test"})
                                  (db/set-new-token id ::new-token)))))
    (rf/dispatch-sync [:tokens/cancel-edit id])
    
    (testing "clears token edit object"
      (is (nil? (db/get-token-edit @app-db id))))

    (testing "clears new token"
      (is (nil? (db/get-new-token @app-db id))))))

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
              (-> @c first (nth 3)))))

     (testing "marks saving"
       (is (true? (db/saving? @app-db id)))))))

(deftest tokens-save--success
  (let [id "test-id"
        token {:secret "test-secret"}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-saving id)
                                  (db/set-token-edit id ::editing)))))
    (rf/dispatch-sync [:tokens/save--success id {:body token}])
    
    (testing "sets new token in db"
      (is (= token (db/get-new-token @app-db id))))

    (testing "unmarks saving"
      (is (false? (db/saving? @app-db id))))

    (testing "adds to list of tokens"
      (is (= [token]
             (db/get-tokens @app-db id))))

    (testing "clears editing token"
      (is (nil? (db/get-token-edit @app-db id))))))

(deftest tokens-save--failed
  (let [id "test-id"]
    (is (some? (reset! app-db (db/set-saving {} id))))
    (rf/dispatch-sync [:tokens/save--failed id "test-error"])
    
    (testing "sets error in db"
      (is (= [:danger]
             (->> (db/get-alerts @app-db id)
                  (map :type)))))

    (testing "unmarks saving"
      (is (false? (db/saving? @app-db id))))))

(deftest tokens-prepare-delete
  (let [id ::test-id]
    (testing "stores token id in db"
      (rf/dispatch-sync [:tokens/prepare-delete id ::test-token])
      (is (= ::test-token (db/get-token-to-delete @app-db id))))))

(deftest tokens-delete
  (rft/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)
         token-id (str (random-uuid))]
     (h/initialize-martian {:delete-org-token
                            {:error-code :no-error}})
     
     (is (some? (swap! app-db #(-> %
                                   (db/set-token-to-delete db/org-id token-id)
                                   (r/set-current {:parameters
                                                   {:path
                                                    {:org-id "test-org"}}})))))
     (rf/dispatch [:tokens/delete db/org-id])

     (testing "deletes token in backend"
       (is (= 1 (count @c)))
       (is (= :delete-org-token (-> @c first (nth 2)))))

     (testing "passes route params and token id"
       (is (= {:org-id "test-org"
               :token-id token-id}
              (-> @c first (nth 3)))))

     (testing "marks deleting"
       (is (true? (db/deleting? @app-db db/org-id)))))))

(deftest tokens-delete--success
  (let [id ::test-id
        token {:id ::test-token}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-token-to-delete id (:id token))
                                  (db/set-deleting id)
                                  (db/set-tokens id [token])))))
    (rf/dispatch-sync [:tokens/delete--success id (:id token)])
    
    (testing "removes token from list"
      (is (empty? (db/get-tokens @app-db id))))
    
    (testing "clears token to delete"
      (is (nil? (db/get-token-to-delete @app-db id))))
    
    (testing "unmarks deleting"
      (is (false? (db/deleting? @app-db id))))))

(deftest tokens-delete--failed
  (let [id ::test-id
        token {:id ::test-token}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-token-to-delete id (:id token))
                                  (db/set-deleting id)
                                  (db/set-tokens id [token])))))
    (rf/dispatch-sync [:tokens/delete--failed id (:id token) "test error"])
    
    (testing "does not remove token from list"
      (is (= 1 (count (db/get-tokens @app-db id)))))

    (testing "sets token error"
      (is (= [:danger]
             (->> (db/get-alerts @app-db id)
                  (map :type)))))
    
    (testing "clears token to delete"
      (is (nil? (db/get-token-to-delete @app-db id))))
    
    (testing "unmarks deleting"
      (is (false? (db/deleting? @app-db id))))))
