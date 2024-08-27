(ns monkey.ci.events.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [martian
             [core :as mc]
             [test :as mt]]
            [monkey.ci.events.build-api :as sut]
            [monkey.ci.protocols :as p]
            [schema.core :as s]))

(deftest build-api-post-events
  (let [client (-> (mc/bootstrap "http://test-api"
                                 [{:route-name :post-events
                                   :path-parts ["/events"]
                                   :method :post
                                   :body-schema {:body [{s/Keyword s/Str}]}}])
                   (mt/respond-with-constant {:post-events {:status 202}}))
        poster (sut/->BuildApiEventPoster client)]
    
    (testing "invokes `post-events` endpoint"
      (is (some? (p/post-events poster [{:type ::test-event}]))))))
