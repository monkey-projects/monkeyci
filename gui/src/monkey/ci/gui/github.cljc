(ns monkey.ci.gui.github
  "Functions for invoking the github api"
  (:require #?@(:node []
                :cljs [[ajax.core :as ajax]])
            [camel-snake-kebab.core :as csk]
            [clojure.walk :as w]
            [re-frame.core :as rf]))

(def format #?@(:node []
                :cljs [(ajax/json-response-format)]))

(def api-version "2022-11-28")

(defn- convert-keys
  "Since github uses snake casing for its keys, we convert them to clojure-style
   kebab-case keywords here."
  [obj]
  (w/postwalk (fn [x]
                (if (map-entry? x)
                  [(csk/->kebab-case-keyword (first x)) (second x)]
                  x))
              obj))

(defn api-url [path]
  (str "https://api.github.com" path))

(defn api-request [db {:keys [path] :as opts}]
  (let [token (or (:token opts) (:github/token db))]
    ;; TODO Handle pagination (see the `link` header)
    (cond-> (-> opts
                (assoc :response-format format
                       ;; Route the response to convert map keys
                       :on-success [:github/process-response (:on-success opts)])
                (update :headers
                        assoc
                        "Authorization" (str "Bearer " token)
                        "X-GitHub-Api-Version" api-version)
                (dissoc path))
      path (assoc :uri (api-url path)))))

(rf/reg-event-fx
 :github/process-response
 (fn [_ [_ evt resp]]
   {:dispatch (conj evt (convert-keys resp))}))
