(ns monkey.ci.entities.types
  "Special type treatments for sql"
  (:require [next.jdbc
             [prepare :as p]
             [result-set :as rs]]))

;; (defn uuid->bytes [uuid]
;;   (-> (doto (java.nio.ByteBuffer/wrap (byte-array 16))
;;         (.putLong (.getMostSignificantBits uuid))
;;         (.putLong (.getLeastSignificantBits uuid)))
;;       (.array)))

;; (defn bytes->uuid [arr]
;;   (when (= 16 (count arr))
;;     (let [bb (java.nio.ByteBuffer/wrap arr)]
;;       (java.util.UUID. (.getLong bb) (.getLong bb)))))

;; (extend-protocol p/SettableParameter
;;   java.util.UUID
;;   (set-parameter [uuid stmt idx]
;;     (.setObject stmt idx (uuid->bytes uuid))))

;; (extend-protocol rs/ReadableColumn
;;   (class (byte-array 0))
;;   (read-column-by-index [arr _ _]
;;     arr))
