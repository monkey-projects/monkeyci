(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:require [buddy.core.nonce :as bcn]
            [monkey.ci.protocols :as p]
            [monkey.ci.vault
             [common :as vc]
             [oci :as vo]])
  (:import java.util.BitSet))

(def iv-size 16)

;; Fixed key vault, that uses a preconfigured key.  Useful for testing or developing,
;; or when using a temporary data encryption key (DEK).
(defrecord FixedKeyVault [encryption-key]
  p/Vault
  (encrypt [_ iv txt]
    (vc/encrypt encryption-key iv txt))
  
  (decrypt [_ iv enc]
    (vc/decrypt encryption-key iv enc)))

(defn generate-key
  "Generates random encryption key"
  []
  (bcn/random-nonce 32))

(defn make-fixed-key-vault [config]
  (->FixedKeyVault (or (:encryption-key config) (generate-key))))

(defmulti make-vault :type)

(defmethod make-vault :oci [config]
  (vo/make-oci-vault config))

(defmethod make-vault :fixed [config]
  (make-fixed-key-vault config))

(defn generate-iv
  "Generates a random initialization vector for AES encryption"
  []
  (bcn/random-nonce iv-size))

(defn cuid->iv
  "Generates an iv from the given cuid"
  [cuid]
  ;; Take the last 6 bits of each char and add them to a bitset, then take
  ;; this result as the iv.
  (let [n (int (Math/ceil (* 8 (/ iv-size (count cuid)))))
        buf (.getBytes cuid)]
    (letfn [(set-bits [bs offs]
              (let [b (aget buf offs)]
                (reduce (fn [bs idx]
                          (doto bs
                            (.set (+ (* offs n) idx) (bit-test b (- (dec n) idx)))))
                        bs
                        (range n))))]
      (->> (range (count buf))
           (reduce set-bits
                   (BitSet.))
           (.toByteArray)
           (byte-array iv-size)))))
