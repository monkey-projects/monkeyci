(ns monkey.ci.gui.test.params.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.params.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest org-load-params
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [params [{:parameters [{:name "test-param" :value "test-val"}]}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-org-params {:status 200
                                               :body params
                                               :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/load "test-org"])
       (is (= 1 (count @c)))
       (is (= :get-org-params (-> @c first (nth 2)))))))
  
  (testing "marks loading"
    (rf/dispatch-sync [:params/load "test-org"])
    (is (true? (db/loading? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:params/load "test-org"])
    (is (nil? (db/alerts @app-db))))

  (testing "clears editing"
    (is (some? (reset! app-db (db/set-editing {} ::test-id ::test-params))))
    (rf/dispatch-sync [:params/load "test-org"])
    (is (nil? (db/get-editing @app-db ::test-id)))))

(deftest load-params--success
  (testing "sets params in db"
    (rf/dispatch-sync [:params/load--success {:body [::test-params]}])
    (is (= [::test-params] (db/params @app-db))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--success {:body [::test-params]}])
    (is (not (db/loading? @app-db)))))

(deftest load-params--failed
  (testing "sets alert in db"
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (not (db/loading? @app-db)))))

(deftest new-set
  (testing "adds new empty set to editing"
    (rf/dispatch-sync [:params/new-set])
    (let [p (db/edit-sets @app-db)]
      (is (= 1 (count p)))
      (is (db/temp-id? (first (keys p))))
      (is (db/temp-id? (-> (vals p) first :id))))))

(deftest new-set?
  (testing "`true` if set has no id"
    (is (true? (sut/new-set? {}))))

  (testing "`true` if set has temp id"
    (is (true? (sut/new-set? {:id (db/new-temp-id)}))))

  (testing "`false` otherwise"
    (is (false? (sut/new-set? {:id "existing-set-id"})))))

(deftest edit-set
  (let [id (random-uuid)]
    (is (some? (reset! app-db (db/set-params {} [{:id id
                                                  :parameters
                                                  [{:name "test" :value "test val"}]
                                                  :label-filters
                                                  [[{:label "test-label" :value "test-value"}]]}]))))
    (rf/dispatch-sync [:params/edit-set id])
    
    (testing "marks set being edited"
      (is (= [id] (keys (db/edit-sets @app-db))))
      (is (not-empty (db/get-editing @app-db id)))
      (is (true? (db/editing? @app-db id))))

    (testing "sets labels for set id"
      (is (= [[{:label "test-label" :value "test-value"}]]
             (lbl/get-labels @app-db [:params id]))))))

(deftest cancel-set
  (let [id (random-uuid)
        param {:id id
               :description "test param"
               :label-filters ::test-labels}]
    (is (some? (reset! app-db (-> {}
                                  (db/set-editing id param)
                                  (lbl/set-labels (sut/labels-id id) (:label-filters param))))))
    (rf/dispatch-sync [:params/cancel-set id])
    
    (testing "clears editing data for set"
      (is (nil? (db/get-editing @app-db id))))

    (testing "clears label data for set"
      (is (nil? (lbl/get-labels @app-db (sut/labels-id id)))))))

(deftest delete-set
  (let [id (str (random-uuid))]

    (testing "sends delete request to backend"
      (rf-test/run-test-sync
       (let [c (h/catch-fx :martian.re-frame/request)]
         (h/initialize-martian {:delete-param-set
                                {:status 204
                                 :error-code :no-error}})
         (is (some? (:martian.re-frame/martian @app-db)))
         (rf/dispatch [:params/delete-set id])
         (is (= 1 (count @c)))
         (is (= :delete-param-set (-> @c first (nth 2)))))))

    (testing "marks set deleting"
      (rf/dispatch-sync [:params/delete-set id])
      (is (db/set-deleting? @app-db id)))))

