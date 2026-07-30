(ns agg.ui.wizard
  (:import (java.time Instant ZoneId)))

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
  {:transparent-overlay [:outcome :activity-data :output-timespan]
   :finished-video [:outcome :source-video :activity-data
                    :synchronization-decision]})

(def ^:private synchronization-step
  {:shared-clock :confirm-video-clock
   :manual-anchor :matching-moment})

(def ^:private overlay-step
  {:timer :timer-overlay
   :spo2 :spo2-overlay
   :watermark :watermark-overlay})

(def ^:private overlay-order
  [:timer :spo2 :watermark])

(def ^:private step-copy
  {:outcome
   {:title "What would you like to make?"
    :description "Choose the kind of video workflow you want to complete."}
   :source-video
   {:title "Choose your source video"
    :description "Pick one Google Drive video. You can verify or replace it before continuing."}
   :activity-data
   {:title "Choose your heart-rate file"
    :description "Provide the activity data used to draw the heart-rate trace."}
   :synchronization-decision
   {:title "Compare the camera and activity clocks"
    :description "Choose whether the source video already follows the activity-device clock or whether you need to match one moment."}
   :confirm-video-clock
   {:title "Confirm the video recording clock"
    :description "Check or correct the recording start and timezone before setting the output range."}
   :matching-moment
   {:title "Match one moment"
    :description "Move to one recognizable source-video frame and enter the same instant from the activity data."}
   :output-timespan
   {:title "Choose the output timespan"
    :description "Set the display timezone plus the start and end instants for the output range."}
   :optional-overlays
   {:title "Choose optional overlays"
    :description "Add only the supporting overlays you want."}
   :timer-overlay
   {:title "Set the elapsed timer"
    :description "Choose timer boundaries inside the output range."}
   :spo2-overlay
   {:title "Choose OxiWear SpO2 data"
    :description "Provide the optional oxygen-saturation CSV."}
   :watermark-overlay
   {:title "Choose a PNG watermark"
    :description "Provide the optional watermark image."}
   :output-settings
   {:title "Choose output settings"
    :description "Choose the preset and settings for the active output route."}
   :review
   {:title "Review and create"
    :description "Inspect the active request before previewing or creating the output."}})

(def ^:private finished-video-request-fields
  [:sourceVideo :outputFormat :fitMode :audioMode])

(def ^:private optional-overlay-request-fields
  [:timer :spo2 :watermark])

(def ^:private internal-route-fields
  [:manualSync])

(defn derive-manual-sync
  [{:keys [source-seconds activity-instant time-zone] :as primary}]
  (when-not (and (number? source-seconds)
                 (Double/isFinite (double source-seconds))
                 (not (neg? source-seconds))
                 (< (Math/abs
                     (- (* (double source-seconds) 25.0)
                        (Math/rint (* (double source-seconds) 25.0))))
                    0.0001))
    (throw (ex-info "Manual synchronization source time must be a non-negative 25 fps frame."
                    {:source-seconds source-seconds})))
  (let [activity
        (try
          (Instant/parse activity-instant)
          (catch Exception _
            (throw (ex-info "Manual synchronization activity time must be an ISO-8601 instant."
                            {:activity-instant activity-instant}))))
        zone
        (try
          (when (and (string? time-zone)
                     (not (re-matches
                           #"(?:Z|[+-]\d{2}(?::?\d{2})?)"
                           time-zone)))
            (ZoneId/of time-zone))
          (catch Exception _ nil))]
    (when-not zone
      (throw (ex-info "Manual synchronization timezone must be an IANA identifier."
                      {:time-zone time-zone})))
    (assoc primary
           :recording-start-at
           (str (.minusMillis activity
                              (Math/round
                               (* (double source-seconds) 1000.0))))
           :telemetry-sync-at activity-instant
           :camera-sync-at activity-instant)))

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
             (when (= :finished-video active-route)
               (concat
                (when-let [step (get synchronization-step
                                     synchronization-mode)]
                  [step])
                [:output-timespan]))
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
        (cond-> (= :transparent-overlay route)
          (assoc-in [:decisions :synchronization-mode]
                    :shared-clock))
        (complete-step :outcome))))

