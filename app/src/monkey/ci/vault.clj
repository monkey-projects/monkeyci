(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:import java.util.BitSet))

(def iv-size 16)
(def dek-size 32)

(defn cuid->iv
  "Generates an iv (initialization vector) from the given cuid.  This is in turn
   used for symmetric encryption/decryption."
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
