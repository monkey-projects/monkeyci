(ns monkey.ci.test.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [commands :as co]
             [components :as sut]
             [events :as e]]
            [monkey.ci.web.handler :as wh]
            [monkey.ci.test.helpers :as h]
            [org.httpkit.server :as http]))

(deftest bus-component
  (testing "`start` creates a bus"
    (is (e/bus? (-> (sut/new-bus)
                    (c/start)))))

  (testing "`stop` destroys the bus"
    (is (nil? (-> (sut/new-bus)
                  (c/start)
                  (c/stop)
                  :pub)))))

(deftest command-handler
  (testing "`start` registers handler in the bus"
    (h/with-bus
      (fn [bus]
        (is (some? (-> (sut/new-command-handler)
                       (assoc :bus bus)
                       (c/start)
                       :handler))))))

  (testing "handles `command/invoked` events"
    (h/with-bus
      (fn [bus]
        (let [handled (atom [])
              component (-> (sut/->CommandHandler bus)
                            (c/start))]
          (defmethod co/handle-command ::test [evt]
            (swap! handled conj evt))
          (e/post-event bus {:type :command/invoked
                             :command ::test})
          (is (not= :timeout (h/wait-until #(pos? (count @handled)) 500)))
          (is (= 1 (count @handled)))
          ;; Clean up
          (remove-method co/handle-command ::test)
          (is (some? (c/stop component)))))))

  (testing "`stop` unregisters handler"
    (h/with-bus
      (fn [bus]
        (is (nil? (-> (sut/map->CommandHandler {:bus bus})
                      (c/start)
                      (c/stop)
                      :handler)))))))

(defrecord TestServer []
  http/IHttpServer
  (-server-stop! [s _]
    (assoc s :stopped? true)))

(deftest http-component
  (testing "starts http server"
    (with-redefs [wh/start-server (constantly ::server-started)]

      (is (= ::server-started (-> (sut/new-http-server)
                                  (c/start)
                                  :server)))))

  (testing "stops http server on `stop`"
    (is (nil? (-> (sut/map->HttpServer {:server (->TestServer)})
                  (c/stop)
                  :server)))))

(deftest build-runners
  (testing "registers handlers on start for local"
    (h/with-bus
      (fn [bus]
        (is (= 3 (-> (sut/new-build-runners)
                     (assoc :config {:runner {:type :local}}
                            :bus bus)
                     (c/start)
                     :handlers
                     (count)))))))

  (testing "unregisters handlers on stop"
    (h/with-bus
      (fn [bus]
        (is (nil? (-> (sut/new-build-runners)
                      (assoc :config {:runner {:type :local}}
                             :bus bus)
                      (c/start)
                      (c/stop)
                      :handlers)))))))
