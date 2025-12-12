(ns monkey.ci.gui.test.billing.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.billing.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest load-invoicing
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:get-org-invoicing-settings
                            {:body {:currency "EUR"}
                             :error-code :no-error}})
     (is (some? (swap! app-db r/set-current {:parameters
                                             {:path
                                              {:org-id "test-org"}}})))
     (rf/dispatch [::sut/load-invoicing])

     (testing "loads invoicing settings from backend"
       (is (= 1 (count @c)))
       (is (= :get-org-invoicing-settings (-> @c first (nth 2))))))))
