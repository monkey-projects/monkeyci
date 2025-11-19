(ns monkey.ci.mailing.scw-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.ci.mailing.scw :as sut]
            [monkey.ci.protocols :as p]
            [monkey.scw.core :as scw]))

(deftest scw-mailer
  (let [params (atom nil)]
    (with-redefs [scw/email-ctx (constantly ::email-ctx)
                  mc/response-for (fn [_ req p]
                                    (when (= :create-email req)
                                      (reset! params p)
                                      (md/success-deferred
                                       {:status 200
                                        :body {:id "test-mail"}})))]
      (let [m (-> (sut/->ScwMailer {})
                  (co/start))]
        (testing "`start` creates api context"
          (is (= ::email-ctx (:ctx m))))

        (testing "`send-mail` sends email using api"
          (is (= "test-mail"
                 (-> (p/send-mail m {:subject "test mail"})
                     :id)))
          (is (= "test mail"
                 (get-in @params [:body :subject]))))))))
