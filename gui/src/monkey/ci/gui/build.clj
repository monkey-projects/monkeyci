(ns monkey.ci.gui.build
  "Builders for the index pages, which are then rendered into html pages."
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
   (into
    [:body
     [:main {:role :main}
      [:div.overflow-hidden
       [:div#root.d-flex.flex-column.min-vh-100
        [:div.container
         (t/generic-header config)
         [:content.flex-fill "Loading..."]]
        [:div.mt-auto
         (cc/footer config)]]]]
     ;; Must be loaded before bootstrap to enable dropdowns
     (cc/script (cc/script-url config "popper.min.js"))
     (cc/script (cc/script-url config "bootstrap.min.js"))
     (cc/script (cc/script-url config "theme.min.js"))]
    (map #(cc/script (format "/js/%s.js" (name %1)))
         (:modules config)))])

(defn gen-idx [{:keys [output] :as conf}]
  (let [html (str (h/html (base-page conf)))]
    (if output
      (do
        (.mkdirs (.getParentFile (io/file output)))
        (spit output html))
      (println html))))

(defn gen-main
  "Generates the index.html file for the main website.
   If no output file is specified, prints to stdout."
  [conf]
  (gen-idx (assoc conf :modules ["common" "main"])))

(defn gen-admin
  "Generates the index.html file for the admin page."
  [conf]
  (gen-idx (assoc conf :modules ["common" "admin"])))
