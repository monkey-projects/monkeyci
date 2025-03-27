(ns monkey.ci.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [java-time.api :as jt]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]]))

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

(defn url? [x]
  (and (string? x) (re-matches url-regex x)))

(s/def ::cuid (s/with-gen
                cuid/cuid?
                #(gen/fmap clojure.string/join
                           (gen/vector (gen/elements cuid/cuid-chars) cuid/cuid-length))))

(s/def ::url (s/with-gen url?
               #(gen/fmap (partial format "http://%s") (gen/string-alphanumeric))))

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

(s/def :invoice/invoice-nr string?)
(s/def :invoice/net-amount (s/and decimal? pos?))
(s/def :invoice/vat-perc (s/and decimal? pos?))
(s/def :invoice/currency string?)
(s/def :invoice/kind #{:invoice :creditnote})
(s/def :invoice/date date?)

(s/def :retry-task/creation-time ts?)
(s/def :retry-task/details map?)
