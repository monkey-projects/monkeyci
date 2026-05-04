(ns monkey.ci.vault.fixed
  "Fixed key vault, that uses a preconfigured key.  Useful for testing or developing,
   or when using a temporary data encryption key (DEK)."
  (:require [monkey.ci.protocols :as p]
            [monkey.ci.vault.common :as vc]))

(defrecord FixedKeyVault [encryption-key]
  p/Vault
  (encrypt [_ iv txt]
    (vc/encrypt encryption-key iv txt))
  
  (decrypt [_ iv enc]
    (vc/decrypt encryption-key iv enc)))

(defn make-fixed-key-vault [config]
  (->FixedKeyVault (or (:encryption-key config) (vc/generate-key))))
