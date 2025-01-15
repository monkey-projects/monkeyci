(ns monkey.ci.gui.test.local-storage-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.local-storage :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

;; Local browser storage is not available in node
(when (sut/local-storage-enabled?)
  (deftest local-storage
    (testing "can store and load from browser local storage"
      (let [id ::test-storage
            val {:key "value"}]
        (rf-test/run-test-sync
         (rf/reg-event-fx
          ::init-storage
          (fn [_ _]
            {:local-storage [id val]}))
         
         (rf/reg-event-fx
          ::load-storage
          [(rf/inject-cofx :local-storage id)]
          (fn [{:keys [db] :as ctx} _]
            {:db (assoc db ::loaded-value (get ctx :local-storage))}))

         (rf/dispatch [::init-storage])
         (rf/dispatch [::load-storage])
         (is (= val (::loaded-value @app-db))))))))
