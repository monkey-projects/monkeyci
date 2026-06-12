(ns monkey.ci.app.edn
  "Functionality for serializing objects to edn and deserializing them back."
  (:require [aero.core :as ac]
            [buddy.core.codecs :as codecs]
            [clj-commons.byte-streams :as bs]
            [clojure.walk :as cw]
            [monkey.ci
             [edn :as edn]
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

(def regex-sym 'regex)

(defmethod print-method java.util.regex.Pattern [p w]
  (.write w "#")
  (.write w (str regex-sym))
  (.write w " ")
  (.write w (pr-str (str p))))

(defn- read-regex [edn]
  (re-pattern edn))

(defn ->edn [x]
  (pr-str x))

(defn- ->reader [x]
  (if (string? x)
    (java.io.StringReader. x)
    (bs/to-reader x)))

(def default-opts {:readers {pk-sym read-pk
                             regex-sym read-regex}})

(defn edn-> [edn & [opts]]
  (let [opts (merge opts default-opts)]
    (edn/edn-> edn opts)))

;; Also specify an Aero reader, so we can read private keys from config
(defmethod ac/reader pk-sym
  [_ _ value]
  (read-pk value))

(defmethod ac/reader 'version
  [_ _ _]
  (v/version))

(defmethod ac/reader 'aes-key
  [_ _ value]
  (codecs/b64->bytes value))
