(ns monkey.ci.test.vault
  (:require [monkey.ci
             [protocols :as p]
             [vault :as v]]))

(defrecord DummyVault [enc-fn dec-fn]
  p/Vault
  (encrypt [_ _ v]
    (enc-fn v))

  (decrypt [_ _ v]
    (dec-fn v)))

(defn dummy-vault
  ([]
   (->DummyVault identity identity))
  ([enc-fn dec-fn]
   (->DummyVault enc-fn dec-fn)))

(def fake-vault dummy-vault)

(defmethod v/make-vault :noop [_]
  (fake-vault))
