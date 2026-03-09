(ns clojars
  "Functions for interacting with Clojars API"
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure
             [edn :as edn]
             [string :as cs]]
            [clojure.java.io :as io]))

(def base-url "https://clojars.org/api")

(defn latest-version
  "Fetches latest version number for the given artifact from clojars"
  ([group artifact]
   (->> @(http/get (cs/join "/" [base-url "artifacts" group artifact])
                   {:headers {"accept" "application/edn"}})
        :body
        (bs/to-string)
        (edn/read-string)
        :latest_version))
  ([artifact]
   (latest-version "com.monkeyci" artifact)))

(defn extract-lib
  "Extracts lib info from deps file"
  [deps]
  (with-open [f (io/reader (io/file deps))]
    (when-let [s (-> (edn/read (java.io.PushbackReader. f))
                     (get-in [:aliases :jar :exec-args :lib]))]
      [(namespace s) (name s)])))
