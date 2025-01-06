(ns monkey.ci.gui.admin.login.db)

(def submitting? ::submitting?)

(defn mark-submitting [db]
  (assoc db submitting? true))

(defn reset-submitting [db]
  (dissoc db submitting?))
