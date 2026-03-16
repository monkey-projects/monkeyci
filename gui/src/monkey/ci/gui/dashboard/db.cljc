(ns monkey.ci.gui.dashboard.db)

(def get-assets-url ::assets-url)

(defn set-assets-url [db u]
  (assoc db ::assets-url u))

(defn set-metrics [db id v]
  (assoc-in db [::metrics id] v))

(defn get-metrics [db id]
  (get-in db [::metrics id]))

(def get-active-builds ::active-builds)

(defn set-active-builds [db a]
  (assoc db ::active-builds a))
