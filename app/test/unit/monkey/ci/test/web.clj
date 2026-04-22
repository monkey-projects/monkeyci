(ns monkey.ci.test.web
  (:require [clojure.string :as cs]
            [monkey.ci.test
             [helpers :as h]
             [json :as j]]
            [monkey.ci.web.common :as wc]
            [ring.mock.request :as mock]))

(defn json-request
  "Creates a Ring mock request with given object as json body"
  [method path body]
  (-> (mock/request method path)
      (mock/body (j/to-json body))
      (mock/header :content-type "application/json")))

(defn ->match-data [obj]
  {:reitit.core/match
   {:data obj}})

(defn ->req
  "Takes a runtime and creates a request object from it that can be passed to
   an api handler function."
  [rt]
  (->match-data
   {:monkey.ci.web.common/runtime (wc/->RuntimeWrapper rt)}))

(defn with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn with-query-param [r k v]
  (assoc-in r [:parameters :query k] v))

(defn with-path-params [r p]
  (update-in r [:parameters :path] merge p))

(defn with-body [r v]
  (assoc-in r [:parameters :body] v))

(defn with-identity [r id]
  (assoc r :identity id))

(defn reply->json
  "Takes the reply body and parses it from json"
  [rep]
  (try
    (some-> rep
            :body
            slurp
            j/parse-json)
    (catch Exception ex
      (throw (ex-info "Unable to parse json reply" {:cause ex
                                                    :reply rep})))))

(defn parse-token-payload
  "Given an API JWT token, extracts and parses the payload.  This does
   not verify the signature."
  [token]
  (-> token
      (cs/split #"\.")
      (second)
      (h/base64->)
      (j/parse-json)))

