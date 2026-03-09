(ns monkey.ci.spec.invoice
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [common :as c]
             [gen :as sg]]))

;; Invoice numbers are strings, but should only consist of digits
(s/def ::invoice-nr (s/with-gen
                             (partial re-matches #"\d+")
                             #(gen/return (str (int (* 100000 (rand)))))))
(s/def ::net-amount (s/and decimal? pos?))
(s/def ::vat-perc (s/and decimal? pos?))
(s/def ::currency #{"EUR" "USD" "GBP"})
(s/def ::kind #{:invoice :creditnote})
(s/def ::date c/date?)
(s/def ::ext-id (s/with-gen
                         string?
                         #(sg/fixed-string 5)))
(s/def ::address (s/coll-of string?))
(s/def ::country (s/with-gen
                          string?
                          #(sg/fixed-string 3)))
