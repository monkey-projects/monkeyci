(ns monkey.ci.edn
  "Functionality for serializing objects to edn and deserializing them back."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as cw]
            [monkey.ci.pem :as pem]))

(def pk-sym 'com.monkeyci/PrivateKey)

(defmethod print-method java.security.PrivateKey [pk w]
  (.write w "#")
  (.write w (str pk-sym))
  (.write w " ")
  (.write w (pr-str (pem/private-key->pem pk))))

(defn- read-pk [edn]
  (pem/pem->private-key edn))

(defn ->edn [x]
  (pr-str x))

(defn- ->reader [x]
  (if (string? x)
    (java.io.StringReader. x)
    (io/reader x)))

(def default-opts {:readers {pk-sym read-pk}})

(defn edn-> [edn & [opts]]
  (let [opts (merge opts default-opts)]
    (if (instance? java.io.PushbackReader edn)
      (edn/read opts edn)
      (with-open [r (->reader edn)]
        (edn/read opts (java.io.PushbackReader. r))))))
