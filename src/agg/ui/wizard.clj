(ns agg.ui.wizard)

(def outcome-options
  [{:route :transparent-overlay
    :label "A transparent overlay for my video editor."
    :description "Create a ProRes 4444 MOV with a transparent background."}
   {:route :finished-video
    :label "A finished video with my overlay already added."
    :description "Choose one Google Drive video and receive a completed video."}])

(def ^:private routes
  #{:transparent-overlay :finished-video})

(def ^:private optional-overlays
  #{:timer :spo2 :watermark})

(def ^:private route-prefix
  {:transparent-overlay [:outcome :overlay-timespan]
   :finished-video [:outcome :source-video :video-recording-clock]})

(def ^:private overlay-step
  {:timer :timer-overlay
   :spo2 :spo2-overlay
   :watermark :watermark-overlay})

(def ^:private overlay-order
  [:timer :spo2 :watermark])

(def ^:private finished-video-request-fields
  [:sourceVideo :outputFormat :fitMode :audioMode])

(def ^:private optional-overlay-request-fields
  [:timer :spo2 :watermark])

(defn initial-state []
  {:active-route nil
   :current-step :outcome
   :visited-steps #{:outcome}
   :shared-input {}
   :decisions {:synchronization-mode nil
               :optional-overlays #{}}
   :route-drafts {:transparent-overlay {}
                  :finished-video {}}
   :optional-overlay-drafts {:timer {}
                             :spo2 {}
                             :watermark {}}
   :validation {:errors {}
                :invalidated-steps #{}}
   :completion {:completed-steps #{}}})

(defn active-steps
  [{:keys [active-route decisions]}]
  (if-not active-route
    [:outcome]
    (let [synchronization-mode (:synchronization-mode decisions)
          selected-overlays (:optional-overlays decisions)]
      (into (get route-prefix active-route)
            (concat
             [:activity-data :synchronization]
             (when (= :manual-anchor synchronization-mode)
               [:matching-moment])
             [:optional-overlays]
             (keep #(when (contains? selected-overlays %)
                      (get overlay-step %))
                   overlay-order)
             [:output-settings :review])))))

(defn step-complete?
  [state step-id]
  (contains? (get-in state [:completion :completed-steps]) step-id))

(defn complete-step
  [state step-id]
  (if (some #{step-id} (active-steps state))
    (-> state
        (update-in [:completion :completed-steps] conj step-id)
        (update-in [:validation :invalidated-steps] disj step-id)
        (update-in [:validation :errors] dissoc step-id))
    state))

(defn invalidate-after
  [state step-id]
  (let [steps (active-steps state)
        step-index (.indexOf steps step-id)]
    (if (neg? step-index)
      state
      (let [downstream (set (subvec steps (inc step-index)))
            completed (get-in state [:completion :completed-steps])
            invalidated (into #{} (filter completed) downstream)
            current-step (:current-step state)]
        (-> state
            (update-in [:completion :completed-steps]
                       #(apply disj % downstream))
            (update :visited-steps #(apply disj % downstream))
            (update-in [:validation :errors]
                       #(apply dissoc % downstream))
            (update-in [:validation :invalidated-steps] into invalidated)
            (cond-> (contains? downstream current-step)
              (assoc :current-step step-id)))))))

(defn choose-route
  [state route]
  (when-not (contains? routes route)
    (throw (ex-info "Unknown wizard route." {:route route})))
  (let [changed? (not= route (:active-route state))
        state (if (and changed? (:active-route state))
                (invalidate-after state :outcome)
                state)]
    (-> state
        (assoc :active-route route
               :current-step :outcome)
        (update :visited-steps conj :outcome)
        (complete-step :outcome))))

(defn choose-synchronization
  [state synchronization-mode]
  (when-not (contains? #{:shared-clock :manual-anchor}
                       synchronization-mode)
    (throw (ex-info "Unknown synchronization mode."
                    {:synchronization-mode synchronization-mode})))
  (let [changed? (not= synchronization-mode
                       (get-in state [:decisions :synchronization-mode]))
        state (if (and changed?
                       (some #{:synchronization} (active-steps state)))
                (invalidate-after state :synchronization)
                state)]
    (assoc-in state [:decisions :synchronization-mode]
              synchronization-mode)))

(defn choose-optional-overlays
  [state selected]
  (let [selected (set selected)]
    (when-not (every? optional-overlays selected)
      (throw (ex-info "Unknown optional overlay."
                      {:selected selected})))
    (let [changed? (not= selected
                         (get-in state [:decisions :optional-overlays]))
          state (if (and changed?
                         (some #{:optional-overlays}
                               (active-steps state)))
                  (invalidate-after state :optional-overlays)
                  state)]
      (assoc-in state [:decisions :optional-overlays] selected))))

(defn set-shared-input
  [state values]
  (update state :shared-input merge values))

(defn set-route-draft
  [state route values]
  (when-not (contains? routes route)
    (throw (ex-info "Unknown wizard route." {:route route})))
  (assoc-in state [:route-drafts route] values))

(defn set-overlay-draft
  [state overlay values]
  (when-not (contains? optional-overlays overlay)
    (throw (ex-info "Unknown optional overlay." {:overlay overlay})))
  (assoc-in state [:optional-overlay-drafts overlay] values))

(defn project-render-request
  [{:keys [active-route shared-input route-drafts
           optional-overlay-drafts decisions]}]
  (let [synchronization-mode (:synchronization-mode decisions)
        selected-overlays (:optional-overlays decisions)
        active-input (merge shared-input (get route-drafts active-route))
        active-input (apply dissoc active-input
                            optional-overlay-request-fields)
        active-input (if (= :transparent-overlay active-route)
                       (apply dissoc active-input
                              finished-video-request-fields)
                       active-input)]
    (cond-> active-input
      synchronization-mode
      (assoc :synchronizationMode (name synchronization-mode))

      (contains? selected-overlays :timer)
      (assoc :timer (:timer optional-overlay-drafts))

      (contains? selected-overlays :spo2)
      (assoc :spo2 (:spo2 optional-overlay-drafts))

      (contains? selected-overlays :watermark)
      (assoc :watermark (:watermark optional-overlay-drafts)))))

(defn navigation-eligible?
  [state target-step]
  (let [steps (active-steps state)
        current-index (.indexOf steps (:current-step state))
        target-index (.indexOf steps target-step)]
    (and (not (neg? target-index))
         (or (= target-step (:current-step state))
             (contains? (:visited-steps state) target-step)
             (and (not (neg? current-index))
                  (< target-index current-index))
             (and (= target-index (inc current-index))
                  (step-complete? state (:current-step state)))))))

(defn go-to-step
  [state target-step]
  (if (navigation-eligible? state target-step)
    (-> state
        (assoc :current-step target-step)
        (update :visited-steps conj target-step))
    state))

(defn complete?
  [state]
  (let [required-steps (remove #{:review} (active-steps state))]
    (and (:active-route state)
         (every? #(step-complete? state %) required-steps))))

(defn submit-ready-request
  [state]
  (when (complete? state)
    (project-render-request state)))

(defn browser-initial-state []
  {"activeRoute" nil
   "currentStep" "outcome"
   "visitedStepIds" ["outcome"]
   "sharedInput" {}
   "decisions" {"synchronizationMode" nil
                "optionalOverlays" []}
   "routeDrafts" {"transparent-overlay" {}
                  "finished-video" {}}
   "optionalOverlayDrafts" {"timer" {}
                            "spo2" {}
                            "watermark" {}}
   "validation" {"errors" {}
                 "invalidatedStepIds" []}
   "completion" {"completedStepIds" []
                 "complete" false}
   "renderRequest" nil})

(defn browser-step-model []
  {"routePrefixes"
   {"transparent-overlay" (mapv name
                                (:transparent-overlay route-prefix))
    "finished-video" (mapv name (:finished-video route-prefix))}
   "overlaySteps"
   (into {} (map (fn [[overlay step]]
                   [(name overlay) (name step)]))
         overlay-step)
   "overlayOrder" (mapv name overlay-order)
   "tail" ["output-settings" "review"]})
