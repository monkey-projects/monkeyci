(ns monkey.ci.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]]
            [monkey.ci.spec.gen :as sg]))

(def ts-gen (gen/choose (jt/to-millis-from-epoch (jt/offset-date-time 2020 1 1))
                        (jt/to-millis-from-epoch (jt/offset-date-time 2030 1 1))))

(def id? string?)
(def ts? (s/with-gen int?
           (constantly ts-gen)))
(def path? string?)
(def cuid? cuid/cuid?)
(def date? (s/with-gen
             int?
             #(gen/bind ts-gen
                        (fn [epoch]
                          ;; Convert timestamp to midnight
                          (gen/return
                           (-> (jt/instant epoch)
                               (jt/local-date (jt/zone-id))
                               (jt/zoned-date-time (jt/local-time 0 0) (jt/zone-id))
                               (jt/to-millis-from-epoch)))))))

(def url-regex #"^(?:([A-Za-z]+):)(\/{0,3})([0-9.\-A-Za-z]+)(?::(\d+))?(?:\/([^?#]*))?(?:\?([^#]*))?(?:#(.*))?$")

#_(defn url? [x]
  (and (string? x) (re-matches url-regex x)))

(def url? string?)

;; Not using fmap because it tends to throw "no gen" exceptions
(s/def ::cuid (s/with-gen
                cuid/cuid?
                #(gen/return (cuid/random-cuid))))

;; Just using string to avoid "no gen" errors
(s/def ::url string? #_(s/with-gen url?
                         #(gen/return "http://test-url")))

(s/def ::storage (partial satisfies? p/Storage))
(s/def ::blob-store (partial satisfies? p/BlobStore))
(s/def ::artifacts ::blob-store)
(s/def ::cache ::blob-store)
(s/def ::workspace (partial satisfies? p/Workspace))
(s/def ::params (partial satisfies? p/BuildParams))
(s/def ::mailman #(contains? % :broker))

(s/def ::timeout (s/and int? pos?))

(s/def :credit/type #{:subscription :user})

;; Bitbucket specific
(s/def :bb/workspace string?)
(s/def :bb/repo-slug string?)

;; Invoice numbers are strings, but should only consist of digits
(s/def :invoice/invoice-nr (s/with-gen
                             (partial re-matches #"\d+")
                             #(gen/return (str (int (* 100000 (rand)))))))
(s/def :invoice/net-amount (s/and decimal? pos?))
(s/def :invoice/vat-perc (s/and decimal? pos?))
(s/def :invoice/currency string?)
(s/def :invoice/kind #{:invoice :creditnote})
(s/def :invoice/date date?)

(s/def :queued-task/creation-time ts?)
(s/def :queued-task/task map?)

(def token-size "Api token size in chars" 64)

(s/def ::token (s/with-gen
                 string?
                 #(sg/fixed-string token-size)))

(s/def :mailing/subject string?)
(s/def :mailing/text-body string?)
(s/def :mailing/html-body string?)
(s/def :mailing/to-users boolean?)
(s/def :mailing/to-subscribers boolean?)
