(ns e2e.monkey.ci.e2e.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.e2e.common :as c]))

(deftest user-security
  (testing "when unauthenticated"
    (let [r (-> {:url (c/sut-url "/user")
                 :method :post
                 :throw-exceptions false}
                (c/set-body {:type "github"
                             :type-id 1000
                             :orgs []})
                (c/accept-edn)
                (http/request)
                (deref))
          b (c/try-parse-body r)]
      (testing "should not be able to create users"
        (is (= 401 (:status r)))
        (is (nil? (:id b))))

      (when (:id b)
        (testing "should not be able to edit user"
          (is (= 401 (-> {:url (c/sut-url (str "/user/github/1000"))
                          :method :put
                          :throw-exceptions false}
                         (c/set-body (assoc b :orgs ["test-org"]))
                         (http/request)
                         (deref)
                         :status))))

        (testing "should not be able to delete nonexisting user"
          (is (= 401 (-> (http/delete (c/sut-url (str "/user/" (:id b))))
                         deref
                         :status))))))))
