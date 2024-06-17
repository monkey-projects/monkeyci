(ns monkey.ci.gui.apis.common
  (:require #?@(:node []
                :cljs [[ajax.core :as ajax]])
            [camel-snake-kebab.core :as csk]
            [clojure.walk :as w]
            [re-frame.core :as rf]))

(def format #?@(:node []
                :cljs [(ajax/json-response-format)]))

(defn- convert-keys
  "Since github uses snake casing for its keys, we convert them to clojure-style
   kebab-case keywords here."
  [obj]
  (w/postwalk (fn [x]
                (if (map-entry? x)
                  [(csk/->kebab-case-keyword (first x)) (second x)]
                  x))
              obj))

(defn api-request
  "Builds an xhrio request map to access an external api."
  [db {:keys [token] :as opts}]
  (-> opts
      (assoc :response-format format
             ;; Route the response to convert map keys
             :on-success [:ext-api/process-response (:on-success opts)])
      (assoc-in [:headers "Authorization"] (str "Bearer " token))))

(rf/reg-event-fx
 :ext-api/process-response
 (fn [_ [_ evt resp]]
   {:dispatch (conj evt (convert-keys resp))}))
