(ns server
  (:require [aleph.http :as aleph]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [config :as co]
            [manifold.stream :as ms]
            [monkey.ci
             [config :as config]
             [core :as c]
             [runtime :as rt]
             [spec :as mcs]
             [utils :as u]]
            [monkey.ci.web.auth :as auth]
            [org.httpkit.server :as http]))

(defonce server (atom nil))

(defn stop-server []
  (swap! server (fn [{:keys [server rt]}]
                  (when server
                    (server))
                  (when rt
                    (rt/stop rt))
                  nil)))

(defn validate-config [c]
  (when-not (s/valid? ::mcs/app-config c)
    (s/explain ::mcs/app-config c))
  c)

(defn start-server []
  (stop-server)
  (let [rt (-> (merge {:dev-mode true
                       :http {:port 3000}
                       :work-dir (u/abs-path "tmp")}
                      @co/global-config)
               (config/normalize-config {} {})
               (validate-config)
               (rt/config->runtime)
               (rt/start))
        start-server (:http rt)]
    (reset! server {:rt rt
                    :server (start-server rt)}))
  nil)

(defn get-server-port []
  (some-> server
          deref
          :http
          :server
          http/server-port))

(defn private-key []
  (some-> @server :rt :jwk :priv))

(defn generate-jwt [uid]
  (-> {:sub uid}
      (auth/augment-payload)
      (auth/sign-jwt (private-key))))

(defn post-event
  "Posts an event using the runtime in the current server config"
  [evt]
  (if-let [rt (some-> server deref :rt)]
    (rt/post-events rt evt)
    (throw (ex-info "No server running" @server))))

(defn sse-handler [req]
  (let [stream (ms/periodically 2000
                                #(format "data: %s\n\n"
                                         (pr-str {:type :test
                                                  :message "test-event"
                                                  :time (System/currentTimeMillis)})))]
    (ms/on-drained stream #(log/info "Event stream closed"))
    (log/info "New event stream opened")
    {:status 200
     :headers {"content-type" "text/event-stream"
               "access-control-allow-origin" "*"}
     :body stream}))

(defn sse-server [port]
  (aleph/start-server sse-handler {:port port}))
