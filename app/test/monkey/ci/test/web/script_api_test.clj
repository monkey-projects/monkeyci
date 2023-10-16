(ns monkey.ci.test.web.script-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.script-api :as sut]
            [monkey.ci.test.helpers :as h]
            [ring.mock.request :as mock]))

(deftest routes
  (let [test-app (sut/make-app {:public-api (fn [ep]
                                              (when (= :get-params ep)
                                                {"key" "value"}))})]

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
                                  (h/parse-json))))))))

(deftest start-server
  (testing "runs httpkit server"
    (with-redefs [org.httpkit.server/run-server (constantly :test-server)]
      (is (= :test-server (sut/start-server {}))))))
