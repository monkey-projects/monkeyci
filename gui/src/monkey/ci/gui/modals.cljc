(ns monkey.ci.gui.modals
  "Functions for displaying modal dialogs")

(defn modal-dismiss-btn [lbl]
  [:button.btn.btn-secondary {:type :button
                              :data-bs-dismiss "modal"}
   lbl])

(defn modal
  "Renders a modal box with a close button by default"
  [id title contents & [footer]]
  [:div.modal.fade
   {:id id
    :role :dialog
    :tab-index -1}
   [:div.modal-dialog
    {:role :document}
    [:div.modal-content
     [:div.modal-header
      [:div.modal-title title]
      [:button.btn-close {:type :button
                          :data-bs-dismiss "modal"
                          :aria-label "Close"}]]
     [:div.modal-body
      contents]
     [:div.modal-footer
      (or footer
          [modal-dismiss-btn "Close"])]]]])
