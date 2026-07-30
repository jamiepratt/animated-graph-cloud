(ns agg.proto.core
  (:require [agg.auth.core :as auth]
            [agg.contracts.render :as contract]
            [agg.drive.core :as drive]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def fixed-folder-id "1PAoq2qflZB9qX1FEMmi1m43Zb3oZI_lt")

(def fixed-folder-bootstrap-file-ids
  ["14Zvf8KpIrL3OlXmQ1kXJJ622HFC3F5_P"
   "1JWbSuN9XlZKew8ASHJn9GTQl_OBwuRrC"
   "16kavLpM1iP6lBCWab0yPX-ylB0GCNc2D"])

(def playback-range-worker
  (str
   "const playbackPath=/^\\/v1\\/(?:drive\\/playback\\/[0-9A-Fa-f-]{36}|derivative-preparations\\/[0-9A-Fa-f-]{36}\\/playback\\/[0-9A-Fa-f-]{36})$/;"
   "self.addEventListener('install',event=>event.waitUntil(self.skipWaiting()));"
   "self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));"
   "self.addEventListener('fetch',event=>{"
   "const url=new URL(event.request.url),headers=event.request.headers,"
   "range=headers.get('Range');"
   "if(event.request.method==='GET'&&playbackPath.test(url.pathname)&&range){"
   "url.searchParams.set('__agg_range',range);"
   "event.respondWith(fetch(new Request(url.toString(),{"
   "method:'GET',headers,credentials:event.request.credentials,"
   "mode:event.request.mode,redirect:event.request.redirect,"
   "referrer:event.request.referrer,referrerPolicy:event.request.referrerPolicy"
   "})));"
   "}});"))

(defn- icon-links []
  (str "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"
       "<link rel=\"icon\" href=\"/favicon-16.png\" sizes=\"16x16\" type=\"image/png\">"
       "<link rel=\"icon\" href=\"/favicon-32.png\" sizes=\"32x32\" type=\"image/png\">"
       "<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"))

(defn- parse-duration-seconds [value]
  (let [parsed (cond
                 (number? value) (double value)
                 (string? value) (some-> value Double/parseDouble)
                 :else nil)]
    (when (and parsed (Double/isFinite parsed) (pos? parsed))
      parsed)))

(defn- source-summary [metadata]
  {:fileId (:id metadata)
   :fileName (:name metadata)
   :mimeType (:mimeType metadata)
   :size (:size metadata)
   :durationSeconds
   (parse-duration-seconds
    (or (:durationSeconds metadata)
        (get-in metadata [:videoMediaMetadata :durationSeconds])
        (some-> (get-in metadata [:videoMediaMetadata :durationMillis])
                parse-long
                (/ 1000.0))))
   :width (get-in metadata [:videoMediaMetadata :width])
   :height (get-in metadata [:videoMediaMetadata :height])})

(defn- fallback-source-summary [gateway access-token file-id]
  (let [metadata (drive/source-metadata! gateway access-token file-id)
        prepared (contract/attach-source-selection-metadata
                  {:source-video {:file-id file-id}}
                  metadata)]
    (source-summary (get-in prepared [:source-video :metadata]))))

(defn- fallback-listing [gateway access-token folder-id bootstrap-file-ids]
  {:listingMode "fixed-bootstrap"
   :folderId folder-id
   :sources (->> bootstrap-file-ids
                 (map #(fallback-source-summary gateway access-token %))
                 (sort-by (juxt (comp str/lower-case :fileName) :fileId))
                 vec)})

(defn- resolved-bootstrap-file-ids [folder-id bootstrap-file-ids]
  (if (some? bootstrap-file-ids)
    bootstrap-file-ids
    (when (= folder-id fixed-folder-id)
      fixed-folder-bootstrap-file-ids)))

(defn default-source-listing
  [auth-system subject folder-id bootstrap-file-ids]
  (let [{:keys [access-token]} (auth/drive-access! auth-system subject)
        gateway (:drive auth-system)
        folder-id (or folder-id fixed-folder-id)
        bootstrap-file-ids (resolved-bootstrap-file-ids folder-id
                                                        bootstrap-file-ids)]
    (cond
      (satisfies? drive/FolderSourceListingGateway gateway)
      (try
        (let [sources (->> (drive/list-folder-sources! gateway access-token folder-id)
                           (map source-summary)
                           (sort-by (juxt (comp str/lower-case :fileName) :fileId))
                           vec)]
          (if (and (empty? sources)
                   (satisfies? drive/SourceGateway gateway)
                   (seq bootstrap-file-ids))
            (fallback-listing gateway access-token folder-id bootstrap-file-ids)
            {:listingMode "folder-enumeration"
             :folderId folder-id
             :sources sources}))
        (catch Throwable error
          (if (and (satisfies? drive/SourceGateway gateway)
                   (seq bootstrap-file-ids))
            (fallback-listing gateway access-token folder-id bootstrap-file-ids)
            (throw error))))

      (and (satisfies? drive/SourceGateway gateway) (seq bootstrap-file-ids))
      (fallback-listing gateway access-token folder-id bootstrap-file-ids)

      :else
      (throw (ex-info "Proto source listing dependencies are incomplete"
                      {:type ::invalid-configuration})))))

(defn list-sources!
  [{:keys [proto-source-loader proto-folder-id proto-source-bootstrap
           proto-source-bootstrap-only? auth-system]}
   user]
  (if proto-source-loader
    (proto-source-loader {:user user
                          :folder-id (or proto-folder-id fixed-folder-id)})
    (let [folder-id (or proto-folder-id fixed-folder-id)]
      (if proto-source-bootstrap-only?
        (let [{:keys [access-token]} (auth/drive-access!
                                      auth-system (:subject user))]
          (fallback-listing (:drive auth-system)
                            access-token
                            folder-id
                            proto-source-bootstrap))
        (default-source-listing auth-system
                                (:subject user)
                                folder-id
                                proto-source-bootstrap)))))

(def signed-out-page
  (str
   "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
   "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
   "<meta name=\"color-scheme\" content=\"dark\">"
   (icon-links)
   "<title>Alpha Compose Proto</title><style>"
   (ui/theme-style)
   ".shell{max-width:76rem;margin:0 auto;padding:1.25rem 1rem 3rem}"
   ".hero{display:grid;grid-template-columns:minmax(0,1.15fr) minmax(18rem,.85fr);gap:1rem;align-items:stretch}"
   ".hero-card,.hero-copy{padding:1.4rem;border:1px solid var(--color-border);border-radius:1.2rem;background:var(--color-surface);box-shadow:var(--shadow-surface)}"
   ".hero-card{background:var(--color-surface-strong);border-color:var(--color-border-strong)}"
   "h1{margin:.35rem 0 1rem;font-size:clamp(2.4rem,6vw,4.8rem);line-height:1.02;letter-spacing:-.06em}"
   "p{margin:.4rem 0 0}.actions{display:flex;gap:.75rem;flex-wrap:wrap;margin-top:1.2rem}"
   ".pill{display:inline-flex;align-items:center;gap:.35rem;padding:.35rem .65rem;border:1px solid var(--color-border-strong);border-radius:999px;background:#081729;color:var(--color-muted);font:700 .75rem ui-monospace,SFMono-Regular,Menlo,monospace}"
   "@media(max-width:760px){.hero{grid-template-columns:1fr}}"
   "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
   "<header class=\"product-header\"><a class=\"brand\" href=\"/\">Alpha Compose Proto</a></header>"
   "<main class=\"hero\"><section class=\"hero-copy\">"
   "<div class=\"eyebrow\">Authenticated playback harness</div>"
   "<h1>Timing workspace playback prototype</h1>"
   "<p class=\"muted\">This separate prototype app is the fixed-folder browser playback harness for "
   "<code>proto.alphacompose.com</code>. It lists timing-workspace sources, prepares private playback, "
   "and keeps debug output page-local and privacy-bounded.</p>"
   "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/proto-login/start\">Continue with Google</a></div>"
   "</section><aside class=\"hero-card\"><div class=\"eyebrow\">Scope</div>"
   "<p class=\"pill\">Folder " fixed-folder-id "</p>"
   "<p class=\"muted\">Direct playback only. No transcode fallback. No generic Drive browsing. "
   "No main Alpha Compose navigation.</p></aside></main></div></body></html>"))

(defn page [{:keys [user csrf folder-id]}]
  (let [csrf-json (json/write-str csrf)
        email-json (json/write-str (:email user))
        folder-json (json/write-str folder-id)]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"dark\">"
     (icon-links)
     "<title>Alpha Compose Proto</title><style>"
     (ui/theme-style)
     ".shell{max-width:88rem;margin:0 auto;padding:1rem 1rem 3rem}"
     ".topbar{display:flex;align-items:center;justify-content:space-between;gap:1rem;flex-wrap:wrap;margin:0 0 1rem;padding:1rem 1.1rem;border:1px solid var(--color-border);border-radius:1rem;background:var(--color-surface);box-shadow:var(--shadow-surface)}"
     ".brandline h1{margin:.3rem 0 0;font-size:clamp(2rem,4vw,3.4rem);line-height:1.02;letter-spacing:-.05em}.brandline p{margin:.45rem 0 0}"
     ".session{display:flex;align-items:flex-end;gap:.75rem;flex-wrap:wrap}.session form{margin:0}"
     ".pill{display:inline-flex;align-items:center;gap:.35rem;padding:.35rem .65rem;border:1px solid var(--color-border-strong);border-radius:999px;background:#081729;color:var(--color-muted);font:700 .75rem ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".field{display:grid;gap:.35rem;min-width:min(24rem,calc(100vw - 4rem))}.field-label{color:var(--color-muted);font-size:.72rem;font-weight:800;letter-spacing:.06em;text-transform:uppercase}"
     "select{width:100%;padding:.7rem .85rem;border:1px solid var(--color-border-strong);border-radius:.8rem;background:#081729;color:var(--color-text);font:700 .82rem/1.2 ui-monospace,SFMono-Regular,Menlo,monospace}"
     "select:disabled{opacity:.7;cursor:not-allowed}"
     ".layout{display:grid;grid-template-columns:minmax(18rem,24rem) minmax(0,1fr);gap:1rem;align-items:start}"
     ".card{padding:1.1rem;border:1px solid var(--color-border);border-radius:1rem;background:var(--color-surface);box-shadow:var(--shadow-surface)}"
     ".source-list{display:grid;gap:.6rem}.source{display:grid;gap:.45rem;padding:.8rem;border:1px solid var(--color-border);border-radius:.8rem;background:var(--color-surface-soft);text-align:left}"
     ".source strong,.source small{display:block;overflow-wrap:anywhere}.source-meta{display:flex;gap:.45rem;flex-wrap:wrap;color:var(--color-muted);font:700 .75rem ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".player-shell{display:grid;gap:1rem}.stage{position:relative;display:flex;align-items:center;justify-content:center;aspect-ratio:16/9;overflow:hidden;border:1px solid var(--color-border-strong);border-radius:1rem;background:#020a14}.stage video{width:100%;height:100%;object-fit:contain;background:#020a14}"
     ".timing-grid,.debug-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.timing-card,.debug-card{min-width:0}.timing-card dl,.source-card dl{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:.4rem .75rem;margin:0}.timing-card dt,.source-card dt{color:var(--color-muted);font-size:.78rem;font-weight:800;text-transform:uppercase;letter-spacing:.06em}.timing-card dd,.source-card dd{margin:0;overflow-wrap:anywhere;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".status{min-height:1.4rem;color:var(--color-muted)}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
     ".preparation-panel{display:grid;gap:.75rem;margin:.85rem 0 1rem;padding:.9rem;border:1px solid var(--color-border-strong);border-radius:.8rem;background:var(--color-surface-soft)}.preparation-panel[hidden]{display:none}.preparation-panel h3,.preparation-panel p{margin:0}.preparation-actions{display:flex;gap:.6rem;flex-wrap:wrap}.preparation-actions button[hidden]{display:none}"
     "pre{margin:0;padding:.85rem;border-radius:.8rem;background:#071626;color:#dbefff;overflow:auto;font:500 .79rem/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}"
     "button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem .95rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text)}"
     "button:hover{filter:brightness(1.08)}button.primary{background:var(--color-accent);color:var(--color-accent-ink);border-color:var(--color-accent)}"
     "@media(max-width:960px){.layout{grid-template-columns:1fr}.timing-grid,.debug-grid{grid-template-columns:1fr}}"
     "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
     "<header class=\"topbar\"><div class=\"brandline\"><div class=\"eyebrow\">Separate proto app</div>"
     "<h1>Timing workspace playback prototype</h1>"
     "<p class=\"muted\">Standalone playback harness for <code>proto.alphacompose.com</code>. "
     "Fixed-folder scope, direct playback only, page-local debug only.</p></div>"
     "<div class=\"session\"><div><div class=\"pill\">" (ui/escape-html (:email user)) "</div></div>"
     "<label class=\"field\" for=\"source-select\"><span class=\"field-label\">Files in folder "
     (ui/escape-html folder-id)
     "</span><select id=\"source-select\" disabled><option value=\"\">Loading folder files…</option></select></label>"
     "<form method=\"post\" action=\"/v1/auth/logout\"><input type=\"hidden\" name=\"csrf\" value=\""
     (ui/escape-html csrf)
     "\"><button type=\"submit\">Sign out</button></form></div></header>"
     "<main class=\"layout\"><aside class=\"card\"><div class=\"eyebrow\">Sources</div>"
     "<h2>Fixed folder videos</h2><p class=\"muted\">Each source stays listed. Unsupported browser playback keeps the file visible with explicit evidence.</p>"
     "<p id=\"source-status\" class=\"status\" role=\"status\">Loading the fixed timing-workspace folder…</p>"
     "<div id=\"source-list\" class=\"source-list\"></div></aside>"
     "<section class=\"player-shell\"><section class=\"card\"><div class=\"eyebrow\">Player</div>"
     "<h2 id=\"selected-title\">Choose a listed video</h2>"
     "<p id=\"player-status\" class=\"status\" role=\"status\">No source selected yet.</p>"
     "<section id=\"preparation-panel\" class=\"preparation-panel\" aria-labelledby=\"preparation-title\" hidden>"
     "<h3 id=\"preparation-title\">Browser preview preparation</h3>"
     "<p id=\"preparation-summary\" role=\"status\" aria-live=\"polite\"></p>"
     "<p id=\"preparation-limits\" class=\"muted\">Up to 8 minutes and 2 GB. Preparation can take up to 15 minutes. Ready previews expire after 24 hours.</p>"
     "<div class=\"preparation-actions\">"
     "<button id=\"prepare-browser-preview\" class=\"primary\" type=\"button\">Prepare browser preview</button>"
     "<button id=\"wait-for-preview\" type=\"button\" hidden>Wait for existing preview</button>"
     "<button id=\"cancel-preview\" type=\"button\" hidden>Cancel preparation</button>"
     "<button id=\"retry-preview\" type=\"button\" hidden>Retry preparation</button>"
     "</div></section>"
     "<div class=\"stage\"><video id=\"proto-player\" controls preload=\"metadata\" playsinline></video></div>"
     "</section>"
     "<section class=\"timing-grid\">"
     "<article class=\"card source-card\"><div class=\"eyebrow\">Source</div><h2>Selected file</h2><dl id=\"source-summary\">"
     "<dt>Listing mode</dt><dd id=\"summary-listing\">Unknown</dd>"
     "<dt>File</dt><dd id=\"summary-file\">No source selected</dd>"
     "<dt>MIME type</dt><dd id=\"summary-mime\">-</dd>"
     "<dt>Duration</dt><dd id=\"summary-duration\">-</dd>"
     "<dt>Frame size</dt><dd id=\"summary-size\">-</dd></dl></article>"
     "<article class=\"card timing-card\"><div class=\"eyebrow\">Timing</div><h2>Recording clock</h2><dl>"
     "<dt>Recording start</dt><dd id=\"timing-start\">Unknown</dd>"
     "<dt>Recording end</dt><dd id=\"timing-end\">Unknown</dd>"
     "<dt>Timing state</dt><dd id=\"timing-state\">Waiting for source selection</dd>"
     "<dt>Confidence</dt><dd id=\"timing-confidence\">-</dd></dl></article>"
     "</section>"
     "<section class=\"debug-grid\">"
     "<article class=\"card debug-card\"><div class=\"eyebrow\">Browser + server</div><h2>Playback preparation</h2><pre id=\"prep-debug\">{\"status\":\"idle\"}</pre></article>"
     "<article class=\"card debug-card\"><div class=\"eyebrow\">Buffered media</div><h2>Range and buffering</h2><pre id=\"range-debug\">{\"status\":\"idle\"}</pre></article>"
     "</section></section></main>"
     "<script>(function(){"
     "const csrf=" csrf-json ",viewerEmail=" email-json ",fixedFolderId=" folder-json ",sourceSelect=document.getElementById('source-select'),listNode=document.getElementById('source-list'),sourceStatus=document.getElementById('source-status'),playerStatus=document.getElementById('player-status'),selectedTitle=document.getElementById('selected-title'),prepDebug=document.getElementById('prep-debug'),rangeDebug=document.getElementById('range-debug'),preparationPanel=document.getElementById('preparation-panel'),preparationSummary=document.getElementById('preparation-summary'),prepareButton=document.getElementById('prepare-browser-preview'),waitButton=document.getElementById('wait-for-preview'),cancelButton=document.getElementById('cancel-preview'),retryButton=document.getElementById('retry-preview');let player=document.getElementById('proto-player');"
     "const summaryListing=document.getElementById('summary-listing'),summaryFile=document.getElementById('summary-file'),summaryMime=document.getElementById('summary-mime'),summaryDuration=document.getElementById('summary-duration'),summarySize=document.getElementById('summary-size'),timingStart=document.getElementById('timing-start'),timingEnd=document.getElementById('timing-end'),timingState=document.getElementById('timing-state'),timingConfidence=document.getElementById('timing-confidence');"
     "const state={viewerEmail,fixedFolderId,rangeAdapter:'pending',listing:null,selected:null,sourcesById:new Map(),analysis:null,analysisFailure:null,support:null,playback:{heuristic:null,actual:'not_attempted',kind:null},timing:null,session:null,derivative:null,rangeProbe:null,media:{events:[],buffered:[],currentTime:0,duration:null,error:false}};let selectionGeneration=0,preparationGeneration=0,pollTimer=null;"
     "function setText(node,value){node.textContent=value;}function showStatus(node,text,kind){node.textContent=text;node.className='status'+(kind?' '+kind:'');}"
     "function formatDuration(seconds){return Number.isFinite(seconds)&&seconds>0?seconds.toFixed(3)+' s':'Unknown';}"
     "function formatFrameSize(source){return source&&Number.isFinite(source.width)&&Number.isFinite(source.height)?source.width+' x '+source.height:'Unknown';}"
     "function bufferedRanges(playerNode=player){const ranges=[];const buffered=playerNode.buffered;for(let i=0;i<buffered.length;i++)ranges.push([Number(buffered.start(i).toFixed(3)),Number(buffered.end(i).toFixed(3))]);return ranges;}"
     "function parseRecordingStart(inspection){if(!inspection||!Array.isArray(inspection.candidates))return null;const index=Number.isInteger(inspection.recommendedIndex)?inspection.recommendedIndex:null;const candidate=index!==null?inspection.candidates[index]:null;return typeof (candidate&&candidate.value)==='string'?candidate.value:null;}"
     "function offsetMinutes(instant){const match=typeof instant==='string'&&instant.match(/(Z|[+-]\\d{2}:\\d{2})$/);if(!match)return null;const token=match[1];if(token==='Z')return 0;const sign=token[0]==='-'?-1:1,hours=Number(token.slice(1,3)),minutes=Number(token.slice(4,6));return Number.isFinite(hours)&&Number.isFinite(minutes)?sign*(hours*60+minutes):null;}"
     "function formatWithOffset(millis,minutes){if(!Number.isFinite(millis)||!Number.isFinite(minutes))return null;const shifted=new Date(millis+minutes*60000).toISOString();return minutes===0?shifted:shifted.replace('Z',(minutes<0?'-':'+')+String(Math.floor(Math.abs(minutes)/60)).padStart(2,'0')+':'+String(Math.abs(minutes)%60).padStart(2,'0'));}"
     "function deriveRecordingEnd(start,duration){const millis=Date.parse(start),minutes=offsetMinutes(start);return Number.isFinite(millis)&&Number.isFinite(duration)&&duration>0&&Number.isFinite(minutes)?formatWithOffset(millis+duration*1000,minutes):null;}"
     "function codecMime(evidence){const container=evidence&&evidence.container||{},video=evidence&&evidence.video||{},audio=evidence&&evidence.audio||{};const mimeByFormat={mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm',matroska:'video/x-matroska',avi:'video/x-msvideo',mpeg:'video/mpeg',ogg:'video/ogg'};const codecParts=[video.codecTag||video.codec,audio.codec].filter(Boolean);const base=mimeByFormat[container.format]||null;return base?(codecParts.length?base+'; codecs=\"'+codecParts.join(', ')+'\"':base):'';}"
     "async function browserSupport(evidence){const mime=codecMime(evidence),nativeResult=mime?player.canPlayType(mime):'';let webCodecs='unavailable';if(window.VideoDecoder&&typeof window.VideoDecoder.isConfigSupported==='function'){try{const config={codec:(evidence&&evidence.video&&(evidence.video.codecTag||evidence.video.codec))||''};if(config.codec){const result=await window.VideoDecoder.isConfigSupported(config);webCodecs=result&&result.supported===true?'supported':'unsupported';}else webCodecs='unsupported';}catch(_error){webCodecs='unsupported';}}const nativePlayable=nativeResult==='probably'||nativeResult==='maybe';const supported=(webCodecs==='supported'&&nativePlayable)||(webCodecs==='unavailable'&&nativePlayable);return {supported,mime,nativeResult,webCodecs,reason:supported?null:(nativePlayable?'webcodecs_required':'browser_rejected')};}"
     "async function playbackAnalysisFailure(response){let body={};try{body=await response.json();}catch(_error){}return {status:response.status,error:typeof body.error==='string'?body.error:'unknown_error',retryable:body.retryable===true};}"
     "function safeStringFields(value,names){const result={};for(const name of names){if(value&&typeof value[name]==='string')result[name]=value[name];}return result;}function safePlaybackAnalysis(analysis){if(!analysis||!analysis.evidence)return null;const evidence=analysis.evidence;return {evidence:{container:safeStringFields(evidence.container,['format','majorBrand']),video:safeStringFields(evidence.video,['codec','codecTag','profile','pixelFormat']),audio:safeStringFields(evidence.audio,['codec'])}};}"
     "function renderPreparationDebug(){const derivative=state.derivative&&state.derivative.resource;prepDebug.textContent=JSON.stringify({rangeAdapter:state.rangeAdapter,listingMode:state.listing&&state.listing.listingMode||null,selected:state.selected&&{mimeType:state.selected.mimeType,size:state.selected.size,durationSeconds:state.selected.durationSeconds,width:state.selected.width,height:state.selected.height},analysis:safePlaybackAnalysis(state.analysis),analysisFailure:state.analysisFailure,support:state.support,playback:state.playback,timing:state.timing&&{status:state.timing.status,ambiguous:state.timing.ambiguous,recommendedIndex:state.timing.recommendedIndex,candidates:state.timing.candidates,durationSeconds:state.timing.durationSeconds},session:state.session&&{kind:state.playback.kind,contentType:state.session.contentType,size:state.session.size},derivative:derivative&&{state:derivative.state,attempt:derivative.attempt,profileVersion:derivative.profileVersion,expiresAt:derivative.expiresAt||null,failureCode:derivative.failureCode||null,retryable:typeof derivative.retryable==='boolean'?derivative.retryable:null,requestId:state.derivative.requestId||'unavailable',cacheHit:state.derivative.cacheHit===true,polling:state.derivative.polling===true},preparationError:state.derivative&&state.derivative.error||null},null,2);}"
     "function renderRangeDebug(){rangeDebug.textContent=JSON.stringify({rangeProbe:state.rangeProbe,media:state.media},null,2);}"
     "function syncSourceSelect(sources){sourceSelect.replaceChildren();const placeholder=document.createElement('option');placeholder.value='';placeholder.textContent=sources.length?'Choose a file from the folder':'No folder files available';sourceSelect.append(placeholder);for(const source of sources){const option=document.createElement('option');option.value=source.fileId;option.textContent=source.fileName;sourceSelect.append(option);}sourceSelect.disabled=!sources.length;sourceSelect.value=state.selected&&state.selected.fileId||'';}"
     "function updateTiming(){const inspection=state.timing,start=parseRecordingStart(inspection),duration=state.selected&&state.selected.durationSeconds||inspection&&inspection.durationSeconds||null,end=start?deriveRecordingEnd(start,duration):null;setText(timingStart,start||'Unknown');setText(timingEnd,end||'Unknown');if(!inspection){setText(timingState,'Waiting for source selection');setText(timingConfidence,'-');return;}if(inspection.ambiguous){setText(timingState,'Ambiguous');setText(timingConfidence,'Multiple credible recording-clock candidates');}else if(start){setText(timingState,'Candidate');setText(timingConfidence,'Recommended container candidate');}else if(Number.isFinite(duration)&&duration>0){setText(timingState,'Duration only');setText(timingConfidence,'Recording start unknown');}else{setText(timingState,'Unknown');setText(timingConfidence,'No trustworthy evidence found');}}"
     "function activePreparation(){return state.derivative&&state.derivative.resource||null;}function preparationIsActive(resource=activePreparation()){return !!resource&&['queued','running','cancellation-requested'].includes(resource.state);}function samePreparationSource(){return !!(state.derivative&&state.selected&&state.derivative.source&&state.derivative.source.fileId===state.selected.fileId);}"
     "function preparationStateText(resource){if(!resource)return '';const code=resource.failureCode||'derivative_failed',retryable=resource.retryable===true?'Yes':'No',requestId=state.derivative&&state.derivative.requestId||'unavailable',failureSuffix=' Code: '+code+'. Retryable: '+retryable+'. Request ID: '+requestId+'.';switch(resource.state){case 'queued':return 'Queued for browser preview preparation.';case 'running':return 'Preparing browser preview. Work is bounded to 15 minutes.';case 'cancellation-requested':return 'Cancellation requested. Waiting for preparation to stop.';case 'succeeded':return state.derivative&&state.derivative.cacheHit?'Cached browser preview ready. No new preparation work was started.':'Browser preview preparation completed.';case 'failed':return 'Browser preview failed.'+failureSuffix;case 'cancelled':return 'Browser preview preparation was cancelled. Retryable: Yes. Request ID: '+requestId+'.';case 'expired':return 'Browser preview expired.'+failureSuffix;case 'revoked':return 'Browser preview access was revoked.'+failureSuffix;default:return 'Browser preview state: '+String(resource.state||'unknown')+'.';}}"
     "function renderPreparationControls(){const rejected=state.support&&state.support.supported===false,resource=activePreparation(),same=samePreparationSource(),error=state.derivative&&state.derivative.error;prepareButton.hidden=true;waitButton.hidden=true;cancelButton.hidden=true;retryButton.hidden=true;prepareButton.textContent='Prepare browser preview';waitButton.textContent='Wait for existing preview';if(error&&same){preparationPanel.hidden=false;preparationSummary.textContent='Browser preview request failed ('+error.status+', '+error.error+'). Retryable: '+(error.retryable?'Yes':'No')+'. Request ID: '+error.requestId+'.';if(error.retryable&&!resource){prepareButton.textContent='Retry preparation';prepareButton.hidden=false;}else if(error.retryable&&resource&&resource.state==='succeeded'){prepareButton.textContent='Load browser preview';prepareButton.hidden=false;}else if(error.retryable&&preparationIsActive(resource)){waitButton.textContent='Retry status check';waitButton.hidden=false;cancelButton.hidden=false;}else retryButton.hidden=!error.retryable;return;}if(resource&&preparationIsActive(resource)){preparationPanel.hidden=false;preparationSummary.textContent=same?preparationStateText(resource):'A browser preview for another selected source is still '+resource.state+'. Wait for it or cancel it explicitly before preparing this source.';cancelButton.hidden=false;waitButton.hidden=same||state.derivative.polling===true;return;}if(resource&&same){preparationPanel.hidden=false;preparationSummary.textContent=preparationStateText(resource);if(resource.state==='succeeded'){prepareButton.textContent='Load browser preview';prepareButton.hidden=false;}else if((resource.state==='failed'&&resource.retryable===true)||resource.state==='cancelled'){retryButton.hidden=false;}return;}if(rejected){preparationPanel.hidden=false;preparationSummary.textContent='This source is renderable but the browser rejected its original codec. Prepare a private H.264 browser preview only when you choose to.';prepareButton.hidden=false;return;}preparationPanel.hidden=true;}"
     "function updateSummary(){const source=state.selected;setText(summaryListing,state.listing&&state.listing.listingMode||'Unknown');setText(summaryFile,source&&source.fileName||'No source selected');setText(summaryMime,source&&source.mimeType||'-');setText(summaryDuration,formatDuration(source&&source.durationSeconds));setText(summarySize,formatFrameSize(source));sourceSelect.value=source&&source.fileId||'';updateTiming();renderPreparationControls();renderPreparationDebug();renderRangeDebug();}"
     "function stopPreparationPolling(){preparationGeneration+=1;if(pollTimer!==null){clearTimeout(pollTimer);pollTimer=null;}if(state.derivative)state.derivative.polling=false;renderPreparationControls();renderPreparationDebug();}"
     "function responseRequestId(response){const value=response&&response.headers&&typeof response.headers.get==='function'&&response.headers.get('X-Request-Id');return typeof value==='string'&&value?value:'unavailable';}async function responseFailure(response,fallback){let body={};try{body=await response.json();}catch(_error){}return {status:Number(response.status)||0,error:typeof body.error==='string'?body.error:fallback,retryable:body.retryable===true,requestId:typeof body.requestId==='string'?body.requestId:responseRequestId(response)};}"
     "function validDerivativeResource(resource){const states=['queued','running','cancellation-requested','succeeded','failed','cancelled','expired','revoked'];return !!resource&&states.includes(resource.state)&&Number.isInteger(resource.attempt)&&resource.attempt>0&&resource.profileVersion==='h264-aac-1080p25-v1'&&typeof resource.statusUrl==='string'&&/^\\/v1\\/derivative-preparations\\/[0-9A-Fa-f-]{36}$/.test(resource.statusUrl)&&resource.cancelUrl===resource.statusUrl+'/cancel'&&resource.retryUrl===resource.statusUrl+'/retry';}"
     "function validDerivativePlayback(session,statusUrl){return !!session&&typeof session.playbackUrl==='string'&&session.playbackUrl.startsWith(statusUrl+'/playback/')&&/^\\/v1\\/derivative-preparations\\/[0-9A-Fa-f-]{36}\\/playback\\/[0-9A-Fa-f-]{36}$/.test(session.playbackUrl)&&session.contentType==='video/mp4'&&Number.isFinite(session.size)&&session.size>0;}"
     "function schedulePreparationPoll(token){pollTimer=setTimeout(()=>pollPreparation(token),1000);}"
     "async function attachDerivativePlayback(){const active=state.derivative;if(!active||!samePreparationSource()||!active.resource||active.resource.state!=='succeeded')return;const generation=selectionGeneration,token=preparationGeneration;showStatus(playerStatus,'Minting private browser preview playback…');try{const response=await fetch(active.resource.statusUrl+'/playback-sessions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:'{}'});if(generation!==selectionGeneration||token!==preparationGeneration||active!==state.derivative)return;if(!response.ok)throw await responseFailure(response,'derivative_playback_failed');const session=await response.json();if(generation!==selectionGeneration||token!==preparationGeneration||active!==state.derivative)return;if(!validDerivativePlayback(session,active.resource.statusUrl))throw {status:502,error:'invalid_derivative_playback',retryable:true,requestId:'unavailable'};state.session=session;state.playback.kind='derivative';if(!await probeRange(session.playbackUrl,generation))return;if(generation!==selectionGeneration||token!==preparationGeneration||active!==state.derivative)return;state.playback.actual='pending';player.src=session.playbackUrl;showStatus(playerStatus,'Browser preview prepared. Awaiting media load evidence.','success');updateSummary();player.load();}catch(error){if(generation===selectionGeneration&&token===preparationGeneration&&active===state.derivative){active.error=error&&typeof error.error==='string'?error:{status:0,error:'derivative_playback_failed',retryable:true,requestId:'unavailable'};showStatus(playerStatus,'Browser preview playback failed.','error');updateSummary();}}}"
     "async function pollPreparation(token){const active=state.derivative;if(!active||token!==preparationGeneration)return;active.polling=true;renderPreparationControls();renderPreparationDebug();try{const response=await fetch(active.resource.statusUrl,{credentials:'same-origin'});if(token!==preparationGeneration||active!==state.derivative)return;if(!response.ok)throw await responseFailure(response,'derivative_status_failed');const resource=await response.json();if(token!==preparationGeneration||active!==state.derivative)return;if(!validDerivativeResource(resource))throw {status:502,error:'invalid_derivative_status',retryable:true,requestId:'unavailable'};active.resource=resource;active.requestId=responseRequestId(response);active.polling=false;active.error=null;renderPreparationControls();renderPreparationDebug();if(resource.state==='succeeded'){if(samePreparationSource())await attachDerivativePlayback();else showStatus(playerStatus,'The existing browser preview is ready. Reselect its source to load it.','success');return;}if(preparationIsActive(resource)){schedulePreparationPoll(token);return;}showStatus(playerStatus,preparationStateText(resource),resource.state==='failed'||resource.state==='expired'||resource.state==='revoked'?'error':null);}catch(error){if(token===preparationGeneration&&active===state.derivative){active.polling=false;active.error=error&&typeof error.error==='string'?error:{status:0,error:'derivative_status_failed',retryable:true,requestId:'unavailable'};showStatus(playerStatus,'Browser preview status check failed.','error');updateSummary();}}}"
     "function startPreparationPolling(){if(!state.derivative||!preparationIsActive())return;stopPreparationPolling();const token=preparationGeneration;state.derivative.polling=true;state.derivative.error=null;renderPreparationControls();renderPreparationDebug();schedulePreparationPoll(token);}"
     "function idempotencyKey(){const random=window.crypto&&typeof window.crypto.randomUUID==='function'?window.crypto.randomUUID():String(Date.now())+'-'+Math.random().toString(16).slice(2);return 'browser-preview-'+random;}"
     "async function submitPreparation(){if(!state.selected||!state.support||state.support.supported)return;const existing=activePreparation();if(state.derivative&&preparationIsActive(existing)){renderPreparationControls();return;}if(existing&&samePreparationSource()&&existing.state==='succeeded'){state.derivative.error=null;await attachDerivativePlayback();return;}stopPreparationPolling();const source=state.selected,generation=selectionGeneration;showStatus(playerStatus,'Submitting browser preview preparation…');try{const response=await fetch('/v1/derivative-preparations',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf,'Idempotency-Key':idempotencyKey()},body:JSON.stringify({fileId:source.fileId})});if(generation!==selectionGeneration)return;if(!response.ok){const error=await responseFailure(response,'derivative_submission_failed');if(generation!==selectionGeneration)return;state.derivative={source,resource:null,requestId:error.requestId,cacheHit:false,polling:false,error};showStatus(playerStatus,'Browser preview preparation was not admitted.','error');updateSummary();return;}const resource=await response.json();if(generation!==selectionGeneration)return;if(!validDerivativeResource(resource))throw new Error('invalid_derivative_submission');state.derivative={source,resource,requestId:responseRequestId(response),cacheHit:response.status===200&&resource.state==='succeeded',polling:false,error:null};updateSummary();if(resource.state==='succeeded')await attachDerivativePlayback();else if(preparationIsActive(resource))startPreparationPolling();else showStatus(playerStatus,preparationStateText(resource),resource.state==='failed'?'error':null);}catch(error){if(generation===selectionGeneration){state.derivative={source,resource:null,requestId:'unavailable',cacheHit:false,polling:false,error:{status:0,error:'derivative_submission_failed',retryable:true,requestId:'unavailable'}};showStatus(playerStatus,'Browser preview preparation failed.','error');updateSummary();}}}"
     "async function cancelPreparation(){const active=state.derivative;if(!active||!preparationIsActive())return;stopPreparationPolling();showStatus(playerStatus,'Requesting browser preview cancellation…');try{const response=await fetch(active.resource.cancelUrl,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:'{}'});if(active!==state.derivative)return;if(!response.ok)throw await responseFailure(response,'derivative_cancel_failed');active.resource=await response.json();active.requestId=responseRequestId(response);active.error=null;updateSummary();if(preparationIsActive(active.resource))startPreparationPolling();else showStatus(playerStatus,preparationStateText(active.resource));}catch(error){if(active===state.derivative){active.error=error&&typeof error.error==='string'?error:{status:0,error:'derivative_cancel_failed',retryable:true,requestId:'unavailable'};showStatus(playerStatus,'Browser preview cancellation failed.','error');updateSummary();}}}"
     "async function retryPreparation(){const active=state.derivative;if(!active||!samePreparationSource())return;stopPreparationPolling();showStatus(playerStatus,'Retrying browser preview preparation…');try{const response=await fetch(active.resource.retryUrl,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:'{}'});if(active!==state.derivative)return;if(!response.ok)throw await responseFailure(response,'derivative_retry_failed');active.resource=await response.json();active.requestId=responseRequestId(response);active.cacheHit=false;active.error=null;updateSummary();if(preparationIsActive(active.resource))startPreparationPolling();}catch(error){if(active===state.derivative){active.error=error&&typeof error.error==='string'?error:{status:0,error:'derivative_retry_failed',retryable:true,requestId:'unavailable'};showStatus(playerStatus,'Browser preview retry failed.','error');updateSummary();}}}"
     "function rememberMedia(playerNode,eventName,generation){if(generation!==selectionGeneration||playerNode!==player)return;state.media.events=[...state.media.events.slice(-11),{event:eventName,currentTime:Number((playerNode.currentTime||0).toFixed(3)),duration:Number.isFinite(playerNode.duration)?Number(playerNode.duration.toFixed(3)):null,buffered:bufferedRanges(playerNode)}];state.media.buffered=bufferedRanges(playerNode);state.media.currentTime=Number((playerNode.currentTime||0).toFixed(3));state.media.duration=Number.isFinite(playerNode.duration)?Number(playerNode.duration.toFixed(3)):null;renderRangeDebug();}"
     "function bindPlayerEvents(playerNode,generation){['loadedmetadata','progress','seeking','seeked','waiting','canplay','timeupdate','error'].forEach(name=>playerNode.addEventListener(name,()=>{if(generation!==selectionGeneration||playerNode!==player)return;if(name==='error'){state.media.error=true;if(state.playback.actual==='pending'){state.playback.actual='actual_media_load_failed';showStatus(playerStatus,state.playback.kind==='derivative'?'Browser preview failed during media load.':'Private playback attempt failed during actual media load. See bounded evidence below.','error');renderPreparationDebug();}}else if((name==='loadedmetadata'||name==='canplay')&&state.playback.actual==='pending'){state.playback.actual='actual_media_loaded';showStatus(playerStatus,state.playback.kind==='derivative'?'Browser preview ready. Play or seek the private preview.':state.playback.heuristic==='heuristic_rejected'?'Private playback loaded after a heuristic rejection. See bounded evidence below.':'Private playback loaded. Seek the player and watch the range debug panel update.','success');renderPreparationDebug();}rememberMedia(playerNode,name,generation);}));}function replacePlayer(generation){const next=player.cloneNode(false);player.replaceWith(next);player=next;bindPlayerEvents(next,generation);}bindPlayerEvents(player,selectionGeneration);"
     "async function probeRange(url,generation){const response=await fetch(url,{credentials:'same-origin',headers:{Range:'bytes=0-4095'}});if(generation!==selectionGeneration)return false;await response.arrayBuffer();if(generation!==selectionGeneration)return false;state.rangeProbe={status:response.status,contentRange:response.headers.get('Content-Range'),contentLength:response.headers.get('Content-Length'),contentType:response.headers.get('Content-Type')};renderRangeDebug();return true;}"
     "async function selectSource(source){stopPreparationPolling();const generation=++selectionGeneration;replacePlayer(generation);state.selected=source;state.analysis=null;state.analysisFailure=null;state.support=null;state.playback={heuristic:null,actual:'not_attempted',kind:null};state.timing=null;state.session=null;state.rangeProbe=null;state.media={events:[],buffered:[],currentTime:0,duration:null,error:false};player.load();selectedTitle.textContent=source.fileName;showStatus(playerStatus,'Inspecting browser playback support and recording clock…');updateSummary();try{const [analysisResponse,timingResponse]=await Promise.all([fetch('/v1/drive/playback-analyses',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})}),fetch('/v1/drive/recording-clock-inspections',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})})]);if(generation!==selectionGeneration)return;if(!analysisResponse.ok){state.analysisFailure=await playbackAnalysisFailure(analysisResponse);if(generation!==selectionGeneration)return;throw new Error('Playback analysis failed ('+state.analysisFailure.status+', '+state.analysisFailure.error+')');}state.analysis=await analysisResponse.json();if(generation!==selectionGeneration)return;state.support=await browserSupport(state.analysis&&state.analysis.evidence);if(generation!==selectionGeneration)return;state.playback.heuristic=state.support.supported?'heuristic_supported':'heuristic_rejected';if(timingResponse.ok){state.timing=await timingResponse.json();if(generation!==selectionGeneration)return;}if(!state.support.supported){showStatus(playerStatus,'The original codec is not supported by this browser. Choose Prepare browser preview to continue.');updateSummary();return;}const sessionResponse=await fetch('/v1/drive/playback-sessions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})});if(generation!==selectionGeneration)return;if(!sessionResponse.ok)throw new Error('Playback session failed');state.session=await sessionResponse.json();if(generation!==selectionGeneration)return;if(!state.session||typeof state.session.playbackUrl!=='string'||!state.session.playbackUrl.startsWith('/v1/drive/playback/'))throw new Error('Playback session returned an invalid private playback URL');if(!await probeRange(state.session.playbackUrl,generation))return;if(generation!==selectionGeneration)return;state.playback.kind='direct';state.playback.actual='pending';player.src=state.session.playbackUrl;showStatus(playerStatus,'Private playback prepared. Awaiting actual media load evidence.','success');updateSummary();player.load();}catch(error){if(generation===selectionGeneration){showStatus(playerStatus,error&&error.message?error.message:'Playback preparation failed','error');renderPreparationDebug();renderRangeDebug();}}}"
     "function renderSources(sources){state.sourcesById=new Map(sources.map(source=>[source.fileId,source]));syncSourceSelect(sources);listNode.replaceChildren();if(!sources.length){showStatus(sourceStatus,'No supported videos were reachable from the fixed workspace under the current authorization model.','error');updateSummary();return;}showStatus(sourceStatus,'Choose any listed video from the fixed workspace or the header selector.','success');for(const source of sources){const button=document.createElement('button'),meta=document.createElement('span');button.type='button';button.className='source';button.innerHTML='<strong></strong><small></small>';button.querySelector('strong').textContent=source.fileName;button.querySelector('small').textContent=source.mimeType;meta.className='source-meta';meta.textContent=[formatDuration(source.durationSeconds),formatFrameSize(source),Number.isFinite(source.size)?source.size+' bytes':'Unknown size'].join(' · ');button.append(meta);button.addEventListener('click',()=>selectSource(source));listNode.append(button);}}"
     "async function loadSources(){showStatus(sourceStatus,'Loading the fixed timing-workspace folder…');try{const response=await fetch('/v1/proto/sources',{credentials:'same-origin'});if(!response.ok)throw new Error('Fixed-folder listing failed');state.listing=await response.json();renderSources(Array.isArray(state.listing.sources)?state.listing.sources:[]);updateSummary();}catch(error){sourceSelect.replaceChildren();const option=document.createElement('option');option.value='';option.textContent='Folder listing failed';sourceSelect.append(option);sourceSelect.disabled=true;showStatus(sourceStatus,error&&error.message?error.message:'Fixed-folder listing failed','error');renderPreparationDebug();renderRangeDebug();}}"
     "async function prepareRangeAdapter(){if(location.protocol==='file:'){state.rangeAdapter='fixture';return;}if(!('serviceWorker' in navigator)){state.rangeAdapter='unsupported';throw new Error('Private range adapter is unavailable in this browser');}const controlled=navigator.serviceWorker.controller?Promise.resolve():new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Private range adapter did not become ready')),5000);navigator.serviceWorker.addEventListener('controllerchange',()=>{clearTimeout(timer);resolve();},{once:true});});await navigator.serviceWorker.register('/proto-playback-range-worker.js',{scope:'/',updateViaCache:'none'});await navigator.serviceWorker.ready;await controlled;state.rangeAdapter='ready';renderPreparationDebug();}"
     "sourceSelect.addEventListener('change',event=>{const source=state.sourcesById.get(event.target.value);if(source)selectSource(source);});"
     "prepareButton.addEventListener('click',submitPreparation);waitButton.addEventListener('click',startPreparationPolling);cancelButton.addEventListener('click',cancelPreparation);retryButton.addEventListener('click',retryPreparation);"
     "async function start(){try{await prepareRangeAdapter();await loadSources();}catch(error){state.rangeAdapter='failed';sourceSelect.disabled=true;showStatus(sourceStatus,error&&error.message?error.message:'Private range adapter failed','error');renderPreparationDebug();renderRangeDebug();}}start();})();</script></div></body></html>")))
