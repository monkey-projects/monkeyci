(ns monkey.ci.http-helpers
  "Helper functions to work with http clients"
  (:require [aleph.http :as aleph]
            [manifold.deferred :as md]))

(defn- map->handler [[exp-req rep]]
  (fn [req]
    (if (= exp-req (select-keys req (keys exp-req)))
      (if (fn? rep) (rep req) rep)
      {:status 400 :body "Unexpected request"})))

(defn as-handler [obj]
  (cond
    (fn? obj) obj
    (vector? obj) (map->handler obj)))

(defn with-fake-http* [mocks body-fn]
  (let [handlers (atom (map as-handler mocks))]
    (with-redefs [aleph/request (fn [req]
                                  (md/success-deferred
                                   (if-let [h (first @handlers)]
                                     (do
                                       (swap! handlers rest)
                                       (h req))
                                     {:status 500 :body (str "No handler for request: " req)})))]
      (body-fn))))

(defmacro with-fake-http
  "Sets up fake http requests using the mocks, and invokes the body with it."
  [mocks & body]
  `(with-fake-http* ~mocks (fn [] ~@body)))

