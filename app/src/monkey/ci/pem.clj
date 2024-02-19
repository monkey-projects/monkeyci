(ns monkey.ci.pem
  (:import [org.bouncycastle.util.io.pem PemObject PemWriter]
           java.io.StringWriter))

(defn private-key->pem
  "Writes private key to PEM format"
  [pk]
  (let [po (PemObject. "PRIVATE KEY" (.getEncoded pk))
        sw (StringWriter.)]
    (with-open [pw (PemWriter. sw)]
      (.writeObject pw po))
    (.toString sw)))
