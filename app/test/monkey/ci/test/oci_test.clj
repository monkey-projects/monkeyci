(ns monkey.ci.test.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.oci :as sut]
            [monkey.oci.os.stream :as os])
  (:import java.io.ByteArrayInputStream))

(deftest stream-to-bucket
  (testing "pipes input stream to multipart"
    (with-redefs [os/input-stream->multipart (fn [ctx opts]
                                                 {:context ctx
                                                  :opts opts})]
      (let [in (ByteArrayInputStream. (.getBytes "test stream"))
            r (sut/stream-to-bucket {:key "value"} in)]
        (is (some? (:context r)))
        (is (= in (get-in r [:opts :input-stream])))))))
