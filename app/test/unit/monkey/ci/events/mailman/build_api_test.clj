(ns monkey.ci.events.mailman.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.edn :as edn]
            [monkey.ci.events.mailman.build-api :as sut]
            [monkey.mailman.core :as mmc]))

(deftest build-api-broker
  (testing "posts events to api client"
    (let [posted (atom [])
          client (fn [req]
                   (swap! posted conj req)
                   (md/success-deferred {:status 200}))
          evts [{:type ::test-event}]
          broker (sut/make-broker client nil)]
      (is (= evts (mmc/post-events broker evts)))
      (is (= (pr-str evts) (-> posted
                               (deref)
                               first
                               :body)))))

  (let [stream (ms/stream)
        broker (sut/make-broker nil stream)
        recv (atom [])
        l (mmc/add-listener broker (fn [evt]
                                     (swap! recv conj evt)
                                     nil))
        evt {:type ::test-event}]
    (testing "listeners receive events from api SSE stream"
      (is (some? l))
      (is (true? (deref (ms/put! stream evt) 100 :timeout))))

    (testing "can unregister listener"
      (is (true? (mmc/unregister-listener l)))))

  (let [stream (ms/stream)
        client (fn [req]
                 (ms/put-all! stream (edn/edn-> (:body req)))
                 (md/success-deferred {:status 200}))
        broker (sut/make-broker client stream)]
    (testing "re-posts resulting events"
      (let [recv (atom [])
            l (mmc/add-listener broker (fn [evt]
                                         (swap! recv conj evt)
                                         (when (= ::first (:type evt))
                                           [{:result [{:type ::second}]}])))]
        (is (some? (mmc/post-events broker [{:type ::first}])))
        (is (= 2 (count @recv)))
        (is (= [::first ::second] (map :type @recv)))))))
