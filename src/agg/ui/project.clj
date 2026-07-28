(ns agg.ui.project
  (:require [agg.ui.wizard :as wizard]))

(def schema-version 1)

(def ^:private route-order
  [:transparent-overlay :finished-video])

(def ^:private overlay-order
  [:timer :spo2 :watermark])

(def ^:private project-fields
  #{:schemaVersion
    :activeRoute
    :currentStepId
    :visitedStepIds
    :sharedInput
    :decisions
    :routeDrafts
    :optionalOverlayDrafts
    :renderRequest})

(def ^:private request-fields
  #{:telemetryFormat
    :telemetry
    :preset
    :displayTimeZone
    :futureTraceOpacityPercent
    :synchronizationMode
    :telemetrySyncAt
    :cameraSyncAt
    :sectionStartAt
    :sectionEndAt
    :spo2
    :timer
    :watermark
    :sourceVideo
    :outputFormat
    :fitMode
    :audioMode})

(def ^:private iso-pattern
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$")

(defn- route-name [route]
  (when route
    (name route)))

(defn- ordered-visited-steps [state]
  (let [visited (:visited-steps state)
        active (wizard/active-steps state)
        ordered (vec (filter visited active))]
    (if (and (:current-step state)
             (visited (:current-step state))
             (not (some #{(:current-step state)} ordered)))
      (conj ordered (:current-step state))
      ordered)))

(defn export-project [state]
  (let [render-request (wizard/submit-ready-request state)]
    {:schemaVersion schema-version
     :activeRoute (route-name (:active-route state))
     :currentStepId (some-> (:current-step state) name)
     :visitedStepIds (mapv name (ordered-visited-steps state))
     :sharedInput (:shared-input state)
     :decisions {:synchronizationMode (some-> (get-in state [:decisions :synchronization-mode]) name)
                 :optionalOverlays (->> overlay-order
                                        (filter (get-in state [:decisions :optional-overlays]))
                                        (mapv name))}
     :routeDrafts (into {}
                        (map (fn [route]
                               [route
                                (get-in state [:route-drafts route] {})]))
                        route-order)
     :optionalOverlayDrafts (into {}
                                  (map (fn [overlay]
                                         [overlay
                                          (get-in state [:optional-overlay-drafts overlay] {})]))
                                  overlay-order)
     :renderRequest render-request}))

(defn- add-error [errors message]
  (conj errors message))

(defn- unknown-field-errors [candidate allowed path]
  (reduce (fn [errors key]
            (if (contains? allowed key)
              errors
              (add-error errors
                         (str path " contains unknown field " (name key) "."))))
          []
          (keys candidate)))

(defn- object? [value]
  (and (map? value)
       (not (record? value))))

(defn- string-present? [value]
  (and (string? value)
       (not= "" value)))

(defn- instant? [value]
  (and (string-present? value)
       (re-matches iso-pattern value)
       (try
         (java.time.Instant/parse value)
         true
         (catch Exception _
           false))))

(defn- validate-request [request path]
  (let [errors (if-not (object? request)
                 [(str path " must be a JSON object.")]
                 (unknown-field-errors request request-fields path))]
    (cond-> errors
      (and (contains? request :sectionStartAt)
           (not (instant? (:sectionStartAt request))))
      (add-error
       (str path ".sectionStartAt must be an ISO-8601 instant with Z or an explicit UTC offset.")))))

(defn validate-project [project]
  (let [errors (if-not (object? project)
                 ["Project must be a JSON object."]
                 (unknown-field-errors project project-fields "Project"))
        errors (if (= schema-version (:schemaVersion project))
                 errors
                 (add-error errors
                            (str "Project.schemaVersion must be "
                                 schema-version ".")))
        errors (cond-> errors
                 (contains? project :renderRequest)
                 (into (validate-request (:renderRequest project)
                                         "Project.renderRequest")))]
    errors))

(defn browser-runtime-script []
  (str
   "function deepClone(value){return value==null?value:JSON.parse(JSON.stringify(value));}function projectText(){captureWizardState(wizardState.renderRequest);const project={schemaVersion:projectSchemaVersion,activeRoute:wizardState.activeRoute||null,currentStepId:wizardState.renderRequest?'review':wizardState.currentStep,visitedStepIds:[...(wizardState.visitedStepIds||[])],sharedInput:deepClone(wizardState.sharedInput||{}),decisions:{synchronizationMode:wizardState.decisions?.synchronizationMode||null,optionalOverlays:[...(wizardState.decisions?.optionalOverlays||[])]},routeDrafts:deepClone(wizardState.routeDrafts||{'transparent-overlay':{},'finished-video':{}}),optionalOverlayDrafts:deepClone(wizardState.optionalOverlayDrafts||{timer:{},spo2:{},watermark:{}}),renderRequest:wizardState.completion.complete&&wizardState.renderRequest?deepClone(wizardState.renderRequest):null};const text=JSON.stringify(project,null,2);projectJson.value=text;return text;}function projectUnknownFields(candidate,allowed,path,errors){Object.keys(candidate).filter(key=>!allowed.includes(key)).forEach(key=>errors.push(path+' contains unknown field '+key+'.'));}function validateProject(project){const errors=[];if(!isObject(project))return ['Project must be a JSON object.'];projectUnknownFields(project,['schemaVersion','activeRoute','currentStepId','visitedStepIds','sharedInput','decisions','routeDrafts','optionalOverlayDrafts','renderRequest'],'Project',errors);if(project.schemaVersion!==projectSchemaVersion)errors.push('Project.schemaVersion must be '+projectSchemaVersion+'.');if(project.activeRoute!==null&&project.activeRoute!==undefined&&!['transparent-overlay','finished-video'].includes(project.activeRoute))errors.push('Project.activeRoute must be transparent-overlay or finished-video.');if(!Array.isArray(project.visitedStepIds))errors.push('Project.visitedStepIds must be an array.');if(project.sharedInput!==undefined&&!isObject(project.sharedInput))errors.push('Project.sharedInput must be an object.');if(project.decisions!==undefined){if(!isObject(project.decisions))errors.push('Project.decisions must be an object.');else{projectUnknownFields(project.decisions,['synchronizationMode','optionalOverlays'],'Project.decisions',errors);if(project.decisions.synchronizationMode!==null&&project.decisions.synchronizationMode!==undefined&&!['shared-clock','manual-anchor'].includes(project.decisions.synchronizationMode))errors.push('Project.decisions.synchronizationMode must be shared-clock, manual-anchor, or null.');if(!Array.isArray(project.decisions.optionalOverlays||[]))errors.push('Project.decisions.optionalOverlays must be an array.');else for(const overlay of project.decisions.optionalOverlays)if(!['timer','spo2','watermark'].includes(overlay))errors.push('Project.decisions.optionalOverlays contains unsupported value '+overlay+'.');}}if(project.routeDrafts!==undefined){if(!isObject(project.routeDrafts))errors.push('Project.routeDrafts must be an object.');else projectUnknownFields(project.routeDrafts,['transparent-overlay','finished-video'],'Project.routeDrafts',errors);}if(project.optionalOverlayDrafts!==undefined){if(!isObject(project.optionalOverlayDrafts))errors.push('Project.optionalOverlayDrafts must be an object.');else projectUnknownFields(project.optionalOverlayDrafts,['timer','spo2','watermark'],'Project.optionalOverlayDrafts',errors);}const shared=project.sharedInput||{};if(typeof shared.telemetry==='string'&&utf8Length(shared.telemetry)>(shared.telemetryFormat==='garmin-fit'?13981016:10485760))errors.push('Project.sharedInput.telemetry exceeds its encoded size limit.');const spo2Draft=project.optionalOverlayDrafts?.spo2?.telemetry;if(typeof spo2Draft==='string'&&utf8Length(spo2Draft)>10485760)errors.push('Project.optionalOverlayDrafts.spo2.telemetry exceeds the 10 MiB limit.');const watermarkDraft=project.optionalOverlayDrafts?.watermark?.contentBase64;if(typeof watermarkDraft==='string'&&watermarkDraft.length>2796204)errors.push('Project.optionalOverlayDrafts.watermark.contentBase64 exceeds the 2 MiB PNG limit.');if(project.renderRequest!==null&&project.renderRequest!==undefined){const requestErrors=validateRequest(project.renderRequest);errors.push(...requestErrors.map(error=>error.replace(/^Request/,'Project.renderRequest')));}return errors;}function importedSource(project){return project.renderRequest?.sourceVideo||project.routeDrafts?.['finished-video']?.sourceVideo||null;}function withoutImportedSource(project){const next=deepClone(project),finished=next.routeDrafts?.['finished-video']||{};if(finished.sourceVideo)delete finished.sourceVideo;next.routeDrafts={...(next.routeDrafts||{}),'finished-video':finished};next.renderRequest=null;next.currentStepId='source-video';next.visitedStepIds=['outcome','source-video'];return next;}async function revalidateImportedSource(project){const source=importedSource(project);if(project.activeRoute!=='finished-video'||!source?.fileId)return {project,unavailable:false};const response=await fetch('/ui/project-source-validation',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':playbackCsrf},body:JSON.stringify({fileId:source.fileId})});if(response.ok){const validated=await response.json();const next=deepClone(project),target=next.routeDrafts?.['finished-video']?.sourceVideo||(next.routeDrafts['finished-video']={...next.routeDrafts['finished-video'],sourceVideo:{}}).sourceVideo;target.fileId=validated.fileId||source.fileId;target.name=validated.fileName||source.name||'';target.mimeType=validated.mimeType||source.mimeType||'';if(next.renderRequest?.sourceVideo){next.renderRequest.sourceVideo.fileId=target.fileId;}return {project:next,unavailable:false};}return {project:withoutImportedSource(project),unavailable:true};}function restoreProject(project,sourceUnavailable=false){const next=deepClone(project),shared=next.sharedInput||{},activeRoute=next.activeRoute||null,activeDraft=activeRoute?(next.routeDrafts?.[activeRoute]||{}):{},sourceDraft=(next.routeDrafts?.['finished-video']||{}).sourceVideo||next.renderRequest?.sourceVideo||null;wizardState.activeRoute=activeRoute;wizardState.currentStep=next.renderRequest?'review':(next.currentStepId||'outcome');wizardState.visitedStepIds=[...(next.visitedStepIds||['outcome'])];wizardState.sharedInput=deepClone(shared);wizardState.decisions={synchronizationMode:next.decisions?.synchronizationMode||null,optionalOverlays:[...(next.decisions?.optionalOverlays||[])]};wizardState.routeDrafts=deepClone(next.routeDrafts||{'transparent-overlay':{},'finished-video':{}});wizardState.optionalOverlayDrafts=deepClone(next.optionalOverlayDrafts||{timer:{},spo2:{},watermark:{}});wizardState.validation={errors:{},invalidatedStepIds:[]};wizardState.completion={completedStepIds:wizardState.visitedStepIds.filter(step=>step!=='review'),complete:!!next.renderRequest};wizardState.renderRequest=deepClone(next.renderRequest||null);wizardOutcomeInputs.forEach(input=>{input.checked=input.value===activeRoute;});composeWorkflow.hidden=!activeRoute;byId('telemetry-format').value=shared.telemetryFormat||'';clearFileBackedValue('telemetry');byId('telemetry').value=shared.telemetry||'';byId('telemetry-file').value='';show(byId('telemetry-status'),shared.telemetry?'Loaded from Project JSON.':'');byId('preset').value=shared.preset||'1080p25';byId('future-trace-opacity-percent').value=shared.futureTraceOpacityPercent??25;setDisplayTimeZone(shared.displayTimeZone||'local');[['sectionStartAt','section-start-at'],['sectionEndAt','section-end-at']].forEach(([key,id])=>{byId(id).value=activeDraft[key]?isoToLocal(activeDraft[key]):'';});synchronizationModeInputs.forEach(input=>{input.checked=input.value===(next.decisions?.synchronizationMode||'');});byId('source-video-file-id').value=sourceDraft?.fileId||'';byId('picker-selection').textContent=sourceDraft?.fileId?(sourceDraft.name||'Imported Drive video selected'):'Choose a video file';videoClockCandidates.replaceChildren();clockInspectionGeneration++;videoSourceName=sourceDraft?.name||null;videoSourceDuration=null;videoTimeZone.value=sourceDraft?.timeZone||'';videoRecordingStartAt=sourceDraft?.recordingStartAt||null;videoRecordingStart.value=sourceDraft?.recordingStartAt&&next.decisions?.synchronizationMode==='shared-clock'?isoToZoneLocal(sourceDraft.recordingStartAt,sourceDraft.timeZone):'';videoClockConfirmed=!!sourceDraft;videoClockSource=sourceDraft?(next.decisions?.synchronizationMode==='manual-anchor'?'manual-anchor':'shared-clock'):null;show(videoClockInspectionStatus,sourceDraft?(sourceUnavailable?'Drive access could not be revalidated. Re-select the video to restore playback and rendering.':'Drive reference revalidated for this import. Re-select the video only if you want fresh clock inspection.'):'Choose a source video to continue.');show(videoClockStatus,sourceDraft?(videoClockSource==='manual-anchor'?'Recording time derived from the activity-data match.':'Video clock confirmed.'):'Not confirmed.',sourceDraft?'success':'');[['telemetrySyncAt','telemetry-sync-at'],['cameraSyncAt','camera-sync-at']].forEach(([key,id])=>{byId(id).value=next.renderRequest?.[key]?isoToLocal(next.renderRequest[key]):'';});if(sourceDraft&&videoClockSource==='manual-anchor'&&next.renderRequest?.cameraSyncAt){manualSyncSeconds=snapOutputFrame((Date.parse(next.renderRequest.cameraSyncAt)-Date.parse(sourceDraft.recordingStartAt))/1000);manualSyncSourceSeconds.value=String(manualSyncSeconds);manualSyncElapsed.textContent=playbackTime(manualSyncSeconds);}else{manualSyncSeconds=0;manualSyncSourceSeconds.value='0';manualSyncElapsed.textContent=playbackTime(0);}byId('output-format').value=(next.renderRequest?.outputFormat??activeDraft.outputFormat??'h264-mp4');byId('fit-mode').value=(next.renderRequest?.fitMode??activeDraft.fitMode??'letterbox');byId('audio-mode').value=(next.renderRequest?.audioMode??activeDraft.audioMode??'source+heartbeat');const hasSpo2=wizardState.decisions.optionalOverlays.includes('spo2');byId('spo2-enabled').checked=hasSpo2;refreshOptional('spo2-enabled','spo2-fields');clearFileBackedValue('spo2-telemetry');byId('spo2-telemetry').value=wizardState.optionalOverlayDrafts.spo2?.telemetry||'';show(byId('spo2-status'),byId('spo2-telemetry').value?'Loaded from Project JSON.':'');const hasTimer=wizardState.decisions.optionalOverlays.includes('timer');byId('timer-enabled').checked=hasTimer;refreshOptional('timer-enabled','timer-fields');byId('timer-start-at').value=wizardState.optionalOverlayDrafts.timer?.startAt?isoToLocal(wizardState.optionalOverlayDrafts.timer.startAt):'';byId('timer-end-at').value=wizardState.optionalOverlayDrafts.timer?.endAt?isoToLocal(wizardState.optionalOverlayDrafts.timer.endAt):'';const hasWatermark=wizardState.decisions.optionalOverlays.includes('watermark');byId('watermark-enabled').checked=hasWatermark;refreshOptional('watermark-enabled','watermark-fields');byId('watermark-file').value='';clearFileBackedValue('watermark-content');byId('watermark-content').value=wizardState.optionalOverlayDrafts.watermark?.contentBase64||'';show(byId('watermark-status'),byId('watermark-content').value?'Loaded from Project JSON.':'');updateTelemetryAccept();updateSynchronizationMode();updateTimerMarkers();updateVideoSourceSummary();updateVideoTicks();updateVideoTransport();updateOutputFraming();if(sourceDraft){setComposeSourceMode(true);initializeOutputRange(true);loadDrivePlayback({id:sourceDraft.fileId,name:sourceDraft.name||'Imported video',mimeType:sourceDraft.mimeType||'video/mp4'});}else{setComposeSourceMode(false);refreshNoSourceTimeline();}if(next.renderRequest){writeRequest(next.renderRequest);}else{hidden.value='{}';raw.value='{}';}projectText();invalidatePreview();clearWizardError();renderWizardStep(false);}async function applyProjectText(text){const project=JSON.parse(text),errors=validateProject(project);if(errors.length){show(projectStatus,errors.map((error,index)=>(index+1)+'. '+error).join('\\n'),'error');return;}const result=await revalidateImportedSource(project);restoreProject(result.project,result.unavailable);show(projectStatus,result.unavailable?'Project JSON applied. Re-select the unavailable Drive video to restore playback and rendering.':'Project JSON applied to the form.','success');show(status,result.unavailable?'Project JSON applied. Re-select the unavailable Drive video to continue.':'Ready to preview or create the finished video.','success');}byId('apply-project-json').addEventListener('click',()=>{applyProjectText(projectJson.value).catch(error=>show(projectStatus,error.message,'error'));});byId('copy-project-json').addEventListener('click',()=>{const text=projectText();navigator.clipboard?navigator.clipboard.writeText(text).then(()=>show(projectStatus,'Project JSON copied.','success')):show(projectStatus,'Copy is unavailable in this browser.');});byId('download-project-json').addEventListener('click',()=>{const blob=new Blob([projectText()],{type:'application/json'}),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download='alpha-compose-project.json';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),0);show(projectStatus,'Project JSON downloaded.','success');});byId('upload-project-json').addEventListener('change',()=>{const file=byId('upload-project-json').files&&byId('upload-project-json').files[0];if(!file)return;show(projectStatus,'Reading Project JSON…');const reader=new FileReader();reader.onload=()=>{applyProjectText(String(reader.result)).catch(error=>show(projectStatus,error.message,'error'));};reader.onerror=()=>show(projectStatus,'Could not read that Project JSON file.','error');reader.readAsText(file);});projectText();"))
