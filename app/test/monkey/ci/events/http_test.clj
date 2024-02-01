(ns monkey.ci.events.http-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.events
             [async-tests :as ast]
             [core :as c]
             [http :as sut]]
            [monkey.ci.helpers :as h]
            [monkey.ci.web
             [common :as wc]
             [script-api :as sa]]
            [org.httpkit.server :as http]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [ring.util.response :as rur]
            [schema.core :as s]))

(def edn #{"application/edn"})

(def req->events (comp :events wc/req->ctx))

(defn- post-event [req]
  (let [events (req->events req)
        evt (get-in req [:parameters :body])]
    (log/debug "Received event from client:" evt)
    (c/post-events events evt)
    (rur/response "ok")))

(defn event-stream
  "Sets up an event stream for the specified filter."
  [req]
  (let [events (req->events req)
        stream (ms/stream 10)
        listener (fn [evt]
                   @(ms/put! stream evt)
                   nil)
        make-reply (fn [evt]
                     (-> evt
                         (prn-str)
                         (rur/response)
                         (rur/header "Content-Type" "text/event-stream")))
        send-events (fn send-events [ch]
                      (fn send-single-event [evt]
                        (log/debug "Sending event through channel:" evt)
                        (when-not (http/send! ch (make-reply evt) false)
                          (log/warn "Could not send message to channel, stopping event transmission")
                          (ms/close! stream))))
        cleanup (fn [ch]
                  (log/debug "Event bus was closed, stopping event transmission")
                  (c/remove-listener events listener)
                  (http/send! ch (rur/response "") true))]
    (http/as-channel
     req
     {:on-open (fn [ch]
                 (log/debug "Event stream opened:" ch)
                 (md/chain
                  (ms/consume (send-events ch) stream)
                  (fn [_]
                    (cleanup ch)))
                 (c/add-listener events listener))
      :on-close (fn [_ status]
                  (ms/close! stream)
                  (log/debug "Event stream closed with status" status))})))

(def routes [["/events"
              {:post
               {:handler post-event
                :parameters {:body {s/Keyword s/Any}}
                :consumes edn}
               :get
               {:handler event-stream}}]])

(defn make-router
  ([opts routes]
   (ring/router
    routes
    {:data {:middleware wc/default-middleware
            :muuntaja (wc/make-muuntaja)
            :coercion reitit.coercion.schema/coercion
            ::wc/context opts}}))
  ([opts]
   (make-router opts routes)))

(defn make-app [opts]
  (wc/make-app (make-router opts)))

(defn start-server [events]
  (http/run-server (make-app {:events events})
                   {:legacy-return-value? false}))

(def server-url (atom nil))

(use-fixtures :once
  (fn [t]
    ;; Set up fake http server
    (let [events (c/make-sync-events)
          server (start-server events)]
      (reset! server-url (format "http://localhost:%d/events" (http/server-port server)))
      (try
        (t)
        (finally
          (http/server-stop! server))))))

(deftest http-client-events
  (testing "has server url"
    (is (string? @server-url)))
  
  #_(ast/async-tests #(sut/make-http-client @server-url))
  (testing "listeners receive events"
    (let [e (sut/make-http-client @server-url)
          evt {:type ::test-event}
          recv (atom [])
          l (c/no-dispatch
             (partial swap! recv conj))]
      (is (= e (c/add-listener e l)))
      (is (= e (c/post-events e evt)))
      (is (not= :timeout (h/wait-until #(pos? (count @recv)) 500)))
      (is (= [evt] @recv))
      (is (= e (c/remove-listener e l)))
      (is (empty? (reset! recv [])))
      (is (= e (c/post-events e evt)))
      (is (= :timeout (h/wait-until #(pos? (count @recv)) 500))))))

#_(deftest socket-client-events
  (ast/async-tests #(sut/make-socket-client "test.sock")))
