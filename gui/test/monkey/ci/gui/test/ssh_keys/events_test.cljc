(ns monkey.ci.gui.test.ssh-keys.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.ssh-keys.db :as db]
            [monkey.ci.gui.ssh-keys.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest ssh-keys-load
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [ssh-keys [{:description "test key"
                      :private-key "test-private"
                      :public-key "test-public"}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-ssh-keys {:status 200
                                                      :body ssh-keys
                                                      :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:ssh-keys/load "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-ssh-keys
              (-> @c first (nth 2))))
       (is (= {:customer-id "test-customer"}
              (-> @c first (nth 3))))))))

(deftest ssh-keys-load--success
  (testing "sets ssh keys in db"
    (let [keys [{:description "test ssh key"}]]
      (rf/dispatch-sync [:ssh-keys/load--success {:body keys}])
      (is (= keys (db/get-value @app-db))))))

(deftest ssh-keys-load--failed
  (testing "sets alert"
    (rf/dispatch-sync [:ssh-keys/load--failed "test error"])
    (is (= 1 (count (db/get-alerts @app-db))))))

(deftest ssh-keys-new
  (testing "adds new empty key set to list of editing keys"
    (is (empty? (db/get-editing-keys @app-db)))
    (rf/dispatch-sync [:ssh-keys/new])
    (let [k (db/get-editing-keys @app-db)]
      (is (= 1 (count k)))
      (is (some? (:temp-id (first k)))
          "new key set should have temp id"))))

(deftest cancel-set
  (testing "removes new set from editing list"
    (let [new-set {:temp-id (random-uuid)}]
      (is (some? (reset! app-db (db/set-editing-keys {} [new-set]))))
      (rf/dispatch-sync [:ssh-keys/cancel-set new-set])
      (is (empty? (db/get-editing-keys @app-db)))))

  (testing "removes existing set from editing list"
    (let [ex-set {:id "existing-set"}]
      (is (some? (reset! app-db (db/set-editing-keys {} [ex-set]))))
      (rf/dispatch-sync [:ssh-keys/cancel-set ex-set])
      (is (empty? (db/get-editing-keys @app-db))))))

(deftest prop-changed
  (testing "updates property in ssh key"
    (let [key {:id "test-key"}]
      (is (some? (reset! app-db (db/set-editing-keys {} [key]))))
      (rf/dispatch-sync [:ssh-keys/prop-changed key :description "updated value"])
      (is (= [{:id "test-key"
               :description "updated value"}]
             (db/get-editing-keys @app-db))))))

(deftest save-set
  (rf-test/run-test-sync
   (is (some? (reset! app-db (-> {}
                                 (r/set-current {:parameters
                                                 {:path
                                                  {:customer-id "test-customer"}}})))))
   (h/initialize-martian {:update-customer-ssh-keys {:status 200
                                                     :body []
                                                     :error-code :no-error}})
   (is (some? (:martian.re-frame/martian @app-db)))

   (testing "saves all ssh keys to backend with updated set"
     (let [ks {:id "existing-set"
               :description "test key"
               :private-key "test-private"
               :public-key "test-public"}
           upd (assoc ks :description "updated")
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (swap! app-db (fn [db]
                                  (-> db
                                      (db/set-value [ks])
                                      (db/set-editing-keys [ks]))))))

       (rf/dispatch [:ssh-keys/save-set upd])
       (is (= 1 (count @c)))
       (is (= :update-customer-ssh-keys
              (-> @c first (nth 2))))
       (is (= {:customer-id "test-customer"
               :ssh-keys [(assoc upd :label-filters [])]}
              (-> @c first (nth 3))))))

   (testing "saves new ssh key to backend"
     (let [ks {:temp-id (str (random-uuid))
               :description "test key"
               :private-key "test-private"
               :public-key "test-public"}
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (swap! app-db (fn [db]
                                  (-> db
                                      (db/set-value [])
                                      (db/set-editing-keys [ks]))))))
       (rf/dispatch [:ssh-keys/save-set ks])
       (is (= 1 (count @c)))
       (is (= :update-customer-ssh-keys
              (-> @c first (nth 2))))
       (is (= {:customer-id "test-customer"
               :ssh-keys [(-> ks
                              (dissoc :temp-id)
                              (assoc :label-filters []))]}
              (-> @c first (nth 3))))))

   (testing "adds label filters"
     (let [id (str (random-uuid))
           ks {:temp-id id
               :description "test key"
               :private-key "test-private"
               :public-key "test-public"}
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (swap! app-db (fn [db]
                                  (-> db
                                      (db/set-value [])
                                      (db/set-editing-keys [ks])
                                      (lbl/set-labels (sut/labels-id id) ::test-labels))))))
       (rf/dispatch [:ssh-keys/save-set ks])
       (is (= ::test-labels
              (-> @c first (nth 3) :ssh-keys first :label-filters)))))))

(deftest save-set--success
  (let [ks {:id "editing-set"
            :description "updated"}]

    (is (some? (reset! app-db (-> {}
                                  (db/set-editing-keys [ks])
                                  (db/set-value [(assoc ks :description "original")])))))
    (rf/dispatch-sync [:ssh-keys/save-set--success ks {:body [ks]}])
    
    (testing "updates keys in db"
      (is (= [ks] (db/get-value @app-db))))
    
    (testing "removes keyset from editing"
      (is (empty? (db/get-editing-keys @app-db))))))

(deftest save-set--failed
  (testing "sets alert"
    (rf/dispatch-sync [:ssh-keys/save-set--failed {} "test error"])
    (is (= [:danger]
           (->> (db/get-alerts @app-db)
                (map :type))))))

(deftest edit-set
  (testing "adds to editing keys"
    (let [ks {:id "test-set"}]
      (is (some? (reset! app-db (db/set-value {} [ks]))))
      (rf/dispatch-sync [:ssh-keys/edit-set ks])
      (is (= [ks] (db/get-editing-keys @app-db))))))

(deftest delete-set
  (testing "saves ssh keys without deleted keyset"
    (let [[set-1 set-2 :as ksets] (->> (range 2)
                                       (map (fn [idx] {:id (str "set-" (inc idx))})))
          c (h/catch-fx :martian.re-frame/request)]
      (rf-test/run-test-sync
       (is (some? (reset! app-db (-> {}
                                     (r/set-current {:parameters
                                                     {:path
                                                      {:customer-id "test-customer"}}})
                                     (db/set-value ksets)
                                     (db/set-editing-keys [set-2])))))
       (h/initialize-martian {:update-customer-ssh-keys {:status 200
                                                         :body []
                                                         :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:ssh-keys/delete-set set-2])
       (is (= :update-customer-ssh-keys (-> @c first (nth 2))))
       (is (= [set-1] (-> @c first (nth 3) :ssh-keys)))))))
