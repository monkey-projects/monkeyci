(ns monkey.ci.gui.build
  (:require [clojure.java.io :as io]
            [hiccup2.core :as h]
            [monkey.ci.gui.template :as t]
            [monkey.ci.template.components :as cc]))

(defn head [config]
  (conj (cc/head config)
        (cc/script "/conf/config.js")))

(defn- base-page [config]
  [:html
   (head config)
   [:body
    [:main {:role :main}
     [:div.overflow-hidden
      [:div#root.d-flex.flex-column.min-vh-100
       [:div.container
        (t/generic-header config)
        [:content.flex-fill "Loading..."]]
       [:div.mt-auto
        (cc/footer config)]]]]
    (cc/script (cc/script-url config "vendor.min.js"))
    (cc/script (cc/script-url config "theme.min.js"))
    (cc/script "/js/main.js")]])

(defn gen-idx
  "Generates the index.html file that will be included in the resulting website.
   If no output file is specified, prints to stdout."
  [{:keys [output] :as conf}]
  (let [html (str (h/html (base-page conf)))]
    (if output
      (do
        (.mkdirs (.getParentFile (io/file output)))
        (spit output html))
      (println html))))
