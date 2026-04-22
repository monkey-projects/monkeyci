(ns monkey.ci.vault
  "Functions related to encryption/decryption of data using a vault"
  (:require [buddy.core.nonce :as bcn]
            [monkey.ci.protocols :as p]
            [monkey.ci.vault
             [common :as vc]
             [oci :as vo]]))

(def iv-size vc/iv-size)
(def dek-size vc/dek-size)

;; Fixed key vault, that uses a preconfigured key.  Useful for testing or developing,
;; or when using a temporary data encryption key (DEK).
(defrecord FixedKeyVault [encryption-key]
  p/Vault
  (encrypt [_ iv txt]
    (vc/encrypt encryption-key iv txt))
  
  (decrypt [_ iv enc]
    (vc/decrypt encryption-key iv enc)))

(def generate-key
  "Generates random encryption key"
  vc/generate-key)

(defn make-fixed-key-vault [config]
  (->FixedKeyVault (or (:encryption-key config) (generate-key))))

(defmulti make-vault :type)

(defmethod make-vault :oci [config]
  (vo/make-oci-vault config))

(defmethod make-vault :fixed [config]
  (make-fixed-key-vault config))

(def generate-iv vc/generate-iv)
(def cuid->iv vc/cuid->iv)