(defn choose-synchronization
  [state synchronization-mode]
  (when-not (contains? #{:shared-clock :manual-anchor}
                       synchronization-mode)
    (throw (ex-info "Unknown synchronization mode."
                    {:synchronization-mode synchronization-mode})))
  (let [synchronization-mode
        (if (= :transparent-overlay (:active-route state))
          :shared-clock
          synchronization-mode)
        changed? (not= synchronization-mode
                       (get-in state [:decisions :synchronization-mode]))
        state (if (and changed?
                       (some #{:synchronization-decision}
                             (active-steps state)))
                (invalidate-after state :synchronization-decision)
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
        manual-sync (:manualSync active-input)
        derived-sync
        (when (and (= :finished-video active-route)
                   (= :manual-anchor synchronization-mode)
                   (number? (:sourceSeconds manual-sync))
                   (seq (:activityInstant manual-sync))
                   (seq (:timeZone manual-sync)))
          (derive-manual-sync
           {:source-seconds (:sourceSeconds manual-sync)
            :activity-instant (:activityInstant manual-sync)
            :time-zone (:timeZone manual-sync)}))
        active-input (apply dissoc active-input
                            (concat optional-overlay-request-fields
                                    internal-route-fields))
        active-input (if (= :transparent-overlay active-route)
                       (apply dissoc active-input
                              finished-video-request-fields)
                       active-input)
        active-input
        (if derived-sync
          (-> active-input
              (update :sourceVideo merge
                      {:recordingStartAt
                       (:recording-start-at derived-sync)
                       :timeZone (:time-zone derived-sync)})
              (assoc :telemetrySyncAt
                     (:telemetry-sync-at derived-sync)
                     :cameraSyncAt
                     (:camera-sync-at derived-sync)))
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

(defn advance-ready?
  [state]
  (let [steps (active-steps state)
        current-index (.indexOf steps (:current-step state))]
    (and (not (neg? current-index))
         (< (inc current-index) (count steps))
         (step-complete? state (:current-step state)))))

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
   "synchronizationSteps"
   (into {}
         (map (fn [[mode step]]
                [(name mode) (name step)]))
         synchronization-step)
   "overlayOrder" (mapv name overlay-order)
   "tail" ["output-settings" "review"]
   "copy"
   (into {}
         (map (fn [[step {:keys [title description]}]]
                [(name step)
                 {"title" title
                  "description" description}]))
         step-copy)})

(defn browser-state-script []
  (str
   "const wizardStepCopy=wizardStepModel.copy;"
   "function activeSynchronizationMode(){return wizardState.activeRoute==='transparent-overlay'?'shared-clock':(selectedSynchronizationMode()||wizardState.decisions.synchronizationMode||'');}"
   "function activeWizardSteps(){if(!wizardState.activeRoute)return ['outcome'];const steps=[...(wizardStepModel.routePrefixes[wizardState.activeRoute]||[])];if(wizardState.activeRoute==='finished-video'){const synchronizationStep=wizardStepModel.synchronizationSteps[activeSynchronizationMode()];if(synchronizationStep)steps.push(synchronizationStep);steps.push('output-timespan');}steps.push('optional-overlays');for(const overlay of wizardStepModel.overlayOrder){if(wizardState.decisions.optionalOverlays.includes(overlay))steps.push(wizardStepModel.overlaySteps[overlay]);}return [...steps,...wizardStepModel.tail];}"
   "function routeDraftSnapshot(route){const draft={sectionStartAt:value('section-start-at'),sectionEndAt:value('section-end-at')};if(route==='finished-video'){draft.sourceVideo={fileId:value('source-video-file-id'),recordingStartAt:videoRecordingStartAt,timeZone:videoTimeZone.value.trim()};draft.manualSync={sourceSeconds:Number(value('manual-sync-source-seconds')||manualSyncSeconds||0),activityInstant:manualActivityInstant(),timeZone:activeZone(),derivedRecordingStartAt:videoClockSource==='manual-anchor'?videoRecordingStartAt:null};draft.outputFormat=value('output-format');draft.fitMode=value('fit-mode');draft.audioMode=value('audio-mode');}return draft;}"
   "function restoreRouteDraft(route){const draft=wizardState.routeDrafts[route];if(!draft||!Object.keys(draft).length)return;[['sectionStartAt','section-start-at'],['sectionEndAt','section-end-at'],['outputFormat','output-format'],['fitMode','fit-mode'],['audioMode','audio-mode']].forEach(([key,id])=>{if(draft[key]!==undefined&&byId(id))byId(id).value=draft[key];});if(route==='finished-video'&&draft.sourceVideo){byId('source-video-file-id').value=draft.sourceVideo.fileId||'';videoRecordingStartAt=draft.sourceVideo.recordingStartAt||videoRecordingStartAt;if(draft.sourceVideo.timeZone)videoTimeZone.value=draft.sourceVideo.timeZone;}if(route==='finished-video'&&draft.manualSync){manualSyncSeconds=Number(draft.manualSync.sourceSeconds)||0;byId('manual-sync-source-seconds').value=String(manualSyncSeconds);byId('manual-sync-elapsed').textContent=playbackTime(manualSyncSeconds);if(draft.manualSync.activityInstant)byId('telemetry-sync-at').value=isoToZoneLocal(draft.manualSync.activityInstant,draft.manualSync.timeZone||activeZone());}}"
   "function captureWizardState(request,errorMessage=''){const route=wizardState.activeRoute;if(route)wizardState.routeDrafts[route]=routeDraftSnapshot(route);wizardState.sharedInput={telemetryFormat:value('telemetry-format'),telemetry:contentValue('telemetry'),preset:value('preset'),displayTimeZone:activeZone(),futureTraceOpacityPercent:value('future-trace-opacity-percent')};wizardState.decisions.synchronizationMode=activeSynchronizationMode()||null;wizardState.decisions.optionalOverlays=[['timer',byId('timer-enabled').checked],['spo2',byId('spo2-enabled').checked],['watermark',byId('watermark-enabled').checked]].filter(([_name,selected])=>selected).map(([name])=>name);wizardState.optionalOverlayDrafts.timer={startAt:value('timer-start-at'),endAt:value('timer-end-at')};wizardState.optionalOverlayDrafts.spo2={format:'oxiwear-spo2-csv',telemetry:contentValue('spo2-telemetry')};wizardState.optionalOverlayDrafts.watermark={contentBase64:contentValue('watermark-content')};wizardState.renderRequest=request?JSON.parse(JSON.stringify(request)):null;wizardState.validation.errors=errorMessage?{[wizardState.currentStep]:errorMessage}:{};wizardState.completion.complete=!!request;composeWorkflow.dataset.activeRoute=route||'';composeWorkflow.dataset.currentStep=wizardState.currentStep;}"
   "function chooseWizardOutcome(route,announce=true){if(!['transparent-overlay','finished-video'].includes(route))return;if(wizardState.activeRoute&&wizardState.activeRoute!==route)captureWizardState(wizardState.renderRequest);const changed=wizardState.activeRoute!==route;wizardState.activeRoute=route;restoreRouteDraft(route);if(changed){wizardState.currentStep='outcome';wizardState.visitedStepIds=['outcome'];wizardState.completion.completedStepIds=['outcome'];wizardState.validation.invalidatedStepIds=activeWizardSteps().filter(step=>step!=='outcome');}wizardState.completion.complete=false;wizardState.renderRequest=null;wizardOutcomeInputs.forEach(input=>{input.checked=input.value===route;});composeWorkflow.hidden=false;setComposeSourceMode(route==='finished-video'&&!!value('source-video-file-id'));if(route==='transparent-overlay')refreshNoSourceTimeline();invalidatePreview();syncRequest(false);captureWizardState(wizardState.renderRequest);renderWizardStep(false);if(announce)show(wizardOutcomeStatus,route==='finished-video'?'Finished video selected. Choose your source video next.':'Transparent overlay selected. Choose your heart-rate file next.','success');}"))

(defn browser-request-script []
  (str
   "function buildRequest(){const synchronizationMode=activeSynchronizationMode();if(!synchronizationMode)throw new Error('Choose whether the camera and activity devices used the same clock or different clocks.');if(finishedManualSynchronization()&&!deriveManualSynchronization(false))throw new Error('Choose a source-video frame and enter the matching activity-data time.');const request={telemetryFormat:required('telemetry-format','Heart-rate data format'),telemetry:required('telemetry','Heart-rate data'),preset:required('preset','Render preset'),displayTimeZone:activeZone(),futureTraceOpacityPercent:boundedNumber('future-trace-opacity-percent','Future trace opacity',0,100),synchronizationMode,sectionStartAt:localToIso('section-start-at'),sectionEndAt:localToIso('section-end-at')};if(synchronizationMode==='manual-anchor'){request.telemetrySyncAt=localToIso('telemetry-sync-at');request.cameraSyncAt=localToIso('camera-sync-at');}const source=value('source-video-file-id');if(source&&wizardState.activeRoute!=='transparent-overlay'){if(!videoClockConfirmed||!videoRecordingStartAt)throw new Error(synchronizationMode==='manual-anchor'?'Complete the video and activity-data match before preview or creation.':'Confirm the shared video recording clock before preview or creation.');request.sourceVideo={fileId:source,recordingStartAt:videoRecordingStartAt,timeZone:videoTimeZone.value.trim()};request.outputFormat=value('output-format');request.fitMode=value('fit-mode');request.audioMode=value('audio-mode');}if(byId('spo2-enabled').checked){request.spo2={format:'oxiwear-spo2-csv',telemetry:required('spo2-telemetry','Oxygen-saturation data (SpO2)')};}if(byId('timer-enabled').checked){request.timer={startAt:localToIso('timer-start-at'),endAt:localToIso('timer-end-at')};}if(byId('watermark-enabled').checked){request.watermark={contentBase64:required('watermark-content','Watermark file')};}return request;}"
   "function validateRequest(request){const errors=[];if(!isObject(request))return ['Request must be a JSON object.'];unknownFields(request,requestFields,'Request',errors);requiredRequestFields.forEach(key=>requiredString(request,key,'Request',errors));if(typeof request.telemetryFormat==='string'&&request.telemetryFormat&&!['polar-csv','garmin-fit','oxiwear-hr-csv'].includes(request.telemetryFormat))errors.push('Request.telemetryFormat must be polar-csv, garmin-fit, or oxiwear-hr-csv.');if(typeof request.preset==='string'&&request.preset&&!['1080p25','2.7k25'].includes(request.preset))errors.push('Request.preset must be 1080p25 or 2.7k25.');if(has(request,'futureTraceOpacityPercent')&&(typeof request.futureTraceOpacityPercent!=='number'||!Number.isFinite(request.futureTraceOpacityPercent)||request.futureTraceOpacityPercent<0||request.futureTraceOpacityPercent>100))errors.push('Request.futureTraceOpacityPercent must be a number from 0 through 100.');if(typeof request.telemetry==='string'&&request.telemetry){const limit=request.telemetryFormat==='garmin-fit'?13981016:10485760;if(utf8Length(request.telemetry)>limit)errors.push('Request.telemetry exceeds its encoded size limit.');}['telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'].forEach(key=>{if(has(request,key)&&typeof request[key]==='string'&&request[key]&&!instantValue(request[key]))errors.push('Request.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');});const sectionTimes=['cameraSyncAt','sectionStartAt','sectionEndAt'].map(key=>Date.parse(request[key]));if(sectionTimes.every(time=>!Number.isNaN(time))&&!(sectionTimes[0]<=sectionTimes[1]&&sectionTimes[1]<sectionTimes[2]))errors.push('Request timestamps must satisfy cameraSyncAt <= sectionStartAt < sectionEndAt.');if(has(request,'spo2')){const value=request.spo2;if(!isObject(value))errors.push('Request.spo2 must be an object.');else{unknownFields(value,['format','telemetry'],'Request.spo2',errors);requiredString(value,'format','Request.spo2',errors);requiredString(value,'telemetry','Request.spo2',errors);if(typeof value.format==='string'&&value.format&&value.format!=='oxiwear-spo2-csv')errors.push('Request.spo2.format must be oxiwear-spo2-csv.');if(typeof value.telemetry==='string'&&value.telemetry&&utf8Length(value.telemetry)>10485760)errors.push('Request.spo2.telemetry exceeds the 10 MiB limit.');}}if(has(request,'timer')){const value=request.timer;if(!isObject(value))errors.push('Request.timer must be an object.');else{unknownFields(value,['startAt','endAt'],'Request.timer',errors);const startValid=validateInstant(value,'startAt','Request.timer',errors),endValid=validateInstant(value,'endAt','Request.timer',errors);if(startValid&&endValid){const start=Date.parse(value.startAt),end=Date.parse(value.endAt),sectionStart=Date.parse(request.sectionStartAt),sectionEnd=Date.parse(request.sectionEndAt);if(!Number.isNaN(sectionStart)&&!Number.isNaN(sectionEnd)&&!(sectionStart<=start&&start<end&&end<=sectionEnd))errors.push('Request.timer must satisfy sectionStartAt <= startAt < endAt <= sectionEndAt.');}}}if(has(request,'watermark')){const value=request.watermark;if(!isObject(value))errors.push('Request.watermark must be an object.');else{unknownFields(value,['contentBase64'],'Request.watermark',errors);if(requiredString(value,'contentBase64','Request.watermark',errors)&&(!/^[A-Za-z0-9+/]*={0,2}$/.test(value.contentBase64)||value.contentBase64.length%4===1))errors.push('Request.watermark.contentBase64 must be base64 text.');if(typeof value.contentBase64==='string'&&value.contentBase64.length>2796204)errors.push('Request.watermark.contentBase64 exceeds the 2 MiB PNG limit.');}}if(has(request,'sourceVideo')){const value=request.sourceVideo;if(!isObject(value))errors.push('Request.sourceVideo must be an object.');else{unknownFields(value,['fileId','recordingStartAt','timeZone','name','mimeType'],'Request.sourceVideo',errors);requiredString(value,'fileId','Request.sourceVideo',errors);validateInstant(value,'recordingStartAt','Request.sourceVideo',errors);requiredString(value,'timeZone','Request.sourceVideo',errors);['name','mimeType'].forEach(key=>{if(has(value,key)&&typeof value[key]!=='string')errors.push('Request.sourceVideo.'+key+' must be a string.');});}}[['outputFormat',['h264-mp4','prores-422-mov']],['fitMode',['letterbox','pillarbox','crop']],['audioMode',['source+heartbeat','source-only','heartbeat-only']]].forEach(([key,allowed])=>{if(has(request,key)){if(typeof request[key]!=='string')errors.push('Request.'+key+' must be a string.');else if(!allowed.includes(request[key]))errors.push('Request.'+key+' has an unsupported value.');if(!has(request,'sourceVideo'))errors.push('Request.'+key+' requires sourceVideo.fileId.');}});return errors;}"))

(defn browser-navigation-script []
  (str
   "function wizardPanelForStep(step){const panels={outcome:'wizard-outcome-step','source-video':'drive-source-step','activity-data':'activity-data-step','synchronization-decision':'synchronization-decision-step','confirm-video-clock':'confirm-video-clock-step','matching-moment':'matching-moment-step','output-timespan':'output-timespan-step','optional-overlays':'optional-overlays-step','spo2-overlay':'optional-overlays-step','watermark-overlay':'optional-overlays-step','output-settings':'output-settings-step',review:'review-step'};return panels[step]?byId(panels[step]):null;}"
   "function timingWorkspaceAvailable(step){return ['matching-moment','output-timespan','timer-overlay'].includes(step)&&(wizardState.activeRoute==='transparent-overlay'||hasSourceVideo());}"
   "function wizardNavigationTarget(step,panel){if(timingWorkspaceAvailable(step))return videoPlayer;if(['output-settings','review'].includes(step)&&panel){const cards=[...panel.children].filter(child=>child.classList.contains('card')&&!child.hidden);return cards.at(-1)||panel;}return panel;}"
   "function showOnlyWizardSections(step){setComposeSourceMode(hasSourceVideo());const timingAvailable=timingWorkspaceAvailable(step),timerStep=step==='timer-overlay',finished=wizardState.activeRoute==='finished-video'&&hasSourceVideo(),manual=activeSynchronizationMode()==='manual-anchor'&&finished;videoPlayer.hidden=!timingAvailable;videoFullscreen.disabled=!timingAvailable;byId('display-time-zone-field').hidden=!timingAvailable||timerStep;byId('output-start-field').hidden=!timingAvailable||timerStep;byId('output-end-field').hidden=!timingAvailable||timerStep;noSourceRangeStatus.hidden=!timingAvailable||wizardState.activeRoute!=='transparent-overlay';byId('synchronization-options').hidden=step!=='synchronization-decision';videoClockConfirmation.hidden=step!=='confirm-video-clock';manualSynchronizationFields.style.display=timingAvailable&&manual?'':'none';manualSynchronizationFields.hidden=!(timingAvailable&&manual);byId('camera-sync-at').type=finished?'hidden':'datetime-local';manualSyncElapsed.hidden=!finished;byId('section-start-at').readOnly=step!=='output-timespan';byId('section-end-at').readOnly=step!=='output-timespan';byId('timezone').disabled=!timingAvailable||step!=='output-timespan';byId('telemetry-sync-at').readOnly=step!=='matching-moment';byId('timer-start-at').readOnly=step!=='timer-overlay';byId('timer-end-at').readOnly=step!=='timer-overlay';byId('timer-fields').hidden=!(timerStep&&byId('timer-enabled').checked);const optionalStep=step==='optional-overlays';[['timer-option','timer-overlay'],['spo2-option','spo2-overlay'],['watermark-option','watermark-overlay']].forEach(([id,configStep])=>{byId(id).hidden=!(optionalStep||step===configStep);});if(optionalStep){refreshOptional('spo2-enabled','spo2-fields');refreshOptional('watermark-enabled','watermark-fields');}updateTimerMarkers();}"
   "function selectedText(id){const control=byId(id);return control?.selectedOptions?.[0]?.textContent?.trim()||value(id);}"
   "function reviewSummary(step){const selected=wizardState.decisions.optionalOverlays||[],names={timer:'Timer',spo2:'OxiWear SpO2',watermark:'PNG watermark'};if(step==='outcome')return wizardState.activeRoute==='finished-video'?'Finished video with overlay':'Transparent overlay for a video editor';if(step==='source-video')return 'One Google Drive source video selected';if(step==='activity-data')return ({'garmin-fit':'Garmin FIT','polar-csv':'Polar CSV','oxiwear-hr-csv':'OxiWear heart-rate CSV'}[value('telemetry-format')]||'Heart-rate file')+' selected';if(step==='synchronization-decision')return activeSynchronizationMode()==='manual-anchor'?'Different camera and activity clocks':'Shared camera and activity clock';if(step==='confirm-video-clock')return (videoRecordingStartAt||'recording start confirmed')+' · '+(videoTimeZone.value.trim()||activeZone());if(step==='matching-moment')return 'Source '+playbackTime(manualSyncSeconds)+' matches '+(manualActivityInstant()||'activity time required');if(step==='output-timespan')return value('section-start-at')+' to '+value('section-end-at')+' · '+activeZone();if(step==='optional-overlays')return selected.length?selected.map(name=>names[name]).join(', '):'No optional overlays';if(step==='timer-overlay')return 'Timer start: '+value('timer-start-at')+' · Timer end: '+value('timer-end-at')+' · IANA timezone: '+activeZone();if(step==='spo2-overlay')return 'OxiWear oxygen-saturation CSV selected';if(step==='watermark-overlay')return 'PNG watermark selected';if(step==='output-settings'){const preset=selectedText('preset'),opacity='Future trace opacity '+value('future-trace-opacity-percent')+'%';if(wizardState.activeRoute==='transparent-overlay')return 'ProRes 4444 MOV · Transparent background · '+preset+' · '+opacity;return selectedText('output-format')+' · '+selectedText('fit-mode')+' · '+selectedText('audio-mode')+' · '+preset+' · '+opacity;}return '';}"
   "function renderReview(){const container=byId('review-sections');if(!container)return;container.replaceChildren();for(const step of activeWizardSteps().filter(stepId=>stepId!=='review')){const copy=wizardStepCopy[step]||{title:step},section=document.createElement('section'),body=document.createElement('div'),heading=document.createElement('h3'),summary=document.createElement('p'),edit=document.createElement('button');section.className='review-section';section.dataset.reviewStep=step;heading.textContent=copy.title;summary.textContent=reviewSummary(step);edit.type='button';edit.dataset.editStep=step;edit.textContent='Edit';edit.setAttribute('aria-label','Edit '+copy.title);edit.addEventListener('click',()=>navigateWizardStep(step,{push:true,focus:true}));body.append(heading,summary);section.append(body,edit);container.append(section);}}"
   "function renderWizardStep(focusHeading=false){const steps=activeWizardSteps();if(!steps.includes(wizardState.currentStep)){wizardState.currentStep=[...steps].reverse().find(stepId=>wizardState.visitedStepIds.includes(stepId))||'outcome';const state=history.state&&typeof history.state==='object'?history.state:{};history.replaceState({...state,wizardStep:wizardState.currentStep},'',location.href);}const step=wizardState.currentStep,index=steps.indexOf(step),copy=wizardStepCopy[step]||{title:step,description:''};wizardPanels.forEach(panel=>{panel.hidden=true;});const panel=wizardPanelForStep(step);if(panel){panel.hidden=false;panel.dataset.stepId=step;}showOnlyWizardSections(step);if(step==='review')renderReview();const navigationTarget=wizardNavigationTarget(step,panel);if(navigationTarget)navigationTarget.append(wizardNavigation);wizardHeading.textContent=copy.title;wizardDescription.textContent=copy.description;wizardProgress.textContent='Step '+(index+1)+' of '+steps.length;wizardOverview.hidden=!wizardState.activeRoute;wizardStepList.replaceChildren();if(wizardState.activeRoute){steps.forEach((stepId,stepIndex)=>{const item=document.createElement('li'),button=document.createElement('button'),stepCopy=wizardStepCopy[stepId]||{title:stepId};button.type='button';button.dataset.stepId=stepId;button.textContent=(stepIndex+1)+'. '+stepCopy.title;button.disabled=stepId!==step&&!wizardState.visitedStepIds.includes(stepId);if(stepId===step)button.setAttribute('aria-current','step');button.addEventListener('click',()=>navigateWizardStep(stepId,{push:true,focus:true}));item.append(button);wizardStepList.append(item);});}wizardBack.disabled=index<=0;wizardNext.textContent=step==='output-settings'?'Finish':'Next';renderWizardAdvance(step);composeWorkflow.dataset.activeRoute=wizardState.activeRoute||'';composeWorkflow.dataset.currentStep=step;if(focusHeading)wizardHeader.focus();}"
   "function showWizardError(message){wizardState.validation.errors={[wizardState.currentStep]:message};wizardErrorSummary.textContent=message;wizardErrorSummary.hidden=false;wizardErrorSummary.focus();}"
   "function clearWizardError(){wizardState.validation.errors={};wizardErrorSummary.textContent='';wizardErrorSummary.hidden=true;}"
   "function validateWizardStep(step){if(step==='outcome'){if(!wizardState.activeRoute)throw new Error('Choose an output before continuing.');return;}if(step==='source-video'){if(!value('source-video-file-id'))throw new Error('Choose a source video before continuing.');return;}if(step==='activity-data'){required('telemetry-format','Heart-rate data format');required('telemetry','Heart-rate data');return;}if(step==='synchronization-decision'){if(!selectedSynchronizationMode())throw new Error('Choose whether the camera and activity devices used the same clock or different clocks.');return;}if(step==='confirm-video-clock'){if(!videoClockConfirmed||videoClockSource!=='shared-clock')throw new Error('Confirm the shared video recording clock before continuing.');return;}if(step==='matching-moment'){if(!deriveManualSynchronization(false))throw new Error('Choose a source-video frame and enter the matching activity-data time.');return;}if(step==='output-timespan'){if(hasSourceVideo()){localToIso('section-start-at');localToIso('section-end-at');return;}noSourceRange();return;}if(step==='timer-overlay'){localTextToIso(value('timer-start-at'),activeZone(),'Timer start');localTextToIso(value('timer-end-at'),activeZone(),'Timer end');return;}if(step==='spo2-overlay'){required('spo2-telemetry','Oxygen-saturation data (SpO2)');return;}if(step==='watermark-overlay'){required('watermark-content','Watermark file');return;}if(step==='output-settings'){required('preset','Render preset');boundedNumber('future-trace-opacity-percent','Future trace opacity',0,100);return;}if(step==='review')buildRequest();}"
   "const validateWizardStepWithLegacyActivityFields=validateWizardStep;validateWizardStep=function(step){if(step==='activity-data'){required('telemetry','Heart-rate file');if(!value('telemetry-format'))throw new Error('Choose a compatible Garmin FIT, Polar CSV, or Advanced OxiWear heart-rate file.');return;}return validateWizardStepWithLegacyActivityFields(step);};"
   "function wizardAdvanceIsReady(step){const steps=activeWizardSteps(),index=steps.indexOf(step);if(index<0||index+1>=steps.length)return false;try{validateWizardStep(step);return true;}catch(_error){return false;}}"
   "function renderWizardAdvance(step){const ready=wizardAdvanceIsReady(step),reveal=ready&&wizardAdvanceReadiness[step]!==true,stepChanged=wizardAdvanceStep!==step;wizardNext.hidden=!ready;if(!ready||(stepChanged&&!reveal))wizardNext.classList.remove('wizard-next-ready');else if(reveal){wizardNext.classList.remove('wizard-next-ready');void wizardNext.offsetWidth;wizardNext.classList.add('wizard-next-ready');}wizardAdvanceStep=step;wizardAdvanceReadiness[step]=ready;}"
   "function navigateWizardStep(step,{push=true,focus=true}={}){const steps=activeWizardSteps();if(!steps.includes(step))return false;wizardState.currentStep=step;if(!wizardState.visitedStepIds.includes(step))wizardState.visitedStepIds.push(step);clearWizardError();if(push){const state=history.state&&typeof history.state==='object'?history.state:{};history.pushState({...state,wizardStep:step},'',location.href);}renderWizardStep(focus);return true;}"
   "function completeCurrentWizardStep(){const step=wizardState.currentStep;try{validateWizardStep(step);}catch(error){showWizardError(error.message);return;}if(!wizardState.completion.completedStepIds.includes(step))wizardState.completion.completedStepIds.push(step);captureWizardState(wizardState.renderRequest);const steps=activeWizardSteps(),index=steps.indexOf(step),nextStep=steps[index+1];if(nextStep)navigateWizardStep(nextStep,{push:true,focus:true});}"
   "function acceptWizardSourceSelection(){if(wizardState.activeRoute!=='finished-video')chooseWizardOutcome('finished-video',false);clearWizardError();renderWizardStep(false);}"))
