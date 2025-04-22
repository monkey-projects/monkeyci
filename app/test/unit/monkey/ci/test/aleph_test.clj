(ns monkey.ci.test.aleph-test
  "Provides functions for faking Aleph http requests"
  (:require [aleph.http :as http]
            [manifold.deferred :as md]))

(defn map-pred [exp-req req]
  (= exp-req (select-keys req (keys exp-req))))

(defn ->pred [x]
  (cond
    (string? x) (comp (partial = x) (some-fn :url :request-url))
    (fn? x) x
    (map? x) (partial map-pred x)
    :else (throw (ex-info "Don't know how to turn this into a predicate" {:arg x}))))

(defn- find-handler [req entries]
  (letfn [(matches? [[pred _]]
            ((->pred pred) req))]
    (some->> entries
             (partition 2)
             (filter matches?)
             (first)
             (second))))

(defn- maybe-defer [x]
  (cond-> x
    (not (md/deferred? x)) (md/success-deferred)))

(defn- handle-req [h req]
  (if (fn? h)
    (h req)
    h))

(defn request-faker [entries]
  (fn [req]
    (if-let [h (find-handler req entries)]
      (maybe-defer (handle-req h req))
      (throw (ex-info "Request did not match any mocked request" {:request req})))))

(defn with-fake-http*
  "Intercepts Aleph requests and verifies them against the given entries.
   Entries is a list where each even entry is a request, and each odd entry
   is a reply.  A request can be a string or a predicate.  A reply can be
   a fixed map, or a function that takes request and should return a reply."
  [entries f]
  (with-redefs [http/request (request-faker entries)]
    (f)))

(defmacro with-fake-http
  "Convenience macro"
  [entries & body]
  `(with-fake-http* ~entries (fn [] ~@body)))
