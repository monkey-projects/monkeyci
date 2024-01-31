(ns monkey.ci.events.manifold-test
  (:require [monkey.ci.events
             [core :as c]
             [manifold :as sut]]
            [monkey.ci.helpers :as h]
            [clojure.test :refer [deftest testing is]]))

(deftest manifold-events
  (testing "listeners receive events"
    (let [e (sut/make-events)
          evt {:type ::test-event}
          recv (atom [])
          l (c/no-dispatch
              (partial swap! recv conj))]
      (is (= e (c/add-listener e l)))
      (is (= e (c/post-events e evt)))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) 200)))
      (is (= [evt] @recv))
      (is (= e (c/remove-listener e l)))
      (is (empty? (reset! recv [])))
      (is (= e (c/post-events e evt)))
      (is (= :timeout (h/wait-until #(pos? (count @recv)) 200)))))

  (testing "return values of handlers are posted back as events"
    (let [recv (atom [])
          e (-> (sut/make-events)
                (c/add-listener (c/filter-type ::first (constantly {:type ::second})))
                (c/add-listener (->> (partial swap! recv conj)
                                     (c/no-dispatch)
                                     (c/filter-type ::second))))]
      (is (some? (c/post-events e {:type ::first})))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) 200)))
      (is (= ::second (-> @recv first :type)))))

  (testing "can post multiple events at once"
    (let [recv (atom [])
          e (-> (sut/make-events)
                (c/add-listener (c/no-dispatch (partial swap! recv conj))))]
      (is (some? (c/post-events e [::first ::second])))
      (is (not= :timeout (h/wait-until #(= 2 (count @recv)) 1000)))
      (is (= [::first ::second] @recv)))))
