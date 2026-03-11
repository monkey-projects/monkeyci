(ns monkey.ci.mailing.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [cuid :as cuid]
             [edn :as edn]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.mailing.events :as sut]
            [monkey.ci.test.helpers :as h]
            [monkey.mailman.core :as mmc]))

(deftest routes
  (testing "handles required events"
    (let [exp [:email/confirmation-created]
          r (sut/make-routes {})
          handled (->> r
                       (map first)
                       (set))]
      (doseq [t exp]
        (is (contains? handled t)
            (str "expected to handle " t))))))

(deftest add-config
  (let [{:keys [enter] :as i} (sut/add-config ::test-config)]
    (is (keyword? (:name i)))

    (testing "`enter` adds config to ctx"
      (is (= ::test-config
             (-> {}
                 (enter)
                 (sut/get-config)))))))

(deftest result->schedule
  (let [{:keys [leave] :as i} sut/result->scheduled]
    (is (keyword? (:name i)))
    
    (testing "`leave` moves result to scheduled emails"
      (let [email ::test-email
            r (-> {}
                  (ec/set-result [email])
                  (leave))]
        (is (nil? (ec/result r)))
        (is (= [email] (sut/get-scheduled-mails r)))))))

(deftest send-scheduled-mails
  (let [mailer (h/fake-mailer)
        {:keys [leave] :as i} sut/send-scheduled-mails
        mail ::test-mail]
    (is (keyword? (:name i)))
    
    (testing "`leave` sends out scheduled emails"
      (let [r (-> {}
                  (sut/set-config {:mailer mailer})
                  (sut/set-scheduled-mails [mail])
                  (leave))]
        (is (= 1 (count @(:mailings mailer))))
        (is (some? (sut/get-mail-results r))
            "adds mail results to context")))))

(deftest confirmation-created
  (let [code (str (random-uuid))
        r (-> {:event
               {:type :confirmation-created
                :email "test@monkeyci.com"
                :id (cuid/random-cuid)
                :code code}}
              (sut/set-config {:site-url "http://test-url"})
              (sut/confirmation-created))]
    (testing "sends confirmation email to address"
      (is (= ["test@monkeyci.com"]
             (-> r first :destinations)))
      (is (string? (-> r first :subject)))
      (is (not-empty (-> r first :text-body))))

    (testing "includes url with confirmation code"
      (let [tb (-> r first :text-body (.replaceAll "\n" ""))
            [_ v] (re-matches #"^.*http://test-url/email/confirm\?code=(\S+).*$" tb)]
        (is (some? v))
        (is (= code (some-> v h/base64-> edn/edn-> :code)))))))
