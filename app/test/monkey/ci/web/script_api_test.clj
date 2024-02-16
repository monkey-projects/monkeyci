(ns monkey.ci.web.script-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.web.script-api :as sut]
            [monkey.ci.helpers :as h]
            [ring.mock.request :as mock]))

(deftest routes
  (let [events (atom [])
        test-app (sut/make-app {:public-api (fn [_]
                                              (fn [ep]
                                                (when (= :get-params ep)
                                                  {"key" "value"})))
                                :events {:poster (partial swap! events conj)}})]

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
            r (-> (mock/request :post "/script/event")
                  (mock/body (pr-str evt))
                  (mock/header :content-type "application/edn")
                  (test-app))]
        (is (= 202 (:status r)))
        (is (= evt (-> @events
                       (first)
                       (select-keys (keys evt)))))))))

(deftest start-server
  (testing "runs httpkit server"
    (with-redefs [org.httpkit.server/run-server (constantly :test-server)]
      (is (= :test-server (sut/start-server {:public-api sut/local-api}))))))

(deftest local-api
  (testing "`get-params` retrieves params from local db"
    (let [[cust-id repo-id :as sid] ["test-cust" "test-repo" "test-build"]
          st (st/make-memory-storage)
          api (sut/local-api {:storage st
                              :build {:sid sid}})
          params [{:parameters [{:name "testkey" :value "testvalue"}]}]]
      (is (st/sid? (st/save-customer st {:id cust-id
                                         :repos {repo-id {:name "test repo"}}})))
      (is (st/sid? (st/save-params st cust-id params)))
      (is (fn? api))
      (is (= {"testkey" "testvalue"} (api :get-params))))))
