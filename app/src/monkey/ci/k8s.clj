(ns monkey.ci.k8s
  "Kubernetes functionality, used by build and container runners."
  (:require [clojure.math :as m]
            #_[kubernetes-api.core :as k8s]))

(def mem-regex #"^(\d+)(G|M|K|Gi|Mi|Ki)$")

(defn parse-mem
  "Parses a kubernetes style memory amount to gbs"
  [s]
  (when-let [[_ n u] (re-matches mem-regex s)]
    ;; TODO when i is specified, it's a power of 2
    (let [dpu {"G"  identity
               "M"  #(/ % 1e3)
               "K"  #(/ % 1e6)
               "Gi" identity
               "Mi" #(/ % (m/pow 2 10))
               "Ki" #(/ % (m/pow 2 20))}]
      (int ((get dpu u) (Integer/parseInt n))))))

#_(defn make-client [url token]
  (k8s/client url {:token token :insecure? true}))

#_(defn list-nodes
  "Lists all kubernetes nodes."
  [cl]
  (->> (k8s/invoke cl {:kind :Node
                       :action :list})
       :items))
