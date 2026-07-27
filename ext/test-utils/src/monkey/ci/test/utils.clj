(ns monkey.ci.test.utils)

(defn- make-memory-storage []
  ;; Avoid direct dependency
  ((requiring-resolve 'monkey.ci.storage/make-memory-storage)))

(defn with-memory-store-fn [f]
  (f (make-memory-storage)))

(defmacro with-memory-store [s & body]
  `(with-memory-store-fn
     (fn [~s]
       ~@body)))

(defn generate-private-key
  "Generates a new RSA keypair"
  []
  (-> (doto (java.security.KeyPairGenerator/getInstance "RSA")
        (.initialize 2048))
      (.generateKeyPair)
      (.getPrivate)))

(defn base64->
  "Converts from base64"
  [x]
  (when x
    (String.
     (.. (java.util.Base64/getDecoder)
         (decode x)))))
