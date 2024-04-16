(ns git
  (:require [monkey.ci.git :as g]))

(defn clone-private [url dir pk]
  (let [priv (slurp pk)
        pub (slurp (str pk ".pub"))
        opts {:url url
              :dir (str dir "/checkout")
              :ssh-keys [{:private-key priv
                          :public-key pub}]
              :ssh-keys-dir (str dir "/keys")}]
    (println "Cloning from" url "using private key" pk)
    (g/clone opts)))
