(ns monkey.ci.events.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging :as log]
            [monkey.ci.events
             [core :as c]
             [http :as sut]
             [manifold :as em]]
            [monkey.ci.helpers :as h]
            [monkey.ci.web.common :as wc]
            [org.httpkit.server :as http]
            [reitit.coercion.schema]
            [reitit.ring :as ring]
            [ring.util.response :as rur]
            [schema.core :as s]))

(def edn #{"application/edn"})

(def req->events (comp :events wc/req->ctx))

(defn- recv-events [req]
  (let [events (req->events req)
        p (get-in req [:parameters :body])]
    (log/debug "Received events from client:" p)
    (swap! events conj p)
    (rur/response "ok")))

(def routes [["/events"
              {:post
               {:handler recv-events
                :parameters {:body [{s/Keyword s/Any}]}
                :consumes edn}}]])

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
                   {:legacy-return-value? false
                    :port 3001}))

(defn with-server [events f]
  (let [server (start-server events)
        url (format "http://localhost:%d/events" (http/server-port server))]
    (try
      (f url)
      (finally
        (log/debug "Stopping server")
        (http/server-stop! server)))))

(defn- validate-events-received [act & [exp]]
  (let [events (atom [])]
    (with-server events
      (fn [url]
        (let [evt (sut/make-http-client url)]
          (is (some? (c/post-events evt act)))
          (is (not= :timeout (h/wait-until #(pos? (count @events)) 1000)))
          (is (= [(or exp act)] @events)))))))

(deftest http-client-events
  (testing "posts single event to remote http server"
    (let [e {:type ::test-event}]
      (validate-events-received e [e])))

  (testing "posts multiple events to remote http server"
    (validate-events-received [{:type ::first}
                               {:type ::second}])))

(deftest make-events
  (testing "can make http events"
    (is (some? (c/make-events {:events
                               {:type :http
                                :url "http://test"}})))))
