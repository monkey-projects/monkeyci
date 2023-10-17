(ns monkey.ci.test.web.script-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [events :as e]
             [storage :as st]]
            [monkey.ci.web.script-api :as sut]
            [monkey.ci.test.helpers :as h]
            [ring.mock.request :as mock]))

(deftest routes
  (let [bus (e/make-bus)
        test-app (sut/make-app {:public-api (fn [_]
                                              (fn [ep]
                                                (when (= :get-params ep)
                                                  {"key" "value"})))
                                :event-bus bus})]

    (testing "unknown endpoint results in 404"
      (is (= 404 (-> (mock/request :get "/unknown")
                     (test-app)
                     :status))))

    (testing "provides openapi spec"
      (is (= 200 (-> (mock/request :get "/script/swagger.json")
                     (test-app)
                     :status))))

    (testing "'GET /script/params` retrieves exposed parameter values through public api"
      (let [r (-> (mock/request :get "/script/params")
                  (test-app))]
        (is (= 200 (:status r)))
        (is (= {:key "value"} (-> (:body r)
                                  (slurp)
                                  (h/parse-json))))))

    (testing "`POST /script/event` posts event to bus"
      (let [evt {:type :test-event :message "test event"}
            w (e/wait-for bus :test-event (map identity))
            r (-> (mock/request :post "/script/event")
                  (mock/body (pr-str evt))
                  (mock/header :content-type "application/edn")
                  (test-app))]
        (is (= 202 (:status r)))
        (is (= evt (-> (h/try-take w)
                       (select-keys (keys evt)))))))))

(deftest start-server
  (testing "runs httpkit server"
    (with-redefs [org.httpkit.server/run-server (constantly :test-server)]
      (is (= :test-server (sut/start-server {:public-api sut/local-api}))))))

(deftest local-api
  (testing "`get-params` retrieves from local db"
    (let [sid ["test-cust" "test-proj" "test-repo" "test-build"]
          st (st/make-memory-storage)
          api (sut/local-api {:storage st
                              :build {:sid sid}})
          params [{:name "testkey" :value "testvalue"}]]
      (is (st/sid? (st/save-params st (butlast sid) params)))
      (is (fn? api))
      (is (= {"testkey" "testvalue"} (api :get-params))))))
