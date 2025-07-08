(ns monkey.ci.web.trigger-test
  (:require [buddy.core.codecs :as bcc]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.vault.common :as vc]
            [monkey.ci.web
             [trigger :as sut]
             [crypto :as crypto]]))

(deftest prepare-triggered-build
  (h/with-memory-store st
    (let [org-id (cuid/random-cuid)
          dek (atom nil)
          rt (-> (trt/test-runtime)
                 (trt/set-storage st)
                 (trt/set-encrypter
                  (fn [x org cuid]
                    (when (and (= org-id org)
                               (= cuid org)
                               (crypto/b64-dek? x))
                      ;; Store the unencrypted dek for asserts
                      (reset! dek x)
                      "encrypted-dek")))
                 (trt/set-decrypter
                  (fn [x org cuid]
                    "decrypted-key")))
          repo {:id "test-repo"
                :org-id org-id
                :main-branch "main"
                :labels [{:name "test-lbl"
                          :value "lbl-val"}]}
          _ (st/save-repo st repo)
          _ (st/save-ssh-keys st org-id [{:id "test-ssh-key"
                                          :private-key "encrypted-key"
                                          :label-filter
                                          [[{:label "test-lbl"
                                             :value "lbl-val"}]]}])
          p (sut/prepare-triggered-build
             {:org-id org-id
              :repo-id "test-repo"
              :message "Previous build message"
              :git {:ref "original/ref"}}
             rt
             repo)]
      (testing "assigns id"
        (is (some? (:id p))))

      (testing "sets start time"
        (is (some? (:start-time p))))

      (testing "clears message"
        (is (nil? (:message p))))

      (testing "marks pending"
        (is (= :pending (:status p))))

      (testing "keeps configured git ref"
        (is (= "original/ref" (get-in p [:git :ref]))))

      (testing "adds new encrypted data encryption key"
        (is (= "encrypted-dek" (:dek p)))
        (is (string? @dek))
        (is (crypto/b64-dek? @dek)))

      (testing "looks up associated ssh keys and adds them encrypted"
        (let [sk (get-in p [:git :ssh-keys])]
          (is (not-empty sk))
          (is (= (vc/encrypt (bcc/b64->bytes @dek)
                             (crypto/cuid->iv org-id)
                             "decrypted-key")
                 (-> sk
                     first
                     :private-key)))))

      (testing "uses main branch from repo if no configured ref"
        (is (= "refs/heads/main"
               (-> p
                   (update :git dissoc :ref)
                   (sut/prepare-triggered-build rt repo)
                   (get-in [:git :ref]))))))))
