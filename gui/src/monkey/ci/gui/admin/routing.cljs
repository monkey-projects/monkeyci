(ns monkey.ci.gui.admin.routing
  "Admin specific routing"
  (:require [monkey.ci.gui.routing :as r]))

(def admin-router
  (r/make-router
   [["/" :admin/root]
    ["/login" {:name :admin/login
               :public? true}]
    ["/credits" :admin/credits]
    ["/credits/:org-id" :admin/org-credits]
    ["/builds/clean" :admin/clean-builds]
    ["/forget" :admin/forget-users]
    ["/invoicing" :admin/invoicing]
    ["/invoicing/:org-id" :admin/org-invoices]
    ["/invoicing/:org-id/new" :admin/invoice-new]
    ["/mailings" :admin/mailings]
    ["/mailings/new" :admin/new-mailing]
    ["/mailings/edit/:mailing-id" :admin/mailing-edit]]))

(defn start! []
  (r/start-router admin-router))
