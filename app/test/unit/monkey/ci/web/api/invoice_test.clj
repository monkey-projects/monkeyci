(ns monkey.ci.web.api.invoice-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.invoice :as sut]))

(deftest get-invoice
  (testing "retrieves single invoice by id"
    (let [{st :storage :as rt} (trt/test-runtime)
          inv (h/gen-invoice)]
      (is (some? (st/save-invoice st inv)))
      (is (= inv (-> (h/->req rt)
                     (assoc-in [:parameters :path] {:org-id (:org-id inv)
                                                    :invoice-id (:id inv)})
                     (sut/get-invoice)
                     :body))))))

(deftest search-invoices
  (let [{st :storage :as rt} (trt/test-runtime)
        org (h/gen-org)
        [a b c :as inv] (->> [{:kind :invoice
                               :currency "EUR"
                               :net-amount 100M
                               :vat-perc 21M}]
                             (map (partial merge (h/gen-invoice)))
                             (map #(assoc % :org-id (:id org)))
                             (remove nil?))]
    (is (some? (st/save-org st org)))
    (is (some? (->> inv
                    (map (partial st/save-invoice st))
                    (doall))))
    
    (testing "when no filter given, returns all org invoices"
      (is (= inv (-> (h/->req rt)
                     (assoc-in [:parameters :path :org-id] (:id org))
                     (sut/search-invoices)
                     :body))))

    (testing "parses filter dates")))

(deftest create-invoice
  (let [ext-inv (atom [])
        fake-client (fn [req]
                      (swap! ext-inv conj req)
                      (condp = (:path req)
                        "/invoice"
                        (md/success-deferred
                         {:status 201
                          :body {:id 123
                                 :invoice-nr "INV1234"}})
                        "/customer"
                        (md/success-deferred
                         {:status 200
                          :body [{:id 1 :name "test org"}]})
                        (md/error-deferred (ex-info "Invalid request" req))))
        {st :storage :as rt} (-> (trt/test-runtime)
                                 (trt/set-invoicing-client fake-client))
        org (-> (h/gen-org)
                (assoc :name "test org"))
        _ (st/save-org st org)
        _ (st/save-org-invoicing st {:org-id (:id org)
                                     :ext-id "1"})
        res (-> (h/->req rt)
                (assoc-in [:parameters :path :org-id] (:id org))
                (assoc-in [:parameters :body] {:amount 10M})
                (sut/create-invoice))]
    (is (= 201 (:status res)) (:body res))
    
    (testing "creates invoice in db"
      (is (= 1 (-> (st/list-invoices-for-org st (:id org))
                   (count)))))

    (testing "creates invoice externally"
      (is (= 2 (count @ext-inv))))

    (testing "set external id and invoice nr in db"
      (let [inv (st/find-invoice st [(:id org) (get-in res [:body :id])])]
        (is (some? inv))
        (is (= "123" (:ext-id inv)))
        (is (= "INV1234" (:invoice-nr inv)))))))