(deftest delete-set-success
  (testing "removes set from db"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {} id
                                                {:id id
                                                 :description "test set"}))))
      (rf/dispatch-sync [:params/delete-set--success id])
      (is (nil? (db/get-editing @app-db id)))))

  (testing "unmarks deleting"
    (is (some? (reset! app-db (db/mark-set-deleting {} ::test-id))))
    (rf/dispatch-sync [:params/delete-set--success ::test-id])
    (is (false? (db/set-deleting? @app-db ::test-id))))
  
  (testing "removes set from params"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-params {} [{:id id
                                                    :description "deleted set"}]))))
      (rf/dispatch-sync [:params/delete-set--success id])
      (is (empty? (db/params @app-db)))))

  (testing "removes set labels"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (lbl/set-labels {} (sut/labels-id id) ::test-labels))))
      (rf/dispatch-sync [:params/delete-set--success id])
      (is (nil? (lbl/get-labels @app-db (sut/labels-id id)))))))

(deftest delete-set-failed
  (let [id (random-uuid)]
    (testing "sets set alert"    
      (rf/dispatch-sync [:params/delete-set--failed id])
      (is (not-empty (db/get-set-alerts @app-db id))))

    (testing "unmarks deleting"
      (is (some? (reset! app-db (db/mark-set-deleting {} id))))
      (rf/dispatch-sync [:params/delete-set--failed id])
      (is (false? (db/set-deleting? @app-db id))))))

(deftest new-param
  (testing "adds empty param to set in db"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {}
                                                id
                                                {:description "test set"
                                                 :parameters [{:name "first param"
                                                               :value "first value"}]}))))
      (rf/dispatch-sync [:params/new-param id])
      (is (= {:description "test set"
              :parameters [{:name "first param"
                            :value "first value"}
                           {}]}
             (db/get-editing @app-db id))))))

(deftest delete-param
  (testing "removes param at idx from set"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {}
                                                id
                                                {:description "test set"
                                                 :parameters [{:name "first param"
                                                               :value "first value"}]}))))
      (rf/dispatch-sync [:params/delete-param id 0])
      (is (= {:description "test set"
              :parameters []}
             (db/get-editing @app-db id))))))

(deftest cancel-all
  (h/catch-fx :route/goto) ; Safety
  
  (testing "resets all form data to original db values"
    (is (some? (reset! app-db (-> {}
                                  (db/set-params [{:description "original set"}
                                                  {:description "second set"}])
                                  (db/set-editing :test-id
                                                  {:description "updated set"})))))
    (rf/dispatch-sync [:params/cancel-all])
    (is (nil? (db/edit-sets @app-db))))

  (testing "redirects to org page"
    (let [e (h/catch-fx :route/goto)]
      (rf-test/run-test-sync
       (rf/dispatch [:params/cancel-all])
       (is (= 1 (count @e)))))))

(deftest description-changed
  (testing "updates set description"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {} id {:description "original desc"}))))
      (rf/dispatch-sync [:params/description-changed id "updated desc"])
      (is (= {:description "updated desc"}
             (db/get-editing @app-db id))))))

(deftest label-changed
  (testing "updates param label"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {}
                                                id
                                                {:parameters
                                                 [{:name "original label"
                                                   :value "original value"}]}))))
      (rf/dispatch-sync [:params/label-changed id 0 "updated label"])
      (is (= {:parameters
              [{:name "updated label"
                :value "original value"}]}
             (db/get-editing @app-db id))))))

(deftest value-changed
  (testing "updates param value"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-editing {}
                                                id
                                                {:parameters
                                                 [{:name "original label"
                                                   :value "original value"}]}))))
      (rf/dispatch-sync [:params/value-changed id 0 "updated value"])
      (is (= {:parameters
              [{:name "original label"
                :value "updated value"}]}
             (db/get-editing @app-db id))))))

