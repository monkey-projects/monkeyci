(ns monkey.ci.pem
  (:import [org.bouncycastle.util.io.pem PemObject PemWriter]
           java.io.StringWriter)
  (:require [buddy.core.keys.pem :as pem]))

(defn private-key->pem
  "Writes private key to PEM format"
  [pk]
  (let [po (PemObject. "PRIVATE KEY" (.getEncoded pk))
        sw (StringWriter.)]
    (with-open [pw (PemWriter. sw)]
      (.writeObject pw po))
    (.toString sw)))

(defn pem->private-key
  "Load private key from pem string"
  [str]
  (with-open [r (java.io.StringReader. str)]
    (pem/read-privkey r nil)))

(def private-key? (partial instance? java.security.PrivateKey))
