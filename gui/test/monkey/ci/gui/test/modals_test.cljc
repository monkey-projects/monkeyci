(ns monkey.ci.gui.test.modals-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is]]
               :clj [clojure.test :refer [deftest testing is]])
            [monkey.ci.gui.modals :as sut]
            [re-frame.core :as rf]))

(deftest modal
  (testing "renders modal"
    (is (= :div.modal.fade (first (sut/modal ::test-id
                                             "test title"
                                             "test contents"))))))
