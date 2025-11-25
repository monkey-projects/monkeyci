(ns monkey.ci.mailing.scw-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.ci.mailing.scw :as sut]
            [monkey.ci.protocols :as p]
            [monkey.scw.core :as scw]))

(deftest scw-mailer
  (let [inv (atom [])]
    (with-redefs [scw/email-ctx (constantly ::email-ctx)
                  mc/response-for (fn [_ req p]
                                    (when (= :create-email req)
                                      (swap! inv conj p)
                                      (md/success-deferred
                                       {:status 200
                                        :body {:id "test-mail"}})))]
      (let [m (-> (sut/->ScwMailer {:unsubscribe "mailto:test-unsub"})
                  (co/start))]
        (testing "`start` creates api context"
          (is (= ::email-ctx (:ctx m))))

        (testing "`send-mail`"
          (testing "returns body"
            (is (= "test-mail"
                   (-> (p/send-mail m {:subject "test mail"
                                       :destinations ["test@monkeyci.com"]})
                       first
                       :id))))

          (testing "sends email using api"
            (is (= 1 (count @inv))))

          (testing "sets subject"
            (is (= "test mail"
                   (get-in (first @inv) [:body :subject]))))

          (testing "sets `list-unsubscribe` header according to config"
            (is (= {:key "list-unsubscribe"
                    :value "mailto:test-unsub"}
                   (-> (get-in (first @inv) [:body :additional-headers])
                       first))))

          (testing "sends separate mail to each recipient"
            (is (empty? (reset! inv [])))
            (is (some? (p/send-mail m {:subject "another mail"
                                       :text-body "Test mail body"
                                       :destinations ["dest1@monkeyci.com"
                                                      "dest2@monkeyci.com"]})))
            (is (= 2 (count @inv))))

          (testing "applies destination to subject if fn"
            (is (empty? (reset! inv [])))
            (is (some? (p/send-mail m {:subject (partial str "hello ")
                                       :destinations ["John"]})))
            (is (= ["hello John"]
                   (map (comp :subject :body) @inv))))

          (testing "applies destination to body if fn"
            (is (empty? (reset! inv [])))
            (is (some? (p/send-mail m {:text-body (partial str "hello ")
                                       :destinations ["John"]})))
            (is (= ["hello John"]
                   (map (comp :text :body) @inv))))

          (testing "applies formats list-unsubscribe with destination"
            (let [m (-> (sut/->ScwMailer {:unsubscribe "http://unsubscibe?email=%s"})
                        (co/start))]
              (is (empty? (reset! inv [])))
              (is (some? (p/send-mail m {:destinations ["test@monkeyci.com"]})))
              (is (= "http://unsubscibe?email=test@monkeyci.com"
                     (-> @inv
                         first
                         :body
                         :additional-headers
                         first
                         :value))))))))))
