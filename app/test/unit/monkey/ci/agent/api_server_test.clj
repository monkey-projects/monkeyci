(ns monkey.ci.agent.api-server-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [monkey.ci.agent.api-server :as sut]
            [monkey.ci.test
             [api-server :as tas]
             [helpers :as h]]))

(deftest api-server
  (let [test-build (h/gen-build)
        token (str (random-uuid))
        builds (atom {token test-build})
        test-config (-> (tas/test-config)
                        (assoc :builds builds))
        s (sut/start-server test-config)
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))]
    (with-open [srv (:server s)]

      (testing "returns 401 if no token given"
        (is (= 401 (-> {:url (make-url "test")
                        :method :get
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))
      
      (testing "returns 401 if wrong token given"
        (is (= 401 (-> {:url (make-url "test")
                        :method :get
                        :headers {"Authorization" "Bearer wrong token"}
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))

      (testing "returns 200 if valid token given"
        (is (= 200 (-> {:url (make-url "test")
                        :method :get
                        :headers {"Authorization" (str "Bearer " token)}
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))

      (testing "provides metrics"
        (is (= 204 (-> {:url (make-url "metrics")
                        :method :get}
                       (http/request)
                       deref
                       :status)))))))
