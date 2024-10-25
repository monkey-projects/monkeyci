(ns monkey.ci.gui.test.alerts-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [clojure.string :as cs]
            [monkey.ci.gui.alerts :as sut]))

(deftest alert-msg
  (testing "creates function that generates alert"
    (let [msg (sut/alert-msg :info (constantly "test alert"))]
      (is (fn? msg))
      (is (= {:type :info
              :message "test alert"}
             (msg)))))

  (testing "passes arguments to formatter"
    (let [msg (sut/alert-msg :info (fn [& args] (cs/join "-" args)))]
      (is (= "a-b-c" (-> (msg "a" "b" "c") :message))))))

(deftest error-msg
  (testing "generates error message"
    (let [msg (sut/error-msg "test msg")]
      (is (= {:type :danger
              :message "test msg: test error"}
             (msg "test error"))))))
