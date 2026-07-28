(ns agg.proto.core
  (:require [agg.auth.core :as auth]
            [agg.contracts.render :as contract]
            [agg.drive.core :as drive]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def fixed-folder-id "1PAoq2qflZB9qX1FEMmi1m43Zb3oZI_lt")

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

(defn default-source-listing
  [auth-system subject folder-id bootstrap-file-ids]
  (let [{:keys [access-token]} (auth/drive-access! auth-system subject)
        gateway (:drive auth-system)
        folder-id (or folder-id fixed-folder-id)]
    (cond
      (satisfies? drive/FolderSourceListingGateway gateway)
      (try
        {:listingMode "folder-enumeration"
         :folderId folder-id
         :sources (->> (drive/list-folder-sources! gateway access-token folder-id)
                       (map source-summary)
                       (sort-by (juxt (comp str/lower-case :fileName) :fileId))
                       vec)}
        (catch Throwable error
          (if (and (satisfies? drive/SourceGateway gateway)
                   (seq bootstrap-file-ids))
            {:listingMode "fixed-bootstrap"
             :folderId folder-id
             :sources (->> bootstrap-file-ids
                           (map #(fallback-source-summary gateway access-token %))
                           (sort-by (juxt (comp str/lower-case :fileName) :fileId))
                           vec)}
            (throw error))))

      (and (satisfies? drive/SourceGateway gateway) (seq bootstrap-file-ids))
      {:listingMode "fixed-bootstrap"
       :folderId folder-id
       :sources (->> bootstrap-file-ids
                     (map #(fallback-source-summary gateway access-token %))
                     (sort-by (juxt (comp str/lower-case :fileName) :fileId))
                     vec)}

      :else
      (throw (ex-info "Proto source listing dependencies are incomplete"
                      {:type ::invalid-configuration})))))

(defn list-sources!
  [{:keys [proto-source-loader proto-folder-id proto-source-bootstrap auth-system]}
   user]
  (if proto-source-loader
    (proto-source-loader {:user user
                          :folder-id (or proto-folder-id fixed-folder-id)})
    (default-source-listing auth-system
                            (:subject user)
                            proto-folder-id
                            proto-source-bootstrap)))

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
   "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start\">Continue with Google</a></div>"
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
     ".layout{display:grid;grid-template-columns:minmax(18rem,24rem) minmax(0,1fr);gap:1rem;align-items:start}"
     ".card{padding:1.1rem;border:1px solid var(--color-border);border-radius:1rem;background:var(--color-surface);box-shadow:var(--shadow-surface)}"
     ".source-list{display:grid;gap:.6rem}.source{display:grid;gap:.45rem;padding:.8rem;border:1px solid var(--color-border);border-radius:.8rem;background:var(--color-surface-soft);text-align:left}"
     ".source strong,.source small{display:block;overflow-wrap:anywhere}.source-meta{display:flex;gap:.45rem;flex-wrap:wrap;color:var(--color-muted);font:700 .75rem ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".player-shell{display:grid;gap:1rem}.stage{position:relative;display:flex;align-items:center;justify-content:center;aspect-ratio:16/9;overflow:hidden;border:1px solid var(--color-border-strong);border-radius:1rem;background:#020a14}.stage video{width:100%;height:100%;object-fit:contain;background:#020a14}"
     ".timing-grid,.debug-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.timing-card,.debug-card{min-width:0}.timing-card dl,.source-card dl{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:.4rem .75rem;margin:0}.timing-card dt,.source-card dt{color:var(--color-muted);font-size:.78rem;font-weight:800;text-transform:uppercase;letter-spacing:.06em}.timing-card dd,.source-card dd{margin:0;overflow-wrap:anywhere;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".status{min-height:1.4rem;color:var(--color-muted)}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
     "pre{margin:0;padding:.85rem;border-radius:.8rem;background:#071626;color:#dbefff;overflow:auto;font:500 .79rem/1.45 ui-monospace,SFMono-Regular,Menlo,monospace}"
     "button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem .95rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text)}"
     "button:hover{filter:brightness(1.08)}button.primary{background:var(--color-accent);color:var(--color-accent-ink);border-color:var(--color-accent)}"
     "@media(max-width:960px){.layout{grid-template-columns:1fr}.timing-grid,.debug-grid{grid-template-columns:1fr}}"
     "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
     "<header class=\"topbar\"><div class=\"brandline\"><div class=\"eyebrow\">Separate proto app</div>"
     "<h1>Timing workspace playback prototype</h1>"
     "<p class=\"muted\">Standalone playback harness for <code>proto.alphacompose.com</code>. "
     "Fixed-folder scope, direct playback only, page-local debug only.</p></div>"
     "<div class=\"session\"><div><div class=\"pill\">" (ui/escape-html (:email user)) "</div>"
     "<div class=\"pill\">Folder " (ui/escape-html folder-id) "</div></div>"
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
     "const csrf=" csrf-json ",viewerEmail=" email-json ",fixedFolderId=" folder-json ",listNode=document.getElementById('source-list'),sourceStatus=document.getElementById('source-status'),player=document.getElementById('proto-player'),playerStatus=document.getElementById('player-status'),selectedTitle=document.getElementById('selected-title'),prepDebug=document.getElementById('prep-debug'),rangeDebug=document.getElementById('range-debug');"
     "const summaryListing=document.getElementById('summary-listing'),summaryFile=document.getElementById('summary-file'),summaryMime=document.getElementById('summary-mime'),summaryDuration=document.getElementById('summary-duration'),summarySize=document.getElementById('summary-size'),timingStart=document.getElementById('timing-start'),timingEnd=document.getElementById('timing-end'),timingState=document.getElementById('timing-state'),timingConfidence=document.getElementById('timing-confidence');"
     "const state={viewerEmail,fixedFolderId,listing:null,selected:null,analysis:null,support:null,timing:null,session:null,rangeProbe:null,media:{events:[],buffered:[],currentTime:0,duration:null,error:false}};"
     "function setText(node,value){node.textContent=value;}function showStatus(node,text,kind){node.textContent=text;node.className='status'+(kind?' '+kind:'');}"
     "function formatDuration(seconds){return Number.isFinite(seconds)&&seconds>0?seconds.toFixed(3)+' s':'Unknown';}"
     "function formatFrameSize(source){return source&&Number.isFinite(source.width)&&Number.isFinite(source.height)?source.width+' x '+source.height:'Unknown';}"
     "function bufferedRanges(){const ranges=[];const buffered=player.buffered;for(let i=0;i<buffered.length;i++)ranges.push([Number(buffered.start(i).toFixed(3)),Number(buffered.end(i).toFixed(3))]);return ranges;}"
     "function parseRecordingStart(inspection){if(!inspection||!Array.isArray(inspection.candidates))return null;const index=Number.isInteger(inspection.recommendedIndex)?inspection.recommendedIndex:null;const candidate=index!==null?inspection.candidates[index]:null;return typeof (candidate&&candidate.value)==='string'?candidate.value:null;}"
     "function deriveRecordingEnd(start,duration){const millis=Date.parse(start);return Number.isFinite(millis)&&Number.isFinite(duration)&&duration>0?new Date(millis+duration*1000).toISOString():null;}"
     "function codecMime(evidence){const container=evidence&&evidence.container||{},video=evidence&&evidence.video||{},audio=evidence&&evidence.audio||{};const mimeByFormat={mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm',matroska:'video/x-matroska',avi:'video/x-msvideo',mpeg:'video/mpeg',ogg:'video/ogg'};const codecParts=[video.codecTag||video.codec,audio.codec].filter(Boolean);const base=mimeByFormat[container.format]||null;return base?(codecParts.length?base+'; codecs=\"'+codecParts.join(', ')+'\"':base):'';}"
     "async function browserSupport(evidence){const mime=codecMime(evidence),nativeResult=mime?player.canPlayType(mime):'';let webCodecs='unavailable';if(window.VideoDecoder&&typeof window.VideoDecoder.isConfigSupported==='function'){try{const config={codec:(evidence&&evidence.video&&(evidence.video.codecTag||evidence.video.codec))||''};if(config.codec){const result=await window.VideoDecoder.isConfigSupported(config);webCodecs=result&&result.supported===true?'supported':'unsupported';}else webCodecs='unsupported';}catch(_error){webCodecs='unsupported';}}const nativePlayable=nativeResult==='probably'||nativeResult==='maybe';const supported=(webCodecs==='supported'&&nativePlayable)||(webCodecs==='unavailable'&&nativePlayable);return {supported,mime,nativeResult,webCodecs,reason:supported?null:(nativePlayable?'webcodecs_required':'browser_rejected')};}"
     "function renderPreparationDebug(){prepDebug.textContent=JSON.stringify({viewerEmail:state.viewerEmail,folderId:state.fixedFolderId,listingMode:state.listing&&state.listing.listingMode||null,selected:state.selected,analysis:state.analysis,support:state.support,timing:state.timing&&{status:state.timing.status,ambiguous:state.timing.ambiguous,recommendedIndex:state.timing.recommendedIndex,candidates:state.timing.candidates,durationSeconds:state.timing.durationSeconds},session:state.session&&{playbackUrl:state.session.playbackUrl,contentType:state.session.contentType,size:state.session.size}},null,2);}"
     "function renderRangeDebug(){rangeDebug.textContent=JSON.stringify({rangeProbe:state.rangeProbe,media:state.media},null,2);}"
     "function updateTiming(){const inspection=state.timing,start=parseRecordingStart(inspection),duration=state.selected&&state.selected.durationSeconds||inspection&&inspection.durationSeconds||null,end=start?deriveRecordingEnd(start,duration):null;setText(timingStart,start||'Unknown');setText(timingEnd,end||'Unknown');if(!inspection){setText(timingState,'Waiting for source selection');setText(timingConfidence,'-');return;}if(inspection.ambiguous){setText(timingState,'Ambiguous');setText(timingConfidence,'Multiple credible recording-clock candidates');}else if(start){setText(timingState,'Candidate');setText(timingConfidence,'Recommended container candidate');}else if(Number.isFinite(duration)&&duration>0){setText(timingState,'Duration only');setText(timingConfidence,'Recording start unknown');}else{setText(timingState,'Unknown');setText(timingConfidence,'No trustworthy evidence found');}}"
     "function updateSummary(){const source=state.selected;setText(summaryListing,state.listing&&state.listing.listingMode||'Unknown');setText(summaryFile,source&&source.fileName||'No source selected');setText(summaryMime,source&&source.mimeType||'-');setText(summaryDuration,formatDuration(source&&source.durationSeconds));setText(summarySize,formatFrameSize(source));updateTiming();renderPreparationDebug();renderRangeDebug();}"
     "function rememberMedia(eventName){state.media.events=[...state.media.events.slice(-11),{event:eventName,currentTime:Number((player.currentTime||0).toFixed(3)),duration:Number.isFinite(player.duration)?Number(player.duration.toFixed(3)):null,buffered:bufferedRanges()}];state.media.buffered=bufferedRanges();state.media.currentTime=Number((player.currentTime||0).toFixed(3));state.media.duration=Number.isFinite(player.duration)?Number(player.duration.toFixed(3)):null;renderRangeDebug();}"
     "['loadedmetadata','progress','seeking','seeked','waiting','canplay','timeupdate','error'].forEach(name=>player.addEventListener(name,()=>{if(name==='error')state.media.error=true;rememberMedia(name);}));"
     "async function probeRange(url){const response=await fetch(url,{credentials:'same-origin',headers:{Range:'bytes=0-4095'}});await response.arrayBuffer();state.rangeProbe={status:response.status,contentRange:response.headers.get('Content-Range'),contentLength:response.headers.get('Content-Length'),contentType:response.headers.get('Content-Type')};renderRangeDebug();}"
     "async function selectSource(source){state.selected=source;state.analysis=null;state.support=null;state.timing=null;state.session=null;state.rangeProbe=null;state.media={events:[],buffered:[],currentTime:0,duration:null,error:false};player.removeAttribute('src');player.load();selectedTitle.textContent=source.fileName;showStatus(playerStatus,'Inspecting browser playback support and recording clock…');updateSummary();try{const [analysisResponse,timingResponse]=await Promise.all([fetch('/v1/drive/playback-analyses',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})}),fetch('/v1/drive/recording-clock-inspections',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})})]);if(!analysisResponse.ok)throw new Error('Playback analysis failed');state.analysis=await analysisResponse.json();state.support=await browserSupport(state.analysis&&state.analysis.evidence);if(timingResponse.ok)state.timing=await timingResponse.json();if(!state.support.supported){showStatus(playerStatus,'This source stays listed, but the browser could not prove direct playback support. See bounded evidence below.','error');updateSummary();return;}const sessionResponse=await fetch('/v1/drive/playback-sessions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({fileId:source.fileId})});if(!sessionResponse.ok)throw new Error('Playback session failed');state.session=await sessionResponse.json();if(!state.session||typeof state.session.playbackUrl!=='string'||!state.session.playbackUrl.startsWith('/v1/drive/playback/'))throw new Error('Playback session returned an invalid private playback URL');await probeRange(state.session.playbackUrl);player.src=state.session.playbackUrl;player.load();showStatus(playerStatus,'Private playback prepared. Seek the player and watch the range debug panel update.','success');updateSummary();}catch(error){showStatus(playerStatus,error&&error.message?error.message:'Playback preparation failed','error');renderPreparationDebug();renderRangeDebug();}}"
     "function renderSources(sources){listNode.replaceChildren();if(!sources.length){showStatus(sourceStatus,'No supported videos were reachable from the fixed workspace under the current authorization model.','error');updateSummary();return;}showStatus(sourceStatus,'Choose any listed video from the fixed workspace.','success');for(const source of sources){const button=document.createElement('button'),meta=document.createElement('span');button.type='button';button.className='source';button.innerHTML='<strong></strong><small></small>';button.querySelector('strong').textContent=source.fileName;button.querySelector('small').textContent=source.mimeType;meta.className='source-meta';meta.textContent=[formatDuration(source.durationSeconds),formatFrameSize(source),Number.isFinite(source.size)?source.size+' bytes':'Unknown size'].join(' · ');button.append(meta);button.addEventListener('click',()=>selectSource(source));listNode.append(button);}}"
     "async function loadSources(){showStatus(sourceStatus,'Loading the fixed timing-workspace folder…');try{const response=await fetch('/v1/proto/sources',{credentials:'same-origin'});if(!response.ok)throw new Error('Fixed-folder listing failed');state.listing=await response.json();renderSources(Array.isArray(state.listing.sources)?state.listing.sources:[]);updateSummary();}catch(error){showStatus(sourceStatus,error&&error.message?error.message:'Fixed-folder listing failed','error');renderPreparationDebug();renderRangeDebug();}}"
     "loadSources();})();</script></div></body></html>")))
