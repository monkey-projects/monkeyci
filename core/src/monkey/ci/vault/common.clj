(ns monkey.ci.vault.common
  (:require [buddy.core
             [codecs :as codecs]
             [crypto :as bcc]
             [nonce :as bcn]])
  (:import java.util.BitSet))

(def iv-size 16)
(def dek-size 32)

;; Key size determines algorithm and iv length
(def algo {:algo :aes-256-gcm})

(defn encrypt
  "Performs AES encryption of the given text"
  [enc-key iv txt]
  (-> (bcc/encrypt (codecs/str->bytes txt)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->b64-str)))

(defn decrypt
  "Performs AES decryption of the given encrypted value"
  [enc-key iv enc]
  (-> (bcc/decrypt (codecs/b64->bytes enc)
                   enc-key
                   iv
                   algo)
      (codecs/bytes->str)))

(defn generate-iv
  "Generates a random initialization vector for AES encryption"
  []
  (bcn/random-nonce iv-size))

(defn generate-key
  "Generates random encryption key"
  []
  (bcn/random-nonce dek-size))

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
