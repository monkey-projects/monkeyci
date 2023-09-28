(ns monkey.ci.test.build.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.build
             [core :as sut]
             [spec :as spec]]))

(defn pipeline? [x]
  (instance? monkey.ci.build.core.Pipeline x))

(deftest failed?
  (testing "true if not successful"
    (is (sut/failed? sut/failure))
    (is (sut/failed? nil))
    (is (not (sut/failed? sut/success)))))

(deftest pipeline
  ;; (testing "stops on failed step"
  ;;   (let [executed? (atom nil)
  ;;         step (fn [r rv]
  ;;                (fn [& args]
  ;;                  (reset! executed? r)
  ;;                  {:status rv}))
  ;;         p {:steps [(step :first :failure)
  ;;                    (step :second :success)]}]
  ;;     (is (= :failure (:status (sut/pipeline p))))
  ;;     (is (= :first @executed?)))))

  (testing "creates pipeline object"
    (is (pipeline? (sut/pipeline {:steps []}))))

  (testing "fails if config not conforming to spec"
    (is (thrown? AssertionError (sut/pipeline {}))))

  (testing "function is valid step"
    (is (s/valid? :ci/step (constantly "ok"))))

  (testing "map is valid step"
    (is (s/valid? :ci/step {:action (constantly "ok")})))

  (testing "accepts container image"
    (let [p {:steps [{:container/image "test-image"
                      :script ["first" "second"]}]}]
      (is (s/valid? :ci/step (-> p :steps (first))))
      (is (pipeline? (sut/pipeline p))))))