(deftest params-save-set
  (testing "sends request for existing set"
    (rf-test/run-test-sync
     (let [params {:id "test-id"
                   :parameters {}
                   :label-filters []}
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (db/set-editing {} (:id params) params))))
       (h/initialize-martian {:update-param-set {:status 200
                                                 :body params
                                                 :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/save-set "test-id"])
       (is (= 1 (count @c)))
       (is (= :update-param-set (-> @c first (nth 2))))
       (is (= params (-> @c first (nth 3) :params))))))

  (testing "sends request for new set"
    (rf-test/run-test-sync
     (let [params {:id (db/new-temp-id)
                   :parameters {}
                   :label-filters []}
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (db/set-editing {} (:id params) params))))
       (h/initialize-martian {:create-param-set {:status 201
                                                 :body params
                                                 :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/save-set (:id params)])
       (is (= 1 (count @c)))
       (is (= :create-param-set (-> @c first (nth 2))))
       (is (= (dissoc params :id)
              (-> @c first (nth 3) :params))))))

  (testing "ensures label filters key is present"
    (rf-test/run-test-sync
     (let [params {:id "test-id"
                   :parameters {}}
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (db/set-editing {} (:id params) params))))
       (h/initialize-martian {:update-param-set {:status 200
                                                 :body params
                                                 :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/save-set "test-id"])
       (is (some? (-> @c first (nth 3) :params :label-filters))))))

  (testing "adds label filters from label editor"
    (rf-test/run-test-sync
     (let [params {:id "test-id"
                   :parameters {}}
           lbl-filters [[{:label "test-label"
                          :value "test value"}]]
           c (h/catch-fx :martian.re-frame/request)]
       (is (some? (reset! app-db (-> {}
                                     (db/set-editing (:id params) params)
                                     (lbl/set-labels (sut/labels-id (:id params)) lbl-filters)))))
       (h/initialize-martian {:update-param-set {:status 200
                                                 :body params
                                                 :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/save-set "test-id"])
       (is (= lbl-filters
              (-> @c first (nth 3) :params :label-filters))))))
  
  (testing "marks saving"
    (let [id (random-uuid)]
      (rf/dispatch-sync [:params/save-set id])
      (is (true? (db/saving? @app-db id)))))

  (testing "clears alerts"
    (let [id (random-uuid)]
      (is (some? (reset! app-db (db/set-set-alerts {} id ::test-alerts))))
      (rf/dispatch-sync [:params/save-set id])
      (is (nil? (db/get-set-alerts @app-db id))))))

(deftest params-save-set-success
  (let [id (random-uuid)
        params {:id id
                :parameters [{:name "test"
                              :value "test value"}]}]
    (testing "unmarks saving"
      (is (some? (reset! app-db (db/mark-saving {} id))))
      (rf/dispatch-sync [:params/save-set--success id {:body params}])
      (is (not (db/saving? @app-db id))))
    
    (testing "clears editing"
      (is (some? (reset! app-db (db/set-editing {} id params))))
      (rf/dispatch-sync [:params/save-set--success id {:body params}])
      (is (nil? (db/get-editing @app-db id))))

    (testing "updates existing param set in db"
      (is (some? (reset! app-db (db/set-params {} [{:id id
                                                    :description "stale set"}]))))
      (rf/dispatch-sync [:params/save-set--success id {:body params}])
      (is (= [params] (db/params @app-db))))

    (testing "adds new param set to db"
      (is (empty? (reset! app-db {})))
      (rf/dispatch-sync [:params/save-set--success id {:body params}])
      (is (= [params] (db/params @app-db))))))

(deftest params-save-set--failed
  (let [id (random-uuid)]
    (testing "unmarks saving"
      (is (some? (reset! app-db (db/mark-saving {} id))))
      (rf/dispatch-sync [:params/save-set--failed id "test error"])
      (is (not (db/saving? @app-db id))))

    (testing "sets error"
      (rf/dispatch-sync [:params/save-set--failed id "test error"])
      (is (= [:danger]
             (->> (db/get-set-alerts @app-db id)
                  (map :type)))))))
