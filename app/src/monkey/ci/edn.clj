(ns monkey.ci.edn
  "Functionality for serializing objects to edn and deserializing them back."
  (:require [aero.core :as ac]
            [clj-commons.byte-streams :as bs]
            [clojure.edn :as edn]
            [clojure.walk :as cw]
            [monkey.ci
             [pem :as pem]
             [version :as v]]))

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
    (bs/to-reader x)))

(def default-opts {:readers {pk-sym read-pk}})

(defn edn-> [edn & [opts]]
  (let [opts (merge opts default-opts)]
    (if (instance? java.io.PushbackReader edn)
      (edn/read opts edn)
      (with-open [r (->reader edn)]
        (edn/read opts (java.io.PushbackReader. r))))))

;; Also specify an Aero reader, so we can read private keys from config
(defmethod ac/reader pk-sym
  [_ _ value]
  (read-pk value))

(defmethod ac/reader 'version
  [_ _ _]
  (v/version))
