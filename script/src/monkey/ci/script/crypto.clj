(ns monkey.ci.script.crypto
  "Cryptography functionality for scripts"
  (:require [taoensso.tempel :as tempel]))

(defn encrypt [v iv k]
  (tempel/encrypt-with-symmetric-key v k {:ba-aad iv}))

(defn decrypt [v iv k]
  (let [{:keys [ba-content ba-aad]} (tempel/decrypt-with-symmetric-key v k {:return :map})]
    (when (not= iv ba-aad)
      (throw (ex-info "Invalid IV" {:iv iv}))
      ba-content)))
