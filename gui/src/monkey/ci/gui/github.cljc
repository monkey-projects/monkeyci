(ns monkey.ci.gui.github
  "Functions for invoking the github api"
  (:require [ajax.core :as ajax]
            [camel-snake-kebab.core :as csk]
            [clojure.walk :as w]
            [re-frame.core :as rf]))

(defn- convert-keys
  "Since github uses snake casing for its keys, we convert them to clojure-style
   kebab-case keywords here."
  [obj]
  (w/postwalk (fn [x]
                (if (map-entry? x)
                  [(csk/->kebab-case-keyword (first x)) (second x)]
                  x))
              obj))

(defn api-request [db {:keys [path] :as opts}]
  (let [token (or (:token opts) (:github/token db))]
    (cond-> (-> opts
                (assoc :response-format (ajax/json-response-format)
                       ;; Route the response to convert map keys
                       :on-success [:github/process-response (:on-success opts)])
                (assoc-in [:headers "Authorization"] (str "Bearer " token))
                (dissoc path))
      path (assoc :uri (str "https://api.github.com" path)))))

(rf/reg-event-fx
 :github/process-response
 (fn [_ [_ evt resp]]
   {:dispatch (conj evt (convert-keys resp))}))
