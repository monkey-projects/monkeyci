(ns monkey.ci.web.api.invoice-test
  (:require [clojure.test :refer [deftest is testing]]
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
        cust (h/gen-cust)
        [a b c :as inv] (->> [{:kind :invoice
                               :currency "EUR"
                               :net-amount 100M
                               :vat-perc 21M}]
                             (map (partial merge (h/gen-invoice)))
                             (map #(assoc % :org-id (:id cust)))
                             (remove nil?))]
    (is (some? (st/save-org st cust)))
    (is (some? (->> inv
                    (map (partial st/save-invoice st))
                    (doall))))
    
    (testing "when no filter given, returns all org invoices"
      (is (= inv (-> (h/->req rt)
                     (assoc-in [:parameters :path :org-id] (:id cust))
                     (sut/search-invoices)
                     :body))))

    (testing "parses filter dates")))
