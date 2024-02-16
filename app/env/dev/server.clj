(ns server
  (:require [clojure.spec.alpha :as s]
            [config :as co]
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
  (swap! server (fn [s]
                  (when s
                    (s))
                  nil)))

(defn validate-config [c]
  (when-not (s/valid? ::mcs/app-config c)
    (s/explain ::mcs/app-config c))
  c)

(defn start-server []
  (stop-server)
  (let [rt (-> @co/global-config
               (merge {:dev-mode true
                       :http {:port 3000}
                       :work-dir (u/abs-path "tmp")
                       :checkout-base-dir (u/abs-path "tmp/checkout")
                       :ssh-keys-dir (u/abs-path "tmp/ssh-keys")})
               (config/normalize-config {} {})
               (validate-config)
               (rt/config->runtime))]
    (reset! server ((:http rt) rt)))
  nil)

(defn get-server-port []
  (some-> server
          deref
          :http
          :server
          http/server-port))

(defn private-key []
  (some-> @server :context :jwk :priv))

(defn generate-jwt [uid]
  (-> {:sub uid}
      (auth/augment-payload)
      (auth/sign-jwt (private-key))))
