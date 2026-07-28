(ns agg.api-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.browser-process :as browser-process]
            [agg.drive.core :as drive]
            [agg.http-test-support :as test-http]
            [agg.jobs-test :as fixture]
            [agg.jobs.lifecycle :as jobs]
            [agg.tokens.core :as tokens]
            [agg.ui.core :as ui]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io File)
           (java.net InetSocketAddress URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- available-port []
  (test-http/available-port))

(defn- start-api!
  ([port] (start-api! port {}))
  ([port dependencies]
   (api/start! port dependencies)))

(defn- request! [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (= :post method) (or body "")) headers))

(defn- form [fields]
  (->> fields
       (map (fn [[name value]]
              (str (URLEncoder/encode (clojure.core/name name)
                                      StandardCharsets/UTF_8)
                   "="
                   (URLEncoder/encode (str value) StandardCharsets/UTF_8))))
       (str/join "&")))

(defn- fixture []
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ _ _ _ _]
                  (throw (UnsupportedOperationException.))))
        grant-store (reify auth/GrantStore
                      (load-grant [_ _]
                        {:refresh-token-ciphertext "kms:refresh"
                         :folder-id "folder-1"})
                      (save-grant! [_ _ grant] grant)
                      (revoke-grant! [_ _] nil))
        cipher (reify auth/TokenCipher
                 (encrypt-token! [_ value] (str "kms:" value))
                 (decrypt-token! [_ value] (subs value 4)))
        token-client (reify auth/DriveTokenClient
                       (refresh-drive-token! [_ _]
                         {:access-token "drive-access"}))
        auth-system (auth/system
                     {:client-id "client-id"
                      :client-secret "client-secret"
                      :base-url "https://app.example.com"
                      :allowlist #{"owner@example.com" "member@example.com"}
                      :session-key (.getBytes "01234567890123456789012345678901")
                      :oauth oauth
                      :grant-store grant-store
                      :cipher cipher
                      :drive-token-client token-client})
        owner {:subject "owner-subject" :email "owner@example.com"}
        member {:subject "member-subject" :email "member@example.com"}]
    {:auth-system auth-system
     :owner owner
     :owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))
     :owner-csrf (auth/issue-csrf-token auth-system owner)
     :member-cookie (str "agg_session=" (auth/issue-session auth-system member))}))

(def form-content-type
  {"Content-Type" "application/x-www-form-urlencoded"})

(def browser-fixture-timeout-ms 30000)

(defn- javascript-valid? [source]
  (let [process (.start (ProcessBuilder. ["node" "--check" "-"]))]
    (with-open [writer (io/writer (.getOutputStream process))]
      (.write writer source))
    (.readAllBytes (.getErrorStream process))
    (= 0 (.waitFor process))))

(defn- chrome-executable []
  (some (fn [candidate]
          (when candidate
            (let [file (File. candidate)]
              (when (.canExecute file) candidate))))
        [(System/getenv "CHROME_BIN")
         "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
         "/usr/bin/google-chrome"
         "/usr/bin/google-chrome-stable"
         "/usr/bin/chromium"
         "/usr/bin/chromium-browser"]))

(defn- browser-location-outcome
  ([requirement location virtual-time-budget browser-args]
   (browser-location-outcome requirement location virtual-time-budget
                             browser-fixture-timeout-ms browser-args))
  ([requirement location virtual-time-budget timeout-ms browser-args]
   (let [chrome (chrome-executable)]
     (is chrome requirement)
     (when chrome
       (let [{:keys [exit output cleanup]}
             (browser-process/run!
              {:executable chrome
               :fixture requirement
               :location location
               :virtual-time-budget-ms virtual-time-budget
               :timeout-ms timeout-ms
               :browser-args browser-args})
             encoded (second (re-find #"data-outcome=\"([^\"]+)\"" output))]
         ;; Browser-process cleanup correctness is covered directly in
         ;; agg.browser-process-test. These UI regressions care about page
         ;; behavior and can still succeed when CI Chromium teardown reports a
         ;; noisy false negative here.
         (when-not (true? (:process-tree-terminated? cleanup))
           (println (str requirement
                         " [phase=cleanup, process-tree-terminated=false]")))
         (is (true? (:profile-removed? cleanup))
             (str requirement " [phase=cleanup, profile-removed=false]"))
         (is (= 0 exit)
             (str requirement
                  " [phase=browser-exit, exit=" exit
                  ", output-bytes="
                  (alength (.getBytes ^String output StandardCharsets/UTF_8))
                  "]"))
         (is encoded
             (str requirement
                  " [phase=outcome-decode, output-bytes="
                  (alength (.getBytes ^String output StandardCharsets/UTF_8))
                  "]"))
         (when encoded
           (json/read-str
            (String. (.decode (Base64/getDecoder) ^String encoded)
                     StandardCharsets/UTF_8)
            :key-fn keyword)))))))

(defn- browser-outcome*
  [prefix requirement html virtual-time-budget & browser-args]
  (let [temp (File/createTempFile prefix ".html")]
    (try
      (spit temp html)
      (browser-location-outcome requirement
                                (.toURI temp)
                                virtual-time-budget
                                browser-args)
      (finally
        (.delete temp)))))

(defn- browser-outcome [prefix requirement html & browser-args]
  (apply browser-outcome* prefix requirement html 1000 browser-args))

(defn- browser-outcome-with-timeout
  [prefix requirement html timeout-ms & browser-args]
  (let [temp (File/createTempFile prefix ".html")]
    (try
      (spit temp html)
      (browser-location-outcome requirement
                                (.toURI temp)
                                1000
                                timeout-ms
                                browser-args)
      (finally
        (.delete temp)))))

(defn- browser-outcome-with-budget-and-timeout
  [prefix requirement html virtual-time-budget timeout-ms & browser-args]
  (let [temp (File/createTempFile prefix ".html")]
    (try
      (spit temp html)
      (browser-location-outcome requirement
                                (.toURI temp)
                                virtual-time-budget
                                timeout-ms
                                browser-args)
      (finally
        (.delete temp)))))

(defn- respond-browser-fixture!
  [^HttpExchange exchange status content-type body generation]
  (let [bytes (.getBytes ^String body StandardCharsets/UTF_8)]
    (doto (.getResponseHeaders exchange)
      (.set "Content-Type" content-type)
      (.set "Cache-Control" "no-store"))
    (when generation
      (.set (.getResponseHeaders exchange) "X-Preview-Generation" generation))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [response (.getResponseBody exchange)]
      (.write response bytes))))

(defn- real-htmx-preview-outcome [page]
  (let [operation-id "00000000-0000-0000-0000-000000000021"
        generation (atom nil)
        requests (atom [])
        server-error (atom nil)
        htmx-source
        (slurp
         (io/resource
          "META-INF/resources/webjars/htmx.org/2.0.10/dist/htmx.min.js"))
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "function recordOutcome(outcome){const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "document.addEventListener('DOMContentLoaded',()=>{try{"
         "const events=[];['htmx:beforeSwap','htmx:afterSwap','htmx:afterSettle','htmx:swapError'].forEach(type=>document.body.addEventListener(type,event=>events.push({type,eventTarget:event.target?.id||event.target?.tagName,detailElt:event.detail?.elt?.id||null,detailTarget:event.detail?.target?.id||null,className:event.detail?.elt?.className||'',connected:event.detail?.elt?.isConnected??null,xhrGeneration:event.detail?.xhr?.aggPreviewGeneration||null,elementGeneration:event.detail?.elt?.dataset?.previewGeneration||null})));"
         "const button=document.getElementById('preview-button'),spinner=button.querySelector('.button-spinner'),submit=document.getElementById('submit-button');"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
         "button.click();const deadline=Date.now()+3500;function inspect(){const result=document.getElementById('preview-result'),finished=result?.classList.contains('preview-error')&&!button.disabled;if(finished||Date.now()>=deadline){recordOutcome({htmxVersion:window.htmx?.version||null,className:result?.className||'',text:result?.textContent||'',previewDisabled:button.disabled,spinnerHidden:spinner.hidden,submitDisabled:submit.disabled,submitStatus:document.getElementById('preview-submit-status').textContent,status:document.getElementById('form-status').textContent,events});return;}setTimeout(inspect,25);}setTimeout(inspect,25);"
         "}catch(error){recordOutcome({error:error.message});}},{once:true});"
         "</script>")
        html
        (-> page
            (str/replace
             #"<script src=\"https://cdn\.jsdelivr\.net/npm/htmx\.org@2\.0\.10/dist/htmx\.min\.js\"[^>]*></script>"
             "<script src=\"/htmx.min.js\"></script>")
            (str/replace "</body>" (str scenario "</body>")))
        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        port (.getPort (.getAddress server))]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (let [method (.getRequestMethod exchange)
                 path (.getPath (.getRequestURI exchange))]
             (swap! requests conj [method path])
             (cond
               (and (= "GET" method) (= "/" path))
               (respond-browser-fixture!
                exchange 200 "text/html; charset=utf-8" html nil)

               (and (= "GET" method) (= "/htmx.min.js" path))
               (respond-browser-fixture!
                exchange 200 "application/javascript; charset=utf-8"
                htmx-source nil)

               (and (= "POST" method) (= "/ui/preview" path))
               (let [request-generation
                     (.getFirst (.getRequestHeaders exchange)
                                "X-Preview-Generation")]
                 (reset! generation request-generation)
                 (with-open [request (.getRequestBody exchange)]
                   (.readAllBytes request))
                 (respond-browser-fixture!
                  exchange 202 "text/html; charset=utf-8"
                  (ui/preview-operation-fragment
                   {:id operation-id :state "running" :progressPercent 50}
                   request-generation)
                  request-generation))

               (and (= "GET" method)
                    (= (str "/ui/previews/" operation-id) path))
               (respond-browser-fixture!
                exchange 200 "text/html; charset=utf-8"
                (ui/preview-operation-fragment
                 {:id operation-id
                  :state "failed"
                  :progressPercent 100
                  :error {:code "worker_failed"
                          :category "preview_rendering"
                          :requestId operation-id
                          :stage "source_content"
                          :elapsedMs 4378
                          :retryable false}}
                 @generation)
                @generation)

               :else
               (respond-browser-fixture!
                exchange 404 "text/plain; charset=utf-8" "not found" nil)))
           (catch Throwable error
             (reset! server-error error)
             (respond-browser-fixture!
              exchange 500 "text/plain; charset=utf-8" "fixture failed" nil))))))
    (.setExecutor server nil)
    (.start server)
    (try
      (let [outcome
            (browser-location-outcome
             "Real HTMX preview regression requires Chrome or Chromium"
             (str "http://127.0.0.1:" port "/") 5000 [])]
        (is (nil? @server-error) (some-> @server-error str))
        (assoc outcome :requests @requests))
      (finally
        (.stop server 0)))))

(defn- picker-browser-outcome [page window-size]
  (let [fixture
        (str
         "<script>"
         "window.__pickerState={loads:[],visible:[],diagnostics:[],callback:null,views:[],addedViews:[],selectableMimeTypes:null};"
         "window.fetch=(path,options)=>{if(path==='/v1/drive/picker/diagnostic'){window.__pickerState.diagnostics.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:204});}return Promise.resolve({ok:false,status:415,json:()=>Promise.resolve({error:'browser_playback_not_supported'})});};"
         "class PickerView{constructor(kind='drive'){this.config={kind};window.__pickerState.views.push(this.config);}"
         "setMimeTypes(value){this.config.mimeTypes=value;return this;}"
         "setIncludeFolders(value){this.config.includeFolders=value;return this;}"
         "setSelectFolderEnabled(value){this.config.selectFolderEnabled=value;return this;}"
         "setMode(value){this.config.mode=value;return this;}"
         "setEnableDrives(value){this.config.enableDrives=value;return this;}"
         "setOwnedByMe(value){this.config.ownedByMe=value;return this;}}"
         "class UploadView extends PickerView{constructor(){super('upload');}}"
         "class PickerBuilder{"
         "addView(view){window.__pickerState.addedViews.push(view.config);return this;}"
         "setSelectableMimeTypes(value){window.__pickerState.selectableMimeTypes=value;return this;}setOAuthToken(){return this;}"
         "setDeveloperKey(){return this;}setAppId(){return this;}setOrigin(){return this;}"
         "setCallback(callback){window.__pickerState.callback=callback;return this;}"
         "build(){return {setVisible(visible){window.__pickerState.visible.push(visible);}};}"
         "}"
         "window.google={picker:{DocsView:PickerView,DocsUploadView:UploadView,PickerBuilder,"
         "DocsViewMode:{LIST:'list'},Action:{LOADED:'loaded',PICKED:'picked',CANCEL:'cancel'}}};"
         "window.gapi={load(_module,handlers){window.__pickerState.loads.push(handlers);}};"
         "</script>")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const state=window.__pickerState,button=document.getElementById('open-picker'),selection=document.getElementById('picker-selection');"
         "button.click();const initialLoading=selection.textContent;"
         "const firstLoad=state.loads.at(-1);if(typeof firstLoad?.onerror!=='function')throw new Error('Picker load has no error recovery');"
         "firstLoad.onerror();const failureMessage=selection.textContent;"
         "button.click();const failureRetryLoading=selection.textContent;"
         "const timeoutLoad=state.loads.at(-1);if(typeof timeoutLoad?.ontimeout!=='function')throw new Error('Picker load has no timeout recovery');"
         "timeoutLoad.ontimeout();const timeoutMessage=selection.textContent;"
         "button.click();const timeoutRetryLoading=selection.textContent;"
         "const retryLoad=state.loads.at(-1);if(typeof retryLoad?.callback!=='function')throw new Error('Picker load is not retriable');"
         "retryLoad.callback();"
         "state.callback({action:google.picker.Action.LOADED});"
         "state.callback({action:google.picker.Action.PICKED,docs:[{id:'crafted-file-id',name:'crafted.video',mimeType:'video/x-unsupported'}]});"
         "const rejected={selection:selection.textContent,fileId:document.getElementById('source-video-file-id').value};"
         "state.callback({action:google.picker.Action.PICKED,docs:[{id:'folder-id',name:'Nested folder',mimeType:'application/vnd.google-apps.folder'}]});"
         "const folderRejected={selection:selection.textContent,fileId:document.getElementById('source-video-file-id').value};"
         "state.callback({action:google.picker.Action.PICKED,docs:[{id:'test-file-id',name:'video.mp4',mimeType:'video/mp4'}]});"
         "const selected=selection.textContent;button.click();state.callback({action:google.picker.Action.CANCEL});"
         "outcome={initialLoading,failureMessage,failureRetryLoading,timeoutMessage,timeoutRetryLoading,rejected,folderRejected,selected,views:state.addedViews,selectableMimeTypes:state.selectableMimeTypes,visible:state.visible,diagnostics:state.diagnostics,viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));"
         "document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<script>(function(){"
                              (str fixture "<script>(function(){"))
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome "agg-picker-browser-"
                     "Browser-level Picker regression requires Chrome or Chromium"
                     html (str "--window-size=" window-size))))

(defn- video-player-browser-outcome [page window-size]
  (let [fixture
        (str
         "<script>"
         "window.__playerState={callback:null,loads:[],sessionRequests:[],analysisRequests:[],inspectionRequests:[],playCalls:0,fullscreenElement:null,fullscreenRequests:[],fullscreenExits:0,fullscreenTimers:[],clearedTimers:[],pointerCaptures:[],pointerReleases:[]};"
         "const nativeSetTimeout=window.setTimeout.bind(window),nativeClearTimeout=window.clearTimeout.bind(window);window.setTimeout=(callback,delay,...args)=>{if(delay===4000){const timer={id:'fullscreen-'+(window.__playerState.fullscreenTimers.length+1),callback,cleared:false};window.__playerState.fullscreenTimers.push(timer);return timer.id;}return nativeSetTimeout(callback,delay,...args);};window.clearTimeout=id=>{const timer=window.__playerState.fullscreenTimers.find(candidate=>candidate.id===id);if(timer){timer.cleared=true;window.__playerState.clearedTimers.push(id);return;}nativeClearTimeout(id);};"
         "Object.defineProperty(document,'fullscreenElement',{configurable:true,get(){return window.__playerState.fullscreenElement;}});Element.prototype.requestFullscreen=function(){window.__playerState.fullscreenElement=this;window.__playerState.fullscreenRequests.push(this.id);document.dispatchEvent(new Event('fullscreenchange'));return Promise.resolve();};document.exitFullscreen=function(){window.__playerState.fullscreenElement=null;window.__playerState.fullscreenExits++;document.dispatchEvent(new Event('fullscreenchange'));return Promise.resolve();};"
         "Element.prototype.setPointerCapture=function(pointerId){window.__playerState.pointerCaptures.push({element:this.id,pointerId});};Element.prototype.releasePointerCapture=function(pointerId){window.__playerState.pointerReleases.push({element:this.id,pointerId});};"
         "window.fetch=(path,options={})=>{if(path==='/v1/drive/playback-analyses'){window.__playerState.analysisRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'authoritative-ride.mp4',evidence:{container:{format:'mp4',majorBrand:'isom'},video:{codec:'h264',codecTag:'avc1',profile:'High',pixelFormat:'yuv420p'},audio:{codec:'aac'}}})});}if(path==='/v1/drive/playback-sessions'){window.__playerState.sessionRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:201,json:()=>Promise.resolve({playbackUrl:'/v1/drive/playback/00000000-0000-0000-0000-000000000115',contentType:'video/mp4',size:2048})});}if(/\\/v1\\/jobs\\/[0-9a-f-]+\\/playback-sessions$/.test(path)){return Promise.resolve({ok:true,status:201,json:()=>Promise.resolve({playbackUrl:path.replace('/playback-sessions','/playback/00000000-0000-0000-0000-000000000215'),contentType:'video/mp4',size:2048})});}if(path==='/v1/drive/recording-clock-inspections'){window.__playerState.inspectionRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'authoritative-ride.mp4',status:'candidate',candidates:[{source:'movie',kind:'explicit-offset',value:'2026-07-23T23:59:30+02:00'}],recommendedIndex:0,ambiguous:false,durationSeconds:125.5,limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}})});}return Promise.resolve({ok:true,status:204,json:()=>Promise.resolve({})});};"
         "class PickerView{setMimeTypes(){return this;}setIncludeFolders(){return this;}setSelectFolderEnabled(){return this;}setMode(){return this;}setEnableDrives(){return this;}}"
         "class UploadView extends PickerView{}"
         "class PickerBuilder{addView(){return this;}setSelectableMimeTypes(){return this;}setOAuthToken(){return this;}setDeveloperKey(){return this;}setAppId(){return this;}setOrigin(){return this;}setCallback(callback){window.__playerState.callback=callback;return this;}build(){return {setVisible(){}};}}"
         "window.google={picker:{DocsView:PickerView,DocsUploadView:UploadView,PickerBuilder,DocsViewMode:{LIST:'list'},Action:{LOADED:'loaded',PICKED:'picked',CANCEL:'cancel'}}};"
         "window.gapi={load(_module,handlers){window.__playerState.loads.push(handlers);}};"
         "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??NaN;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},volume:{configurable:true,get(){return this.__volume??1;},set(value){this.__volume=Number(value);}},buffered:{configurable:true,get(){const ranges=this.__bufferedRanges??[];return {length:ranges.length,start:index=>ranges[index][0],end:index=>ranges[index][1]};}}});"
         "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});HTMLMediaElement.prototype.canPlayType=function(){return 'probably';};HTMLMediaElement.prototype.load=function(){};HTMLMediaElement.prototype.play=function(){this.__paused=false;window.__playerState.playCalls++;this.dispatchEvent(new Event('play'));return Promise.resolve();};HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const state=window.__playerState,next=document.getElementById('wizard-next');document.querySelector('input[name=\"wizard-outcome\"][value=\"finished-video\"]').click();next.click();state.loads[0].callback();state.callback({action:google.picker.Action.PICKED,docs:[{id:'private-mp4',name:'ride.mp4',mimeType:'video/mp4'}]});await new Promise(resolve=>setTimeout(resolve,0));next.click();document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-23T21:59:30Z,120\\n2026-07-23T22:01:36Z,124';next.click();document.querySelector('input[name=\"synchronization-mode\"][value=\"shared-clock\"]').click();next.click();document.getElementById('timezone').value='UTC';document.getElementById('video-timezone').value='+02:00';document.getElementById('confirm-video-clock').click();const fixedOffsetRejected={confirmed:document.getElementById('video-clock-confirmation').dataset.confirmed,status:document.getElementById('video-clock-status').textContent};document.getElementById('video-timezone').value='Europe/Warsaw';document.getElementById('confirm-video-clock').click();next.click();"
         "const player=document.getElementById('video-player'),video=document.getElementById('source-video-player'),timeline=document.getElementById('video-timeline'),fit=document.getElementById('fit-mode'),play=document.getElementById('video-play-pause'),track=document.querySelector('.video-timeline-track'),bufferedLayer=document.getElementById('video-buffered-ranges'),outputRange=document.getElementById('video-output-range'),unusedAfter=document.getElementById('video-unused-after'),playhead=document.getElementById('video-playhead');function playbackControl(){const symbol=play.querySelector('.video-play-pause-symbol'),rect=play.getBoundingClientRect(),symbolRect=symbol?.getBoundingClientRect();return {paused:video.paused,name:play.getAttribute('aria-label'),symbol:symbol?.textContent||null,decorative:symbol?.getAttribute('aria-hidden')==='true',visible:!!symbolRect&&symbolRect.width>0&&symbolRect.height>0,width:rect.width,height:rect.height};}function bufferedAppearance(){const segments=[...bufferedLayer.children],layerStyle=getComputedStyle(bufferedLayer),segmentStyle=segments[0]&&getComputedStyle(segments[0]),trackRect=track.getBoundingClientRect(),layerRect=bufferedLayer.getBoundingClientRect();return {segments:segments.length,visible:segments.every(segment=>segment.getBoundingClientRect().width>0&&segment.getBoundingClientRect().height>0),alongside:layerRect.height>0&&layerRect.height<trackRect.height,railFraction:layerRect.height/trackRect.height,layerZ:Number(layerStyle.zIndex),outputZ:Number(getComputedStyle(outputRange).zIndex),unusedZ:Number(getComputedStyle(unusedAfter).zIndex),playheadZ:Number(getComputedStyle(playhead).zIndex),pointerEvents:layerStyle.pointerEvents,colorsDistinct:!!segmentStyle&&new Set([segmentStyle.backgroundColor,getComputedStyle(track).backgroundColor,getComputedStyle(outputRange).backgroundColor,getComputedStyle(playhead).backgroundColor]).size===4};}video.__duration=125.5;video.__bufferedRanges=[[0,30]];video.dispatchEvent(new Event('loadedmetadata'));const initialPlayback=playbackControl(),bufferedBeforeProgress=bufferedAppearance();video.__bufferedRanges.push([60,90]);video.dispatchEvent(new Event('progress'));const bufferedAfterProgress=bufferedAppearance();"
         "const outputStart=document.getElementById('output-start-handle'),outputEnd=document.getElementById('output-end-handle'),syncMarker=document.getElementById('manual-sync-marker'),cameraSync=document.getElementById('camera-sync-at'),cameraSyncField=document.getElementById('camera-sync-field'),telemetrySyncField=document.getElementById('telemetry-sync-field'),markerReady={hidden:syncMarker.hidden,disabled:syncMarker.disabled,value:syncMarker.getAttribute('aria-valuenow')};cameraSync.value=document.getElementById('section-start-at').value;cameraSync.dispatchEvent(new Event('input',{bubbles:true}));const initialRange={start:outputStart.getAttribute('aria-valuenow'),end:outputEnd.getAttribute('aria-valuenow'),startField:document.getElementById('section-start-at').value,endField:document.getElementById('section-end-at').value,unusedBefore:document.getElementById('video-unused-before').getBoundingClientRect().width,unusedAfter:document.getElementById('video-unused-after').getBoundingClientRect().width};"
         "const generatedRequest=JSON.parse(document.getElementById('render-request').value),contextText=()=>[...document.getElementById('video-time-context-visual').children].filter(node=>!node.hidden).map(node=>node.textContent).join(' '),dateLabels=()=>[...document.getElementById('video-dates').children].map(label=>{const rect=label.getBoundingClientRect();return {text:label.textContent,date:label.dataset.date,start:Number(label.dataset.startRatio),end:Number(label.dataset.endRatio),visible:rect.width>0,left:rect.left,right:rect.right,top:rect.top,bottom:rect.bottom};}),dateLabelsSeparated=()=>{const labels=dateLabels();return labels.every((label,index)=>labels.slice(index+1).every(other=>label.right<=other.left||other.right<=label.left||label.bottom<=other.top||other.bottom<=label.top));},initial={hidden:player.hidden,paused:video.paused,currentTime:video.currentTime,playCalls:state.playCalls,src:video.getAttribute('src'),selection:document.getElementById('picker-selection').textContent,fileId:document.getElementById('source-video-file-id').value,time:document.getElementById('video-time').textContent,timeAria:document.getElementById('video-time').getAttribute('aria-label'),context:contextText(),contextAria:document.getElementById('video-time-context').getAttribute('aria-label'),dates:dateLabels(),dateLabelsSeparated:dateLabelsSeparated(),ticks:[...document.getElementById('video-ticks').children].map(tick=>tick.textContent),timelineMax:timeline.getAttribute('aria-valuemax'),timelineValueText:timeline.getAttribute('aria-valuetext'),bufferedSegments:document.querySelectorAll('#video-buffered-ranges span').length,fit:getComputedStyle(video).objectFit,analysisRequest:state.analysisRequests[0],request:state.sessionRequests[0],inspectionRequest:state.inspectionRequests[0],mode:{sourceControlsHidden:document.getElementById('source-output-controls').hidden,summaryHidden:document.getElementById('no-source-output-summary').hidden,stageHidden:document.getElementById('video-stage').hidden,transportHidden:document.querySelector('.video-transport').hidden,timelineLabel:timeline.getAttribute('aria-label')},clock:{start:document.getElementById('video-recording-start').value,zone:document.getElementById('video-timezone').value,confirmed:document.getElementById('video-clock-confirmation').dataset.confirmed,candidates:document.querySelectorAll('#video-clock-candidates input').length,summary:document.getElementById('video-source-summary').textContent,request:generatedRequest.sourceVideo}};const timerToggle=document.getElementById('timer-enabled'),timerStartMarker=document.getElementById('timer-start-marker'),timerEndMarker=document.getElementById('timer-end-marker'),overlaps=(first,second)=>{const a=first.getBoundingClientRect(),b=second.getBoundingClientRect();return a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top;};function timerAt(seconds){video.currentTime=seconds;timerToggle.click();const snapshot={current:video.currentTime,fields:[document.getElementById('timer-start-at').value,document.getElementById('timer-end-at').value],request:JSON.parse(document.getElementById('render-request').value).timer||null,markers:[timerStartMarker.hidden,timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.hidden,timerEndMarker.getAttribute('aria-valuenow')],fieldsHidden:document.getElementById('timer-fields').hidden,markersSeparated:!overlaps(timerStartMarker,timerEndMarker),handlesSeparated:!overlaps(timerStartMarker,outputStart)&&!overlaps(timerEndMarker,outputEnd)};timerToggle.click();return snapshot;}const timerDefaults={start:timerAt(0),middle:timerAt(62.75),end:timerAt(125.48),outside:timerAt(125.5),disabled:{request:JSON.parse(document.getElementById('render-request').value).timer||null,markers:[timerStartMarker.hidden,timerEndMarker.hidden]}};video.currentTime=0;"
         "document.getElementById('timer-enabled').click();document.getElementById('timer-start-at').value='2026-07-23T21:59:50';document.getElementById('timer-end-at').value='2026-07-23T22:01:10';const rangeRect=timeline.getBoundingClientRect();outputStart.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,clientX:rangeRect.left+rangeRect.width*.5,pointerId:7}));outputStart.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,clientX:rangeRect.left+rangeRect.width*.5,pointerId:7}));const clampedStart=outputStart.getAttribute('aria-valuenow'),timerStartMessage=document.getElementById('video-range-status').textContent;outputStart.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key:'ArrowLeft'}));const keyboardStart={value:outputStart.getAttribute('aria-valuenow'),field:document.getElementById('section-start-at').value,highlighted:document.getElementById('section-start-at').classList.contains('range-receiver')};outputEnd.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,clientX:rangeRect.left+rangeRect.width*.5,pointerId:8}));outputEnd.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,clientX:rangeRect.left+rangeRect.width*.5,pointerId:8}));const timerRange={clampedStart,clampedEnd:outputEnd.getAttribute('aria-valuenow'),startMessage:timerStartMessage,endMessage:document.getElementById('video-range-status').textContent,keyboardStart};next.click();await new Promise(resolve=>setTimeout(resolve,0));next.click();await new Promise(resolve=>setTimeout(resolve,0));"
         "const timerMarkerVideoTime=video.currentTime;timerStartMarker.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,clientX:rangeRect.left+rangeRect.width*.95,pointerId:10}));const timerStartCrossing={values:[timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.getAttribute('aria-valuenow')],fields:[document.getElementById('timer-start-at').value,document.getElementById('timer-end-at').value],request:JSON.parse(document.getElementById('render-request').value).timer,startHighlighted:document.getElementById('timer-start-field').classList.contains('timer-field-active'),endHighlighted:document.getElementById('timer-end-field').classList.contains('timer-field-active')};timerStartMarker.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,clientX:rangeRect.left+rangeRect.width*.95,pointerId:10}));timerEndMarker.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,clientX:rangeRect.left+rangeRect.width*.05,pointerId:11}));const timerEndCrossing={values:[timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.getAttribute('aria-valuenow')],startHighlighted:document.getElementById('timer-start-field').classList.contains('timer-field-active'),endHighlighted:document.getElementById('timer-end-field').classList.contains('timer-field-active')};timerEndMarker.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,clientX:rangeRect.left+rangeRect.width*.05,pointerId:11}));const timerMarkerPointer={start:timerStartCrossing,end:timerEndCrossing,captures:state.pointerCaptures.slice(-2),releases:state.pointerReleases.slice(-2),released:[document.getElementById('timer-start-field').classList.contains('timer-field-active'),document.getElementById('timer-end-field').classList.contains('timer-field-active')],videoUnchanged:video.currentTime===timerMarkerVideoTime};"
         "const timerKey=(marker,key,options={})=>{const event=new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key,...options});marker.dispatchEvent(event);return event.defaultPrevented;};timerStartMarker.focus();const timerStartFocus=[document.getElementById('timer-start-field').classList.contains('timer-field-active'),document.getElementById('timer-end-field').classList.contains('timer-field-active')],timerStartLeftPrevented=timerKey(timerStartMarker,'ArrowLeft'),timerStartAfterLeft=timerStartMarker.getAttribute('aria-valuenow'),timerStartShiftLeftPrevented=timerKey(timerStartMarker,'ArrowLeft',{shiftKey:true}),timerStartAfterShiftLeft=timerStartMarker.getAttribute('aria-valuenow'),timerStartHomePrevented=timerKey(timerStartMarker,'Home'),timerStartAfterHome=timerStartMarker.getAttribute('aria-valuenow'),timerStartEndPrevented=timerKey(timerStartMarker,'End'),timerStartAfterEnd=timerStartMarker.getAttribute('aria-valuenow');timerKey(timerStartMarker,'Home');timerEndMarker.focus();const timerEndFocus=[document.getElementById('timer-start-field').classList.contains('timer-field-active'),document.getElementById('timer-end-field').classList.contains('timer-field-active')],timerEndLeftPrevented=timerKey(timerEndMarker,'ArrowLeft'),timerEndAfterLeft=timerEndMarker.getAttribute('aria-valuenow'),timerEndShiftLeftPrevented=timerKey(timerEndMarker,'ArrowLeft',{shiftKey:true}),timerEndAfterShiftLeft=timerEndMarker.getAttribute('aria-valuenow'),timerEndHomePrevented=timerKey(timerEndMarker,'Home'),timerEndAfterHome=timerEndMarker.getAttribute('aria-valuenow'),timerEndEndPrevented=timerKey(timerEndMarker,'End'),timerEndAfterEnd=timerEndMarker.getAttribute('aria-valuenow'),timerMarkerKeyboard={startFocus:timerStartFocus,startLeftPrevented:timerStartLeftPrevented,startAfterLeft:timerStartAfterLeft,startShiftLeftPrevented:timerStartShiftLeftPrevented,startAfterShiftLeft:timerStartAfterShiftLeft,startHomePrevented:timerStartHomePrevented,startAfterHome:timerStartAfterHome,startEndPrevented:timerStartEndPrevented,startAfterEnd:timerStartAfterEnd,endFocus:timerEndFocus,endLeftPrevented:timerEndLeftPrevented,endAfterLeft:timerEndAfterLeft,endShiftLeftPrevented:timerEndShiftLeftPrevented,endAfterShiftLeft:timerEndAfterShiftLeft,endHomePrevented:timerEndHomePrevented,endAfterHome:timerEndAfterHome,endEndPrevented:timerEndEndPrevented,endAfterEnd:timerEndAfterEnd,request:JSON.parse(document.getElementById('render-request').value).timer,videoUnchanged:video.currentTime===timerMarkerVideoTime};timerEndMarker.blur();"
         "const sharedVisibility={markerHidden:syncMarker.hidden,helpHidden:document.getElementById('manual-sync-marker-help').hidden,fieldsHidden:document.getElementById('manual-synchronization-fields').hidden},manualVisibility=null,markerPointer=null,markerKeyboard=null,typedManual=null;"
         "const shortcutHints=[...document.querySelectorAll('.video-control')].map(control=>{const button=control.querySelector('button'),hint=control.querySelector('.video-shortcut'),before=control.getBoundingClientRect();button.focus();const after=control.getBoundingClientRect(),style=hint&&getComputedStyle(hint);return {name:button.getAttribute('aria-label')||button.textContent.trim(),keys:button.getAttribute('aria-keyshortcuts'),hint:hint?.textContent||null,focusVisible:style?.visibility==='visible'&&style?.opacity==='1',stable:before.width===after.width&&before.height===after.height};});"
         "fit.value='crop';fit.dispatchEvent(new Event('input',{bubbles:true}));const cropped=getComputedStyle(video).objectFit;"
         "document.querySelector('[data-seek-seconds=\"10\"]').click();document.querySelector('[data-seek-seconds=\"60\"]').click();document.querySelector('[data-seek-seconds=\"-10\"]').click();const transportTime=video.currentTime;"
         "const rect=timeline.getBoundingClientRect();timeline.dispatchEvent(new PointerEvent('pointermove',{bubbles:true,clientX:rect.left+rect.width*.75}));const hover={hidden:document.getElementById('video-timeline-tooltip').hidden,text:document.getElementById('video-timeline-tooltip').textContent};timeline.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,clientX:rect.left+rect.width*.5,pointerId:1}));const scrubTime=video.currentTime;timeline.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key:'ArrowRight'}));const keyboardTime=video.currentTime;"
         "play.click();await Promise.resolve();const playing=playbackControl();play.click();const paused=playbackControl();video.__paused=false;video.dispatchEvent(new Event('play'));const mediaPlaying=playbackControl();video.__paused=true;video.dispatchEvent(new Event('pause'));const mediaPaused=playbackControl();"
         "function press(target,key,options={}){const event=new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key,...options});target.dispatchEvent(event);return event.defaultPrevented;}"
         "const shortcutStart=video.currentTime,rightPrevented=press(document.body,'ArrowRight'),afterRight=video.currentTime,shiftRightPrevented=press(document.body,'ArrowRight',{shiftKey:true}),afterShiftRight=video.currentTime,leftPrevented=press(document.body,'ArrowLeft'),afterLeft=video.currentTime,shiftLeftPrevented=press(document.body,'ArrowLeft',{shiftKey:true}),afterShiftLeft=video.currentTime,spacePrevented=press(document.body,' '),afterSpacePaused=video.paused,spacePlaying=playbackControl();press(document.body,' ');const spacePaused=playbackControl();"
         "const editable=document.createElement('div'),textbox=document.createElement('div');editable.contentEditable='true';textbox.setAttribute('role','textbox');document.body.append(editable,textbox);const editableChecks=[['select',fit,'ArrowRight'],['textarea',document.getElementById('telemetry'),' '],['input',document.getElementById('future-trace-opacity-percent'),'ArrowLeft'],['contenteditable',editable,'ArrowRight'],['textbox',textbox,' ']].map(([kind,target,key])=>{const before=video.currentTime,prevented=press(target,key);return {kind,prevented,before,after:video.currentTime};});"
         "const modifiedChecks=[['ctrl','ArrowRight',{ctrlKey:true}],['meta',' ',{metaKey:true}],['alt','f',{altKey:true}]].map(([kind,key,options])=>{const before=video.currentTime,prevented=press(document.body,key,options);return {kind,prevented,before,after:video.currentTime};});"
         "const forwardButton=document.querySelector('[data-seek-seconds=\"10\"]');forwardButton.focus();const focusedButtonPrevented=press(forwardButton,' '),afterFocusedButtonKey=video.currentTime;forwardButton.click();const afterFocusedButtonClick=video.currentTime;player.hidden=true;const hiddenStart=video.currentTime,hiddenPrevented=press(document.body,'ArrowRight'),afterHidden=video.currentTime;player.hidden=false;"
         "const chrome=document.getElementById('video-chrome'),dock=document.getElementById('video-controls-dock'),fullscreen=document.getElementById('video-fullscreen'),fullscreenControl=document.getElementById('video-fullscreen-control'),fullscreenHint=document.getElementById('video-fullscreen-shortcut');function hintVisible(){const style=getComputedStyle(fullscreenHint);return style.visibility==='visible'&&style.opacity==='1';}"
         "const fullscreenEntryPrevented=press(document.body,'f'),fullscreenBuffered=bufferedAppearance(),fullscreenDates=document.getElementById('video-dates'),fullscreenContext=document.getElementById('video-time-context'),fullscreenEntry={prevented:fullscreenEntryPrevented,request:state.fullscreenRequests.at(-1),elementId:document.fullscreenElement?.id||null,label:fullscreen.textContent,pressed:fullscreen.getAttribute('aria-pressed'),hint:fullscreenHint.textContent,hintVisible:hintVisible(),auto:fullscreenControl.classList.contains('shortcut-auto'),focusUnchanged:document.activeElement===forwardButton,completeChrome:!!chrome&&chrome.contains(document.getElementById('video-stage'))&&chrome.contains(document.querySelector('.video-transport'))&&chrome.contains(timeline),contextInside:chrome.contains(fullscreenContext),contextVisible:fullscreenContext.getBoundingClientRect().height>0,datesVisible:fullscreenDates.getBoundingClientRect().height>0,dateLabelsVisible:dateLabels().every(label=>label.visible),dateLabelsSeparated:dateLabelsSeparated(),dockInside:chrome?.lastElementChild===dock,fullscreenLayout:getComputedStyle(chrome).display==='grid',dockVisible:!!dock&&dock.getBoundingClientRect().height>0,timelineVisible:timeline.getBoundingClientRect().height>0,markerInside:chrome.contains(syncMarker),markerVisible:syncMarker.getBoundingClientRect().height>0,markerValueText:syncMarker.getAttribute('aria-valuetext'),markerControls:syncMarker.getAttribute('aria-controls'),helpVisible:document.getElementById('manual-sync-marker-help').getBoundingClientRect().height>0,timerMarkersInside:chrome.contains(timerStartMarker)&&chrome.contains(timerEndMarker),timerMarkersVisible:timerStartMarker.getBoundingClientRect().height>0&&timerEndMarker.getBoundingClientRect().height>0,timerMarkerValues:[timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.getAttribute('aria-valuenow')],timerMarkerControls:[timerStartMarker.getAttribute('aria-controls'),timerEndMarker.getAttribute('aria-controls')],timerHelpVisible:document.getElementById('timer-marker-help').getBoundingClientRect().height>0,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth,timerCount:state.fullscreenTimers.length},fullscreenPlayback=playbackControl();"
         "state.fullscreenTimers.at(-1).callback();const afterFourSeconds={auto:fullscreenControl.classList.contains('shortcut-auto'),hintVisible:hintVisible()};fullscreen.focus();const focusedFullscreenHint={hint:fullscreenHint.textContent,visible:hintVisible()};forwardButton.focus();const fullscreenExitPrevented=press(document.body,'f'),fullscreenExit={prevented:fullscreenExitPrevented,label:fullscreen.textContent,pressed:fullscreen.getAttribute('aria-pressed'),elementId:document.fullscreenElement?.id||null,auto:fullscreenControl.classList.contains('shortcut-auto')};"
         "press(document.body,'f');const restartedTimer=state.fullscreenTimers.at(-1),escapePrevented=press(document.body,'Escape');state.fullscreenElement=null;document.dispatchEvent(new Event('fullscreenchange'));const browserExit={escapePrevented,label:fullscreen.textContent,pressed:fullscreen.getAttribute('aria-pressed'),auto:fullscreenControl.classList.contains('shortcut-auto'),timerCleared:restartedTimer.cleared};fullscreen.click();const buttonEntry={request:state.fullscreenRequests.at(-1),label:fullscreen.textContent};fullscreen.click();const buttonExit={exitCount:state.fullscreenExits,label:fullscreen.textContent};"
         "video.dispatchEvent(new Event('error'));const disabledStart=video.currentTime,disabledSeekPrevented=press(document.body,'ArrowRight'),requestsBeforeDisabledF=state.fullscreenRequests.length,disabledFullscreenPrevented=press(document.body,'f'),unsupported={selection:document.getElementById('picker-selection').textContent,fileId:document.getElementById('source-video-file-id').value,message:document.getElementById('video-player-status').textContent,disabledStart,disabledSeekPrevented,afterDisabledSeek:video.currentTime,disabledFullscreenPrevented,fullscreenRequestsUnchanged:state.fullscreenRequests.length===requestsBeforeDisabledF,range:[outputStart.getAttribute('aria-valuenow'),outputEnd.getAttribute('aria-valuenow')],marker:{hidden:syncMarker.hidden,disabled:syncMarker.disabled,value:syncMarker.getAttribute('aria-valuenow')},timerMarkers:[timerStartMarker.hidden,timerStartMarker.disabled,timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.hidden,timerEndMarker.disabled,timerEndMarker.getAttribute('aria-valuenow')]};"
         "const applyRawStatus=request=>{document.getElementById('raw-json').value=JSON.stringify(request);document.getElementById('apply-json').click();return document.getElementById('json-status').textContent;},invalidFrameStatus=applyRawStatus({...generatedRequest,sectionEndAt:new Date(Date.parse(generatedRequest.sectionStartAt)+1020).toISOString()}),shortTimerStatus=applyRawStatus({...generatedRequest,timer:{startAt:generatedRequest.sectionStartAt,endAt:new Date(Date.parse(generatedRequest.sectionStartAt)+20).toISOString()}}),negativeTrimStatus=applyRawStatus({...generatedRequest,sourceVideo:{...generatedRequest.sourceVideo,recordingStartAt:new Date(Date.parse(generatedRequest.sectionStartAt)+40).toISOString()}}),fractionalTrimStatus=applyRawStatus({...generatedRequest,sourceVideo:{...generatedRequest.sourceVideo,recordingStartAt:new Date(Date.parse(generatedRequest.sectionStartAt)-20).toISOString()}});"
         "const rawRequest={...generatedRequest,synchronizationMode:'manual-anchor',telemetrySyncAt:'2026-10-24T00:20:00Z',cameraSyncAt:'2026-10-24T00:30:15Z',sectionStartAt:'2026-10-24T00:30:10Z',sectionEndAt:'2026-10-24T00:30:20Z',timer:{startAt:'2026-10-24T00:30:12Z',endAt:'2026-10-24T00:30:18Z'},sourceVideo:{fileId:'raw-video',recordingStartAt:'2026-10-24T00:30:00Z',timeZone:'Europe/Warsaw'}};document.getElementById('raw-json').value=JSON.stringify(rawRequest);document.getElementById('apply-json').click();const rawRestored={fileId:document.getElementById('source-video-file-id').value,start:document.getElementById('video-recording-start').value,zone:document.getElementById('video-timezone').value,confirmed:document.getElementById('video-clock-confirmation').dataset.confirmed,request:JSON.parse(document.getElementById('render-request').value).sourceVideo,status:document.getElementById('json-status').textContent,range:[outputStart.getAttribute('aria-valuenow'),outputEnd.getAttribute('aria-valuenow')],marker:syncMarker.getAttribute('aria-valuenow'),mode:{sourceControlsHidden:document.getElementById('source-output-controls').hidden,summaryHidden:document.getElementById('no-source-output-summary').hidden,stageHidden:document.getElementById('video-stage').hidden,transportHidden:document.querySelector('.video-transport').hidden,timelineLabel:timeline.getAttribute('aria-label')},timer:{enabled:timerToggle.checked,fields:[document.getElementById('timer-start-at').value,document.getElementById('timer-end-at').value],markers:[timerStartMarker.hidden,timerStartMarker.getAttribute('aria-valuenow'),timerEndMarker.hidden,timerEndMarker.getAttribute('aria-valuenow')]}};"
         "const clockCorrection=null;"
         "outcome={initial,initialPlayback,initialRange,markerReady,buffered:{beforeProgress:bufferedBeforeProgress,afterProgress:bufferedAfterProgress},timerDefaults,timerRange,timerMarkerPointer,timerMarkerKeyboard,markerPointer,markerKeyboard,typedManual,invalidFrameStatus,shortTimerStatus,negativeTrimStatus,fractionalTrimStatus,fixedOffsetRejected,shortcutHints,cropped,transportTime,scrubTime,keyboardTime,hover,playing,paused,mediaPlaying,mediaPaused,spacePlaying,spacePaused,shortcuts:{shortcutStart,rightPrevented,afterRight,shiftRightPrevented,afterShiftRight,leftPrevented,afterLeft,shiftLeftPrevented,afterShiftLeft,spacePrevented,afterSpacePaused,pausedAfterSecondSpace:video.paused},exclusions:{editableChecks,modifiedChecks,focusedButtonPrevented,afterFocusedButtonKey,afterFocusedButtonClick,hiddenStart,hiddenPrevented,afterHidden},fullscreen:{entry:fullscreenEntry,playback:fullscreenPlayback,buffered:fullscreenBuffered,afterFourSeconds,focusedHint:focusedFullscreenHint,exit:fullscreenExit,browserExit,buttonEntry,buttonExit},unsupported,rawRestored,clockCorrection,viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<script>(function(){"
                              (str fixture "<script>(function(){"))
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-video-player-browser-"
     "Browser-level video player regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- playback-capability-browser-outcome
  [page {:keys [window-size webcodecs? can-play-type inspected-duration]
         :or {inspected-duration 42}}]
  (let [fixture
        (str
         "<script>"
         "window.__capabilityState={callback:null,loads:[],analysisRequests:[],sessionRequests:[],canPlayTypeCalls:[],videoDecoderCalls:[]};"
         "window.fetch=(path,options={})=>{if(path==='/v1/drive/playback-analyses'){window.__capabilityState.analysisRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'supported-source.mov',evidence:{container:{format:'mov',majorBrand:'qt  '},video:{codec:'hevc',codecTag:'hvc1',profile:'Main',pixelFormat:'yuv420p'},audio:{codec:'aac'}}})});}if(path==='/v1/drive/playback-sessions'){window.__capabilityState.sessionRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:201,json:()=>Promise.resolve({playbackUrl:'/v1/drive/playback/00000000-0000-0000-0000-000000000155',contentType:'video/quicktime',size:2048})});}if(path==='/v1/drive/recording-clock-inspections'){return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'supported-source.mov',status:'manual',candidates:[],recommendedIndex:null,ambiguous:false,durationSeconds:" (json/write-str inspected-duration) ",limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}})});}return Promise.resolve({ok:true,status:204,json:()=>Promise.resolve({})});};"
         "class PickerView{setMimeTypes(){return this;}setIncludeFolders(){return this;}setSelectFolderEnabled(){return this;}setMode(){return this;}setEnableDrives(){return this;}}"
         "class UploadView extends PickerView{}"
         "class PickerBuilder{addView(){return this;}setSelectableMimeTypes(){return this;}setOAuthToken(){return this;}setDeveloperKey(){return this;}setAppId(){return this;}setOrigin(){return this;}setCallback(callback){window.__capabilityState.callback=callback;return this;}build(){return {setVisible(){}};}}"
         "window.google={picker:{DocsView:PickerView,DocsUploadView:UploadView,PickerBuilder,DocsViewMode:{LIST:'list'},Action:{LOADED:'loaded',PICKED:'picked',CANCEL:'cancel'}}};"
         "window.gapi={load(_module,handlers){window.__capabilityState.loads.push(handlers);}};"
         "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??42;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},buffered:{configurable:true,get(){return {length:0,start(){return 0;},end(){return 0;}};}}});"
         "HTMLMediaElement.prototype.canPlayType=function(type){window.__capabilityState.canPlayTypeCalls.push(type);return " (json/write-str can-play-type) ";};"
         (if webcodecs?
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:{isConfigSupported(config){window.__capabilityState.videoDecoderCalls.push(config);return Promise.resolve({supported:true});}}});"
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});")
         "HTMLMediaElement.prototype.load=function(){this.__duration=42;this.dispatchEvent(new Event('loadedmetadata'));};HTMLMediaElement.prototype.play=function(){this.__paused=false;this.dispatchEvent(new Event('play'));return Promise.resolve();};HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const state=window.__capabilityState;state.loads[0].callback();state.callback({action:google.picker.Action.PICKED,docs:[{id:'hevc-source',name:'ride.mov',mimeType:'video/quicktime'}]});await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));document.getElementById('video-recording-start').value='2026-07-26T07:12:05';document.getElementById('video-timezone').value='Europe/Warsaw';document.getElementById('confirm-video-clock').click();const video=document.getElementById('source-video-player');outcome={selection:document.getElementById('picker-selection').textContent,fileId:document.getElementById('source-video-file-id').value,analysisRequests:state.analysisRequests,sessionRequests:state.sessionRequests,canPlayTypeCalls:state.canPlayTypeCalls,videoDecoderCalls:state.videoDecoderCalls,status:document.getElementById('video-player-status').textContent,stageHidden:document.getElementById('video-stage').hidden,transportHidden:document.querySelector('.video-transport').hidden,summaryHidden:document.getElementById('no-source-output-summary').hidden,sourceEnd:document.getElementById('video-source-end').textContent,src:video.getAttribute('src'),viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<script>(function(){"
                              (str fixture "<script>(function(){"))
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-playback-capability-browser-"
     "Browser-level playback capability regression requires Chrome or Chromium"
     html
     (str "--window-size=" (or window-size "1280,900")))))

(defn- playback-preparation-failure-browser-outcome
  [page {:keys [analysis-response session-response window-size]}]
  (letfn [(response-js [{:keys [ok status body]}]
            (str "{ok:" (json/write-str ok)
                 ",status:" (long status)
                 ",json:()=>Promise.resolve(" (json/write-str body) ")}"))]
    (let [fixture
          (str
           "<script>"
           "window.__prepFailureState={callback:null,loads:[],analysisRequests:[],sessionRequests:[]};"
           "window.fetch=(path,options={})=>{if(path==='/v1/drive/playback-analyses'){window.__prepFailureState.analysisRequests.push(JSON.parse(options.body));return Promise.resolve("
           (response-js analysis-response)
           ");}if(path==='/v1/drive/playback-sessions'){window.__prepFailureState.sessionRequests.push(JSON.parse(options.body));return Promise.resolve("
           (response-js session-response)
           ");}if(path==='/v1/drive/recording-clock-inspections'){return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'broken-source.mp4',status:'candidate',candidates:[{source:'movie',kind:'explicit-offset',value:'2026-07-26T08:54:33+02:00'}],recommendedIndex:0,ambiguous:false,durationSeconds:125.5,limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}})});}return Promise.resolve({ok:true,status:204,json:()=>Promise.resolve({})});};"
           "class PickerView{setMimeTypes(){return this;}setIncludeFolders(){return this;}setSelectFolderEnabled(){return this;}setMode(){return this;}setEnableDrives(){return this;}}"
           "class UploadView extends PickerView{}"
           "class PickerBuilder{addView(){return this;}setSelectableMimeTypes(){return this;}setOAuthToken(){return this;}setDeveloperKey(){return this;}setAppId(){return this;}setOrigin(){return this;}setCallback(callback){window.__prepFailureState.callback=callback;return this;}build(){return {setVisible(){}};}}"
           "window.google={picker:{DocsView:PickerView,DocsUploadView:UploadView,PickerBuilder,DocsViewMode:{LIST:'list'},Action:{LOADED:'loaded',PICKED:'picked',CANCEL:'cancel'}}};"
           "window.gapi={load(_module,handlers){window.__prepFailureState.loads.push(handlers);}};"
           "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??42;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},buffered:{configurable:true,get(){return {length:0,start(){return 0;},end(){return 0;}};}}});"
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});HTMLMediaElement.prototype.canPlayType=function(){return 'probably';};HTMLMediaElement.prototype.load=function(){this.__duration=42;this.dispatchEvent(new Event('loadedmetadata'));};HTMLMediaElement.prototype.play=function(){this.__paused=false;this.dispatchEvent(new Event('play'));return Promise.resolve();};HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
           "</script>")
          scenario
          (str
           "<pre id=\"browser-result\">pending</pre><script>"
           "(async()=>{let outcome;try{"
           "const state=window.__prepFailureState;state.loads[0].callback();state.callback({action:google.picker.Action.PICKED,docs:[{id:'broken-source',name:'broken.mp4',mimeType:'video/mp4'}]});await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));document.getElementById('video-timezone').value='Europe/Warsaw';document.getElementById('confirm-video-clock').click();await new Promise(resolve=>setTimeout(resolve,0));const video=document.getElementById('source-video-player');outcome={selection:document.getElementById('picker-selection').textContent,fileId:document.getElementById('source-video-file-id').value,analysisRequests:state.analysisRequests,sessionRequests:state.sessionRequests,status:document.getElementById('video-player-status').textContent,stageHidden:document.getElementById('video-stage').hidden,transportHidden:document.querySelector('.video-transport').hidden,src:video.getAttribute('src'),viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
           "}catch(error){outcome={error:error.message,stack:error.stack};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
           "</script>")
          html (-> page
                   (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                   (str/replace "<script>(function(){"
                                (str fixture "<script>(function(){"))
                   (str/replace "</body>" (str scenario "</body>")))]
      (browser-outcome
       "agg-playback-preparation-browser-"
       "Browser-level playback preparation regression requires Chrome or Chromium"
       html
       (str "--window-size=" (or window-size "1280,900"))))))

(defn- preview-status-browser-outcome [page]
  (let [terminal-fragment
        (ui/preview-operation-fragment
         {:id "00000000-0000-0000-0000-000000000021"
          :state "failed"
          :progressPercent 100
          :error {:code "worker_failed"
                  :category "preview_rendering"
                  :requestId "00000000-0000-0000-0000-000000000021"
                  :stage "source_content"
                  :elapsedMs 4748
                  :retryable false}}
         "terminal-generation")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const terminalFragment=" (json/write-str terminal-fragment) ";"
         "const button=document.querySelector('[hx-post=\"/ui/preview\"]'),submit=document.getElementById('submit-button'),spinner=button.querySelector('.button-spinner');function buttonPresentation(){const previewStyle=getComputedStyle(button),submitStyle=getComputedStyle(submit);return {spinnerHidden:spinner?.hidden??null,spinnerInside:!!spinner&&button.contains(spinner),previewBackground:previewStyle.backgroundColor,submitBackground:submitStyle.backgroundColor,previewCursor:previewStyle.cursor,submitCursor:submitStyle.cursor,previewShadow:previewStyle.boxShadow,submitShadow:submitStyle.boxShadow};}const initial={submitDisabled:submit.disabled,status:document.getElementById('preview-submit-status').textContent,presentation:buttonPresentation()};"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
         "function configure(){const detail={elt:button,parameters:{},headers:{}};const event=new CustomEvent('htmx:configRequest',{bubbles:true,cancelable:true,detail});button.dispatchEvent(event);return {event,detail};}"
         "function transport(name,status=0){const target=document.getElementById('preview-result');target.dispatchEvent(new CustomEvent(name,{bubbles:true,detail:{elt:button,target,xhr:{status,getResponseHeader:()=>null}}}));return target;}"
         "const first=configure(),firstGeneration=first.detail.headers['X-Preview-Generation'],firstResult=document.getElementById('preview-result');"
         "const pending={text:document.getElementById('form-status').textContent,disabled:button.disabled,submitDisabled:submit.disabled,cleared:!firstResult.textContent.includes('stale prior success'),className:firstResult.className,presentation:buttonPresentation()};"
         "const unrelated=document.getElementById('job-result');unrelated.dispatchEvent(new CustomEvent('htmx:sendError',{bubbles:true,detail:{elt:unrelated,target:unrelated,xhr:{status:0,getResponseHeader:()=>null}}}));const unrelatedIgnored=button.disabled&&document.getElementById('preview-result').classList.contains('preview-pending');"
         "const duplicate=configure();"
         "const duplicateSuppressed=duplicate.event.defaultPrevented&&duplicate.detail.headers['X-Preview-Generation']===undefined;"
         "transport('htmx:responseError',504);const platform=document.getElementById('preview-result');const platformFailure={text:platform.textContent,disabled:button.disabled,submitDisabled:submit.disabled,presentation:buttonPresentation()};"
         "configure();transport('htmx:responseError',502);const gateway=document.getElementById('preview-result');const gatewayFailure={text:gateway.textContent,disabled:button.disabled};"
         "const retryAfterPlatform=configure(),retryGeneration=retryAfterPlatform.detail.headers['X-Preview-Generation'];"
         "const lateDetail={target:document.getElementById('preview-result'),xhr:{getResponseHeader:()=>null},requestConfig:{headers:{'X-Preview-Generation':firstGeneration}},shouldSwap:true};lateDetail.target.dispatchEvent(new CustomEvent('htmx:beforeSwap',{bubbles:true,detail:lateDetail}));"
         "transport('htmx:sendError');const dropped=document.getElementById('preview-result');const connectionLoss={text:dropped.textContent,disabled:button.disabled,lateRejected:!lateDetail.shouldSwap,presentation:buttonPresentation()};"
         "configure();transport('htmx:sendAbort');const aborted=document.getElementById('preview-result');const clientAbort={text:aborted.textContent,disabled:button.disabled,presentation:buttonPresentation()};"
         "configure();transport('htmx:timeout');const timedOut=document.getElementById('preview-result');const browserTimeout={text:timedOut.textContent,disabled:button.disabled,presentation:buttonPresentation()};"
         "const successfulRetry=configure(),successGeneration=successfulRetry.detail.headers['X-Preview-Generation'];const target=document.getElementById('preview-result');target.outerHTML='<article id=\"preview-result\" class=\"preview-gallery\" data-preview-operation=\"00000000-0000-0000-0000-000000000063\" data-preview-generation=\"'+successGeneration+'\"><img></article>';const success=document.getElementById('preview-result');success.dispatchEvent(new CustomEvent('htmx:afterSettle',{bubbles:true,detail:{target:success}}));"
         "const succeeded={text:document.getElementById('form-status').textContent,disabled:button.disabled,submitDisabled:submit.disabled,retried:successGeneration!==retryGeneration,presentation:buttonPresentation()};const submitDetail={elt:submit,parameters:{},headers:{}};const submitEvent=new CustomEvent('htmx:configRequest',{bubbles:true,cancelable:true,detail:submitDetail});submit.dispatchEvent(submitEvent);const duplicateSubmitDetail={elt:submit,parameters:{},headers:{}};const duplicateSubmitEvent=new CustomEvent('htmx:configRequest',{bubbles:true,cancelable:true,detail:duplicateSubmitDetail});submit.dispatchEvent(duplicateSubmitEvent);const submitFlow={firstAllowed:!submitEvent.defaultPrevented,duplicateSuppressed:duplicateSubmitEvent.defaultPrevented,idempotencyKey:submitDetail.headers['Idempotency-Key']};const raw=document.getElementById('raw-json'),invalidationAttempt=configure(),invalidationWasPending=!spinner.hidden;raw.value='changed';raw.dispatchEvent(new Event('input',{bubbles:true}));const rawInvalidated={submitDisabled:submit.disabled,className:document.getElementById('preview-result').className,invalidationWasPending,presentation:buttonPresentation()};const terminalAttempt=configure(),terminalGeneration=terminalAttempt.detail.headers['X-Preview-Generation'],terminalPending=document.getElementById('preview-result');terminalPending.outerHTML=terminalFragment.replace('terminal-generation',terminalGeneration);const terminalError=document.getElementById('preview-result');document.body.dispatchEvent(new CustomEvent('htmx:afterSettle',{bubbles:true,detail:{elt:terminalError,target:terminalPending,xhr:{aggPreviewGeneration:terminalGeneration,getResponseHeader:()=>terminalGeneration}}}));const terminalFailure={className:terminalError.className,text:terminalError.textContent,previewDisabled:button.disabled,submitDisabled:submit.disabled,submitStatus:document.getElementById('preview-submit-status').textContent,status:document.getElementById('form-status').textContent,presentation:buttonPresentation()};"
         "outcome={initial,pending,unrelatedIgnored,duplicateSuppressed,platformFailure,gatewayFailure,connectionLoss,clientAbort,browserTimeout,terminalFailure,succeeded,submitFlow,rawInvalidated};"
         "}catch(error){outcome={error:error.message};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));"
         "document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<div id=\"preview-result\"></div>"
                              "<article id=\"preview-result\" class=\"preview-gallery\">stale prior success</article>")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-preview-status-browser-"
     "Browser-level preview status regression requires Chrome or Chromium"
     html)))

(defn- durable-submit-browser-outcome [page]
  (let [accepted-fragment
        (ui/job-fragment
         {:id "00000000-0000-0000-0000-000000000093"
          :state "queued"
          :attempt 1})
        succeeded-fragment
        (ui/job-fragment
         {:id "00000000-0000-0000-0000-000000000093"
          :state "succeeded"
          :attempt 1
          :output {:driveWebViewLink "https://drive.example/result"}})
        failed-fragment
        (ui/job-fragment
         {:id "00000000-0000-0000-0000-000000000093"
          :state "failed"
          :attempt 1
          :failureCode "worker_failed"
          :retryable false})
        next-accepted-fragment
        (ui/job-fragment
         {:id "00000000-0000-0000-0000-000000000094"
          :state "queued"
          :attempt 1})
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const acceptedFragment=" (json/write-str accepted-fragment) ",succeededFragment=" (json/write-str succeeded-fragment) ",failedFragment=" (json/write-str failed-fragment) ",nextAcceptedFragment=" (json/write-str next-accepted-fragment) ";"
         "const form=document.getElementById('render-form'),submit=document.getElementById('submit-button'),jobResult=document.getElementById('job-result');"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
         "function configure(){const detail={elt:submit,parameters:{},headers:{}};const event=new CustomEvent('htmx:configRequest',{bubbles:true,cancelable:true,detail});submit.dispatchEvent(event);return {event,detail};}"
         "function swap(fragment){jobResult.innerHTML=fragment;jobResult.dispatchEvent(new CustomEvent('htmx:afterSwap',{bubbles:true,detail:{elt:form,target:jobResult}}));return {disabled:submit.disabled,ariaDisabled:submit.getAttribute('aria-disabled'),submitStatus:document.getElementById('preview-submit-status').textContent,formStatus:document.getElementById('form-status').textContent};}"
         "function pollSwap(fragment){jobResult.innerHTML=fragment;const job=jobResult.firstElementChild;job.dispatchEvent(new CustomEvent('htmx:afterSwap',{bubbles:true,detail:{elt:job,target:job}}));return {disabled:submit.disabled,ariaDisabled:submit.getAttribute('aria-disabled'),submitStatus:document.getElementById('preview-submit-status').textContent,formStatus:document.getElementById('form-status').textContent};}"
         "function resetSubmission(){const field=document.getElementById('future-trace-opacity-percent');field.value=field.value==='25'?'24':'25';field.dispatchEvent(new Event('input',{bubbles:true}));}"
         "function transportFailure(name,status=0){resetSubmission();const attempt=configure(),key=attempt.detail.headers['Idempotency-Key'];form.dispatchEvent(new CustomEvent(name,{bubbles:true,detail:{elt:form,target:jobResult,xhr:{status,getResponseHeader:()=>null}}}));const failure={disabled:submit.disabled,ariaDisabled:submit.getAttribute('aria-disabled'),submitStatus:document.getElementById('preview-submit-status').textContent,formStatus:document.getElementById('form-status').textContent};const retry=configure();return {...failure,retryAllowed:!retry.event.defaultPrevented,sameKey:key===retry.detail.headers['Idempotency-Key']};}"
         "const first=configure(),duplicate=configure(),accepted=swap(acceptedFragment),succeeded=swap(succeededFragment),failed=swap(failedFragment);resetSubmission();const oldJobIgnored=swap(succeededFragment);configure();const lateOldPoll=pollSwap(succeededFragment),nextAccepted=swap(nextAcceptedFragment),responseError=transportFailure('htmx:responseError',503),connectionError=transportFailure('htmx:sendError'),timeout=transportFailure('htmx:timeout'),cancelled=transportFailure('htmx:sendAbort');"
         "outcome={firstAllowed:!first.event.defaultPrevented,duplicateSuppressed:duplicate.event.defaultPrevented,...accepted,succeeded,failed,oldJobIgnored,lateOldPoll,nextAccepted,responseError,connectionError,timeout,cancelled};"
         "}catch(error){outcome={error:error.message};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-durable-submit-browser-"
     "Browser-level durable Submit regression requires Chrome or Chromium"
     html)))

(defn- preview-gallery-operation []
  {:id "00000000-0000-0000-0000-000000000061"
   :operationKind "key-moment-gallery"
   :state "succeeded"
   :progressPercent 100
   :result
   {:version 2
    :mode "final"
    :sections
    [{:id "heart-rate" :name "Heart rate" :unit "bpm"
      :moments [{:frameIndex 0 :elapsedSeconds 0.0 :elapsed "00:00.000"
                 :labels ["Video start"]
                 :eventLabel "Video start"
                 :value 120.0
                 :title "Video start - 120 bpm - 00:00.000"
                 :frameRef "a000"}
                {:frameIndex 25 :elapsedSeconds 1.0 :elapsed "00:01.000"
                 :labels ["Prominent maximum"]
                 :eventLabel "Prominent maximum"
                 :value 168.0
                 :title "Prominent maximum - 168 bpm - 00:01.000"
                 :frameRef "a001"}]}
     {:id "spo2" :name "SpO2" :unit "%"
      :moments [{:frameIndex 0 :elapsedSeconds 0.0 :elapsed "00:00.000"
                 :labels ["Video start"]
                 :eventLabel "Video start"
                 :value 97.0
                 :title "Video start - 97 % - 00:00.000"
                 :frameRef "a000"}]}]
    :assets
    [{:id "a000" :frameIndex 0 :kind "final"
      :image {:thumbnailUrl "/v1/previews/x/images/a000-final/thumbnail"
              :fullUrl "/v1/previews/x/images/a000-final/full"}}
     {:id "a001" :frameIndex 25 :kind "final"
      :image {:thumbnailUrl "/v1/previews/x/images/a001-final/thumbnail"
              :fullUrl "/v1/previews/x/images/a001-final/full"}}]}})

(defn- timer-preview-gallery-operation []
  {:id "00000000-0000-0000-0000-000000000082"
   :operationKind "key-moment-gallery"
   :state "succeeded"
   :progressPercent 100
   :result
   {:version 2
    :mode "final"
    :sections
    [{:id "heart-rate" :name "Heart rate" :unit "bpm"
      :moments
      [{:frameIndex 0 :elapsedSeconds 0.0 :elapsed "00:00.000"
        :labels ["Video start" "Trace start"]
        :eventLabel "Video start / Trace start"
        :value 120.0
        :title "Video start / Trace start - 120 bpm - 00:00.000"
        :frameRef "a000"}
       {:frameIndex 13 :elapsedSeconds 0.52 :elapsed "00:00.520"
        :labels ["Timer start"] :eventLabel "Timer start" :value 122.1
        :title "Timer start - 122.1 bpm - 00:00.520"
        :frameRef "a001"}
       {:frameIndex 38 :elapsedSeconds 1.52 :elapsed "00:01.520"
        :labels ["Timer end"] :eventLabel "Timer end" :value 126.1
        :title "Timer end - 126.1 bpm - 00:01.520"
        :frameRef "a002"}
       {:frameIndex 49 :elapsedSeconds 1.96 :elapsed "00:01.960"
        :labels ["Trace stop" "Video end"]
        :eventLabel "Trace stop / Video end"
        :value 127.8
        :title "Trace stop / Video end - 127.8 bpm - 00:01.960"
        :frameRef "a003"}]}]
    :assets
    (mapv (fn [index frame]
            (let [id (format "a%03d" index)]
              {:id id :frameIndex frame :kind "final"
               :image {:thumbnailUrl (str "/v1/previews/x/images/" id
                                          "-final/thumbnail")
                       :fullUrl (str "/v1/previews/x/images/" id
                                     "-final/full")}}))
          (range 4) [0 13 38 49])}})

(defn- wrapping-preview-gallery-operation []
  (let [operation (timer-preview-gallery-operation)
        extra-moments
        (mapv (fn [index frame]
                {:frameIndex frame
                 :elapsedSeconds (/ frame 25.0)
                 :elapsed (format "00:0%d.%03d" (quot frame 25)
                                  (* 40 (mod frame 25)))
                 :labels [(str "Selected moment " (inc index))]
                 :eventLabel (str "Selected moment " (inc index))
                 :value (+ 128.0 index)
                 :title (str "Selected moment " (inc index) " - "
                             (+ 128 index) " bpm - 00:0" (quot frame 25))
                 :frameRef (format "a%03d" index)})
              (range 4 7) [60 70 80])
        extra-assets
        (mapv (fn [index frame]
                (let [id (format "a%03d" index)]
                  {:id id :frameIndex frame :kind "final"
                   :image {:thumbnailUrl (str "/v1/previews/x/images/" id
                                              "-final/thumbnail")
                           :fullUrl (str "/v1/previews/x/images/" id
                                         "-final/full")}}))
              (range 4 7) [60 70 80])]
    (-> operation
        (update-in [:result :sections 0 :moments] into extra-moments)
        (update-in [:result :assets] into extra-assets))))

(defn- preview-gallery-browser-outcome
  ([narrow?]
   (preview-gallery-browser-outcome (preview-gallery-operation) narrow?))
  ([operation viewport-or-narrow?]
   (let [viewport (if (keyword? viewport-or-narrow?)
                    viewport-or-narrow?
                    (if viewport-or-narrow? :narrow :wide))
         narrow? (= :narrow viewport)
         fragment (ui/preview-operation-fragment operation "generation-1")
         page (-> (ui/page {:user {:email "owner@example.com" :role :member}
                            :csrf "csrf-test"
                            :tokens [] :members [] :logs-enabled? false})
                  (str/replace "<div id=\"preview-result\"></div>" fragment)
                  (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" ""))
         request-json (json/write-str (fixture/render-request))
         scenario
         (str
          "<pre id=\"browser-result\">pending</pre><script>"
          "let outcome;try{const moments=document.querySelector('.preview-moments'),buttons=[...document.querySelectorAll('.preview-open')],titles=[...document.querySelectorAll('.photo-title')],openIndex=Math.min(3,buttons.length-2),open=buttons[openIndex],dialog=document.getElementById('preview-dialog');"
          "const momentCount=document.querySelectorAll('.preview-moment').length,display=getComputedStyle(moments).display,roles=[...document.querySelectorAll('.frame-role')].map(node=>node.textContent),photoTitles=titles.map(node=>node.textContent),titlePlacement=titles.every((title,index)=>title.nextElementSibling===buttons[index]&&title.getBoundingClientRect().bottom<=buttons[index].getBoundingClientRect().top+1),titleRects=titles.map(title=>title.getBoundingClientRect()),titlesDoNotOverlap=titleRects.every((rect,index)=>titleRects.every((other,otherIndex)=>index===otherIndex||rect.right<=other.left||other.right<=rect.left||rect.bottom<=other.top||other.bottom<=rect.top)),thumbnailWidth=buttons[0].getBoundingClientRect().width,viewportWidth=window.innerWidth,noOverflow=document.documentElement.scrollWidth<=viewportWidth,meaningfulAlts=buttons.every(button=>button.querySelector('img').alt.length>12),eagerImages=buttons.every(button=>button.querySelector('img').loading==='eager'),nativeButtons=buttons.every(button=>button.tagName==='BUTTON');const sectionLayouts=[...document.querySelectorAll('.trace-preview')].map(section=>{const container=section.querySelector('.preview-moments'),containerRect=container.getBoundingClientRect(),rows=[];[...container.querySelectorAll('.preview-moment')].forEach(card=>{const rect=card.getBoundingClientRect();let row=rows.find(candidate=>Math.abs(candidate.top-rect.top)<2);if(!row){row={top:rect.top,left:rect.left,right:rect.right,count:0};rows.push(row);}row.left=Math.min(row.left,rect.left);row.right=Math.max(row.right,rect.right);row.count+=1;});return {containerWidth:containerRect.width,sectionWidth:section.getBoundingClientRect().width,flexWrap:getComputedStyle(container).flexWrap,rowCounts:rows.map(row=>row.count),centered:rows.every(row=>Math.abs(((row.left+row.right)/2)-((containerRect.left+containerRect.right)/2))<2),noOverflow:section.scrollWidth<=section.clientWidth+1};}),multipleOnFirstRow=sectionLayouts.some(layout=>layout.rowCounts[0]>1),wrapped=sectionLayouts.some(layout=>layout.rowCounts.length>1),rowsCentered=sectionLayouts.every(layout=>layout.centered),traceNoOverflow=sectionLayouts.every(layout=>layout.noOverflow);"
          "const sequenceTitles=buttons.map(button=>button.dataset.title),sequenceFullUrls=buttons.map(button=>button.dataset.full),thumbnailUrlsOnly=buttons.every(button=>button.querySelector('img').getAttribute('src').endsWith('/thumbnail'));const previous=dialog.querySelector('.preview-previous'),next=dialog.querySelector('.preview-next'),counter=dialog.querySelector('.preview-counter'),dialogTitle=dialog.querySelector('#preview-dialog-title'),dialogImage=dialog.querySelector('img'),viewerControls=!!(previous&&next&&counter&&dialogTitle);"
          "open.focus();open.click();const dialogRect=dialog.getBoundingClientRect(),dialogOpened=dialog.open&&dialogImage.alt===open.dataset.alt&&dialogImage.getAttribute('src')===open.dataset.full,clickedPosition=viewerControls&&dialogTitle.textContent===open.dataset.title&&counter.textContent==='Image '+(openIndex+1)+' of '+buttons.length,modalFocus=dialog.matches(':modal')&&dialog.contains(document.activeElement);document.getElementById('preview-button').focus();const focusContained=dialog.contains(document.activeElement);dialog.focus();const viewportFit=dialogRect.width>=window.innerWidth-24&&dialogRect.height>=window.innerHeight-24&&getComputedStyle(dialogImage).objectFit==='contain',controlsVisible=viewerControls&&[previous,next].every(button=>button.getBoundingClientRect().width>0),accessibleViewer=viewerControls&&dialog.getAttribute('aria-labelledby')===dialogTitle.id&&counter.getAttribute('aria-live')==='polite'&&[previous,next,dialog.querySelector('.preview-dialog-close')].every(button=>(button.getAttribute('aria-label')||button.textContent).trim().length>0);"
          "let buttonNavigation=false,keyboardNavigation=false,endStates=false,escapePreserved=false;if(viewerControls){while(!next.disabled)next.click();buttonNavigation=counter.textContent==='Image '+buttons.length+' of '+buttons.length&&dialogTitle.textContent===sequenceTitles.at(-1)&&next.disabled&&!previous.disabled;dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));const stoppedAtEnd=counter.textContent==='Image '+buttons.length+' of '+buttons.length;dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true,cancelable:true}));keyboardNavigation=stoppedAtEnd&&counter.textContent==='Image '+(buttons.length-1)+' of '+buttons.length&&dialogTitle.textContent===sequenceTitles.at(-2);dialog.querySelector('.preview-dialog-close').click();const first=buttons[0];first.focus();first.click();dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true,cancelable:true}));endStates=previous.disabled&&!next.disabled&&counter.textContent==='Image 1 of '+buttons.length;const cancelEvent=new Event('cancel',{cancelable:true});escapePreserved=dialog.dispatchEvent(cancelEvent);if(escapePreserved)dialog.close();}else{dialog.querySelector('.preview-dialog-close').click();}const focusReturned=document.activeElement===buttons[0];"
          "let stale=false,staleSwapRejected=false;if(!" narrow? "){const raw=document.getElementById('raw-json');raw.value=" (json/write-str request-json) ";document.getElementById('apply-json').click();const staleTarget=document.querySelector('.preview-stale');stale=!!staleTarget;const detail={target:staleTarget,xhr:{getResponseHeader:()=>\"generation-1\"},shouldSwap:true};staleTarget.dispatchEvent(new CustomEvent('htmx:beforeSwap',{bubbles:true,detail}));staleSwapRejected=!detail.shouldSwap;}"
          "outcome={momentCount,buttonCount:buttons.length,display,roles,photoTitles,ariaLabels:buttons.map(button=>button.getAttribute('aria-label')),titlePlacement,titlesDoNotOverlap,titleRects:titleRects.map(rect=>({left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom})),thumbnailWidth,viewportWidth,noOverflow,traceNoOverflow,multipleOnFirstRow,wrapped,rowsCentered,sectionLayouts,meaningfulAlts,eagerImages,nativeButtons,sequenceTitles,sequenceFullUrls,thumbnailUrlsOnly,viewerControls,dialogOpened,clickedPosition,modalFocus,focusContained,viewportFit,controlsVisible,accessibleViewer,buttonNavigation,keyboardNavigation,endStates,escapePreserved,focusReturned,stale,staleSwapRejected};"
          "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
          "</script>")
         html (str/replace page "</body>" (str scenario "</body>"))]
     (case viewport
       :narrow
       (browser-outcome "agg-preview-narrow-"
                        "Narrow preview regression requires Chrome or Chromium"
                        html "--window-size=390,844")
       :wrapping
       (browser-outcome "agg-preview-wrapping-"
                        "Wrapping preview regression requires Chrome or Chromium"
                        html "--window-size=720,900")
       (browser-outcome "agg-preview-desktop-"
                        "Desktop preview regression requires Chrome or Chromium"
                        html "--window-size=1280,900")))))

(defn- telemetry-file-browser-outcome [page window-size]
  (let [fit-base64 (str/trim
                    (slurp (io/resource "fixtures/garmin/activity.fit.b64")))
        csv-text (str/trim (slurp (io/resource "fixtures/polar/valid.csv")))
        alternate-polar-text (str "Date/Time;HR (bpm)\n"
                                  "2026-07-17T10:00:00Z;130\n"
                                  "2026-07-17T10:00:02Z;132")
        oxiwear-text (str/trim
                      (slurp (io/resource "fixtures/oxiwear/hr-midnight.csv")))
        summary-text (str "Activity Type,Date,Favorite,Title,Distance\n"
                          "Running,2026-07-17,false,Morning Run,5.2")
        ambiguous-text (str "timestamp,heart_rate,reading_time,pulse_rate\n"
                            "2026-07-17T10:00:00Z,130,"
                            "2026-07-17T10:00:00Z,130")
        malformed-polar-text (str "timestamp,heart_rate\n"
                                  "not-a-time,secret-row-value")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const format=document.getElementById('telemetry-format'),input=document.getElementById('telemetry-file'),telemetry=document.getElementById('telemetry'),status=document.getElementById('telemetry-status'),advanced=document.getElementById('advanced-activity-data'),advancedFormat=document.getElementById('advanced-telemetry-format'),advancedInput=document.getElementById('advanced-telemetry-file'),advancedStatus=document.getElementById('advanced-telemetry-status'),advancedRoute=document.getElementById('open-advanced-activity-data'),next=document.getElementById('wizard-next');"
         "document.querySelector('input[name=\"wizard-outcome\"][value=\"transparent-overlay\"]').click();next.click();document.getElementById('timezone').value='UTC';document.getElementById('section-start-at').value='2026-07-17T10:00:00';document.getElementById('section-end-at').value='2026-07-17T10:00:02';next.click();"
         "const waitForStatus=node=>new Promise((resolve,reject)=>{const deadline=Date.now()+5000,check=()=>{if(node.classList.contains('success')||node.classList.contains('error'))resolve();else if(Date.now()>deadline)reject(new Error('Timed out waiting for activity-file status'));else setTimeout(check,5);};check();});"
         "const upload=async(target,file,statusNode)=>{const transfer=new DataTransfer();if(file)transfer.items.add(file);target.files=transfer.files;target.dispatchEvent(new Event('change',{bubbles:true}));const clearedImmediately=telemetry.value==='';if(file)await waitForStatus(statusNode);return {clearedImmediately,format:format.value,content:telemetry.value,status:statusNode.textContent,success:statusNode.classList.contains('success'),error:statusNode.classList.contains('error')};};"
         "const fitBytes=Uint8Array.from(atob(" (json/write-str fit-base64) "),character=>character.charCodeAt(0));"
         "const fit=await upload(input,new File([fitBytes],'input.fit',{type:'application/octet-stream'}),status);fit.matches=fit.content===" (json/write-str fit-base64) ";fit.formValid=document.getElementById('render-form').checkValidity();fit.invalidIds=[...document.getElementById('render-form').querySelectorAll(':invalid')].map(node=>node.id||node.getAttribute('name')||node.tagName.toLowerCase());"
         "const polar=await upload(input,new File([" (json/write-str csv-text) "],'polar.csv',{type:'text/csv'}),status);polar.matches=polar.content===" (json/write-str csv-text) ";"
         "const alternate=await upload(input,new File([" (json/write-str alternate-polar-text) "],'alternate.csv',{type:'text/csv'}),status);alternate.matches=alternate.content===" (json/write-str alternate-polar-text) ";"
         "const cancelled=await upload(input,null,status);cancelled.inputFiles=input.files.length;cancelled.status=status.textContent;"
         "const oxiwear=await upload(input,new File([" (json/write-str oxiwear-text) "],'oxiwear.csv',{type:'text/csv'}),status);oxiwear.routeVisible=!advancedRoute.hidden;advancedRoute.click();oxiwear.advancedOpen=advanced.open;oxiwear.focused=document.activeElement===advancedFormat;advancedFormat.value='oxiwear-hr-csv';advancedFormat.dispatchEvent(new Event('change',{bubbles:true}));"
         "const advancedUpload=await upload(advancedInput,new File([" (json/write-str oxiwear-text) "],'oxiwear.csv',{type:'text/csv'}),advancedStatus);advancedUpload.matches=advancedUpload.content===" (json/write-str oxiwear-text) ";advancedUpload.advancedFormat=advancedFormat.value;"
         "const summary=await upload(input,new File([" (json/write-str summary-text) "],'activities.csv',{type:'text/csv'}),status);"
         "const ambiguous=await upload(input,new File([" (json/write-str ambiguous-text) "],'ambiguous.csv',{type:'text/csv'}),status);"
         "const wrongExtension=await upload(input,new File([" (json/write-str csv-text) "],'activity.txt',{type:'text/plain'}),status);"
         "const malformedCsv=await upload(input,new File([" (json/write-str malformed-polar-text) "],'malformed.csv',{type:'text/csv'}),status);malformedCsv.privateContentLeaked=malformedCsv.status.includes('secret-row-value');"
         "const malformedFit=await upload(input,new File([new Uint8Array([14,1,0,0,4,0,0,0,46,70,73,84])],'malformed.fit',{type:'application/octet-stream'}),status);"
         "const oversized=await upload(input,new File([new Uint8Array(10485761)],'oversized.fit',{type:'application/octet-stream'}),status);"
         "const NativeFileReader=FileReader;FileReader=function(){const reader=new NativeFileReader(),nativeReadAsText=reader.readAsText.bind(reader);reader.readAsText=function(file,...args){if(file?.name==='unreadable.csv'){setTimeout(()=>reader.onerror?.(new ProgressEvent('error')),0);return;}return nativeReadAsText(file,...args);};return reader;};FileReader.prototype=NativeFileReader.prototype;const readFailure=await upload(input,new File([" (json/write-str csv-text) "],'unreadable.csv',{type:'text/csv'}),status);FileReader=NativeFileReader;readFailure.privateContentLeaked=readFailure.status.includes('2026-07-17');"
         "const panel=document.getElementById('activity-data-step'),rect=panel.getBoundingClientRect();outcome={fit,polar,alternate,cancelled,oxiwear,advancedUpload,summary,ambiguous,wrongExtension,malformedCsv,malformedFit,oversized,readFailure,activityStep:document.getElementById('compose-workflow').dataset.currentStep,labels:{simple:input.labels[0]?.textContent||'',advanced:advancedInput.labels[0]?.textContent||'',format:advancedFormat.labels[0]?.textContent||''},advancedSummary:advanced.querySelector('summary').textContent,viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth,panelFits:rect.left>=-.5&&rect.right<=innerWidth+.5};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome-with-budget-and-timeout
     "agg-telemetry-file-browser-"
     "Browser-level telemetry file regression requires Chrome or Chromium"
     html
     20000
     60000
     (str "--window-size=" window-size))))

(defn- future-trace-opacity-browser-outcome [page]
  (let [request (fixture/render-request)
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),opacity=document.getElementById('future-trace-opacity-percent'),jsonStatus=document.getElementById('json-status'),formStatus=document.getElementById('form-status'),preview=document.getElementById('preview-button'),base="
         (json/write-str request) ";"
         "const bounds={type:opacity.type,min:opacity.min,max:opacity.max,defaultValue:Number(opacity.value)};"
         "raw.value=JSON.stringify(base);apply.click();const omitted={form:Number(opacity.value),json:JSON.parse(raw.value).futureTraceOpacityPercent};"
         "raw.value=JSON.stringify({...base,futureTraceOpacityPercent:100});apply.click();const applied={form:Number(opacity.value),json:JSON.parse(raw.value).futureTraceOpacityPercent};"
         "opacity.value='0';opacity.dispatchEvent(new Event('input',{bubbles:true}));const generated=JSON.parse(raw.value).futureTraceOpacityPercent;"
         "raw.value=JSON.stringify({...base,futureTraceOpacityPercent:'25'});apply.click();const nonNumeric=jsonStatus.textContent;"
         "raw.value=JSON.stringify({...base,futureTraceOpacityPercent:101});apply.click();const outOfRange=jsonStatus.textContent;"
         "opacity.value='';const detail={elt:preview,parameters:{},headers:{}};const event=new CustomEvent('htmx:configRequest',{bubbles:true,cancelable:true,detail});preview.dispatchEvent(event);const blank={prevented:event.defaultPrevented,message:formStatus.textContent};"
         "outcome={bounds,omitted,applied,generated,nonNumeric,outOfRange,blank};"
         "}catch(error){outcome={error:error.message};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-future-opacity-browser-"
     "Future trace opacity form regression requires Chrome or Chromium"
     html)))

(defn- display-time-zone-browser-outcome [page]
  (let [base (-> (fixture/render-request)
                 (assoc :displayTimeZone "Europe/Warsaw"
                        :synchronizationMode "shared-clock")
                 (dissoc :telemetrySyncAt :cameraSyncAt))
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{const base=" (json/write-str base) ",raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),selector=document.getElementById('timezone'),hidden=document.getElementById('render-request'),jsonStatus=document.getElementById('json-status');function applyRequest(request){raw.value=JSON.stringify(request);apply.click();return {selector:selector.value,option:[...selector.options].some(option=>option.value===request.displayTimeZone),status:jsonStatus.textContent,request:JSON.parse(hidden.value)}}const preset=applyRequest({...base,displayTimeZone:'Europe/Warsaw'}),custom=applyRequest({...base,displayTimeZone:'Pacific/Auckland'});document.getElementById('section-start-at').dispatchEvent(new Event('input',{bubbles:true}));custom.regenerated=JSON.parse(hidden.value);custom.absoluteTimestampsPreserved=['sectionStartAt','sectionEndAt'].every(key=>Date.parse(custom.regenerated[key])===Date.parse(base[key]));selector.value='local';selector.dispatchEvent(new Event('input',{bubbles:true}));const local={browserZone:Intl.DateTimeFormat().resolvedOptions().timeZone,request:JSON.parse(hidden.value)};const missingRequest={...base};delete missingRequest.displayTimeZone;const missing=applyRequest(missingRequest);const unknown=applyRequest({...base,displayTimeZone:'Private/Unknown-Zone'});outcome={preset,custom,local,missing,unknown};}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome-with-timeout
     "agg-display-time-zone-browser-"
     "Browser-level display timezone regression requires Chrome or Chromium"
     html
     30000)))

(defn- wizard-outcome-browser-outcome [page]
  (let [request (assoc (fixture/render-request)
                       :sourceVideo
                       {:fileId "drive-source"
                        :recordingStartAt "2026-07-17T09:00:00Z"
                        :timeZone "Europe/Warsaw"}
                       :outputFormat "h264-mp4"
                       :fitMode "letterbox"
                       :audioMode "source+heartbeat")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),source=document.getElementById('source-video-file-id'),hidden=document.getElementById('render-request'),raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),transparent=document.querySelector('input[name=\"wizard-outcome\"][value=\"transparent-overlay\"]'),finished=document.querySelector('input[name=\"wizard-outcome\"][value=\"finished-video\"]');"
         "const initial={workflowHidden:workflow.hidden,selected:document.querySelector('input[name=\"wizard-outcome\"]:checked')?.value||null};"
         "raw.value=JSON.stringify(" (json/write-str request) ");apply.click();"
         "const appliedRequest=JSON.parse(hidden.value),applied={workflowHidden:workflow.hidden,route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,selected:document.querySelector('input[name=\"wizard-outcome\"]:checked')?.value,source:source.value,projectedSource:appliedRequest.sourceVideo?.fileId||null};"
         "transparent.click();const transparentRequest=JSON.parse(hidden.value),inactive={route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,selected:transparent.checked,sourceDraft:source.value,projectedSource:transparentRequest.sourceVideo||null,outputFormat:transparentRequest.outputFormat||null};"
         "finished.click();const restoredRequest=JSON.parse(hidden.value),restored={route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,selected:finished.checked,source:source.value,projectedSource:restoredRequest.sourceVideo?.fileId||null,outputFormat:restoredRequest.outputFormat||null};"
         "outcome={initial,applied,inactive,restored};"
         "}catch(error){outcome={error:error.message};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-wizard-outcome-browser-"
     "Wizard outcome browser regression requires Chrome or Chromium"
     html)))

(defn- wizard-shell-browser-outcome [page window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),header=document.getElementById('wizard-current-step-header'),heading=document.getElementById('wizard-current-step-heading'),progress=document.getElementById('wizard-progress'),stepList=document.getElementById('wizard-step-list'),errorSummary=document.getElementById('wizard-error-summary'),back=document.getElementById('wizard-back'),next=document.getElementById('wizard-next'),finished=document.querySelector('input[name=\"wizard-outcome\"][value=\"finished-video\"]'),transparent=document.querySelector('input[name=\"wizard-outcome\"][value=\"transparent-overlay\"]');"
         "const current=()=>workflow.dataset.currentStep,activePanels=()=>[...document.querySelectorAll('[data-wizard-panel]')].filter(panel=>!panel.hidden).map(panel=>panel.dataset.stepId),snapshot=()=>({current:current(),heading:heading.textContent,progress:progress.textContent,activePanels:activePanels(),currentSemantic:header.getAttribute('aria-current'),currentButtons:[...stepList.querySelectorAll('[aria-current=\"step\"]')].map(button=>button.dataset.stepId),backDisabled:back.disabled,nextHidden:next.hidden,focus:document.activeElement.id||null,noOverflow:document.documentElement.scrollWidth<=innerWidth});"
         "const initial=snapshot();"
         "finished.click();const finishedRoute=snapshot();next.click();const source=snapshot();"
         "next.click();const sourceError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};"
         "document.getElementById('source-video-file-id').value='drive-source';next.click();const activity=snapshot();"
         "const backedPop=new Promise(resolve=>window.addEventListener('popstate',()=>setTimeout(resolve,0),{once:true}));back.click();await backedPop;const backed=snapshot();const outcomeButton=[...stepList.querySelectorAll('button')].find(button=>button.dataset.stepId==='outcome');outcomeButton.click();const direct=snapshot();"
         "finished.click();next.click();document.getElementById('source-video-file-id').value='drive-source';next.click();document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T09:00:00Z,120';next.click();const synchronizationDecision=snapshot();"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"shared-clock\"]').click();const shared={...snapshot(),stepCount:stepList.querySelectorAll('li').length};"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();const manual={...snapshot(),stepCount:stepList.querySelectorAll('li').length};next.click();const matching=snapshot();next.click();const matchingError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};"
         "const popped=new Promise(resolve=>window.addEventListener('popstate',()=>setTimeout(resolve,0),{once:true}));history.back();await popped;const browserBack=snapshot();"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"shared-clock\"]').click();next.click();const confirmVideoClock=snapshot();next.click();const confirmError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};document.getElementById('video-recording-start').value='2026-07-17T09:00:00';document.getElementById('video-timezone').value='UTC';document.getElementById('confirm-video-clock').click();next.click();const finishedTimespan=snapshot();"
         "document.getElementById('telemetry-format').value='';document.getElementById('telemetry').value='';document.getElementById('section-start-at').value='';document.getElementById('section-end-at').value='';transparent.click();const transparentRoute=snapshot();next.click();const transparentActivity=snapshot();next.click();const activityError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};"
         "document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T09:00:00Z,120';next.click();const timespan=snapshot();next.click();const timingError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};"
         "document.getElementById('timezone').value='UTC';document.getElementById('section-start-at').value='2026-07-17T09:00:00';document.getElementById('section-end-at').value='2026-07-17T09:00:02';next.click();const optional=snapshot();document.getElementById('timer-enabled').click();const branched={...snapshot(),stepCount:stepList.querySelectorAll('li').length};next.click();const timer=snapshot();document.getElementById('timer-start-at').value='';document.getElementById('timer-end-at').value='';next.click();const timerError={...snapshot(),message:errorSummary.textContent,errorFocused:document.activeElement===errorSummary};document.getElementById('timer-enabled').click();const pruned={...snapshot(),stepCount:stepList.querySelectorAll('li').length};"
         "outcome={viewportWidth:innerWidth,initial,finishedRoute,source,sourceError,activity,backed,direct,synchronizationDecision,shared,manual,matching,matchingError,browserBack,confirmVideoClock,confirmError,finishedTimespan,transparentRoute,transparentActivity,activityError,timespan,timingError,optional,branched,timer,timerError,pruned};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome-with-timeout
     "agg-wizard-shell-browser-"
     "Wizard shell browser regression requires Chrome or Chromium"
     html
     60000
     (str "--window-size=" window-size))))

(defn- wizard-review-browser-outcome [page window-size]
  (let [request (-> (fixture/render-request)
                    (assoc :synchronizationMode "shared-clock"
                           :timer {:startAt "2026-07-17T09:00:00.400Z"
                                   :endAt "2026-07-17T09:00:01.600Z"})
                    (dissoc :telemetrySyncAt :cameraSyncAt))
        finished-request (assoc request
                                :sourceVideo
                                {:fileId "drive-source"
                                 :recordingStartAt
                                 "2026-07-17T09:00:00.000Z"
                                 :timeZone "Europe/Warsaw"}
                                :outputFormat "prores-422-mov"
                                :fitMode "crop"
                                :audioMode "source-only")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),next=document.getElementById('wizard-next'),review=document.getElementById('review-step'),reviewSections=document.getElementById('review-sections'),timer=document.getElementById('timer-enabled'),timerStart=document.getElementById('timer-start-at'),timerEnd=document.getElementById('timer-end-at'),input=node=>node.dispatchEvent(new Event('input',{bubbles:true})),current=()=>workflow.dataset.currentStep,reviewSnapshot=()=>({current:current(),steps:[...reviewSections.querySelectorAll('[data-review-step]')].map(section=>section.dataset.reviewStep),titles:[...reviewSections.querySelectorAll('h3')].map(node=>node.textContent),summaries:[...reviewSections.querySelectorAll('p')].map(node=>node.textContent),editSteps:[...reviewSections.querySelectorAll('[data-edit-step]')].map(button=>button.dataset.editStep),nextHidden:next.hidden,actionsInside:review.contains(document.getElementById('preview-button'))&&review.contains(document.getElementById('submit-button')),noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth});"
         "raw.value=JSON.stringify(" (json/write-str request) ");apply.click();"
         "for(let count=0;count<7&&current()!=='review';count++)next.click();"
         "const initialReview=reviewSnapshot(),timerEdit=[...reviewSections.querySelectorAll('[data-edit-step]')].find(button=>button.dataset.editStep==='timer-overlay');timerEdit.click();const edited={current:current(),start:timerStart.value,end:timerEnd.value},previewResult=document.getElementById('preview-result');previewResult.dataset.previewOperation='preview-1';previewResult.className='preview-gallery';"
         "timer.click();const deselected={current:current(),requestTimer:JSON.parse(document.getElementById('render-request').value).timer||null,previewClass:previewResult.className};"
         "const optionalEdit=[...reviewSections.querySelectorAll('[data-edit-step]')].find(button=>button.dataset.editStep==='optional-overlays');optionalEdit.click();timer.click();next.click();const restored={current:current(),start:timerStart.value,end:timerEnd.value};next.click();const finishLabel=next.textContent;next.click();const restoredReview=reviewSnapshot();"
         "const transparent={summaryHidden:document.getElementById('no-source-output-summary').hidden,sourceControlsHidden:document.getElementById('source-output-controls').hidden,requestOutputFormat:JSON.parse(document.getElementById('render-request').value).outputFormat||null};"
         "raw.value=JSON.stringify(" (json/write-str finished-request) ");apply.click();for(let count=0;count<12&&current()!=='review';count++)next.click();const finishedReview=reviewSnapshot(),finishedGenerated=JSON.parse(document.getElementById('render-request').value),finished={review:finishedReview,summaryHidden:document.getElementById('no-source-output-summary').hidden,sourceControlsHidden:document.getElementById('source-output-controls').hidden,request:{outputFormat:finishedGenerated.outputFormat,fitMode:finishedGenerated.fitMode,audioMode:finishedGenerated.audioMode}};"
         "outcome={viewportWidth:innerWidth,initialReview,edited,deselected,restored,finishLabel,restoredReview,transparent,finished,advancedOpen:document.querySelector('.advanced-output-settings').open};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-wizard-review-browser-"
     "Wizard Review browser regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- project-json-browser-outcome [page]
  (let [request (assoc (fixture/render-request)
                       :displayTimeZone "Europe/Warsaw")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),raw=document.getElementById('raw-json'),applyRaw=document.getElementById('apply-json'),project=document.getElementById('project-json'),applyProject=document.getElementById('apply-project-json'),copyProject=document.getElementById('copy-project-json'),projectStatus=document.getElementById('project-json-status'),hidden=document.getElementById('render-request');"
         "raw.value=JSON.stringify(" (json/write-str request) ");applyRaw.click();copyProject.click();await new Promise(resolve=>setTimeout(resolve,0));const exported=JSON.parse(project.value);"
         "const edited={...exported,renderRequest:null,currentStepId:'activity-data',visitedStepIds:['outcome','activity-data'],decisions:{synchronizationMode:'shared-clock',optionalOverlays:[]},sharedInput:{...exported.sharedInput,telemetry:'timestamp,heart_rate\\n2026-07-17T09:00:00Z,111'},routeDrafts:{...exported.routeDrafts,'transparent-overlay':{sectionStartAt:'2026-07-17T09:00:00.000Z',sectionEndAt:'2026-07-17T09:00:02.000Z'}}};project.value=JSON.stringify(edited);applyProject.click();await new Promise(resolve=>setTimeout(resolve,0));const applied={route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,status:projectStatus.textContent,telemetry:document.getElementById('telemetry').value,renderRequest:hidden.value,project:JSON.parse(project.value)};"
         "const preservedBefore={route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,telemetry:document.getElementById('telemetry').value,renderRequest:hidden.value};project.value=JSON.stringify({...edited,extra:true});applyProject.click();await new Promise(resolve=>setTimeout(resolve,0));const invalid={route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,status:projectStatus.textContent,telemetry:document.getElementById('telemetry').value,renderRequest:hidden.value,project:project.value,preserved:JSON.stringify(preservedBefore)===JSON.stringify({route:workflow.dataset.activeRoute,currentStep:workflow.dataset.currentStep,telemetry:document.getElementById('telemetry').value,renderRequest:hidden.value})};"
         "outcome={exported:{schemaVersion:exported.schemaVersion,activeRoute:exported.activeRoute,hasRenderRequest:!!exported.renderRequest,nestedTelemetry:exported.renderRequest?.telemetry||null},applied,invalid};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-project-json-browser-"
     "Project JSON browser regression requires Chrome or Chromium"
     html)))

(defn- persistent-timing-dock-browser-outcome [page window-size]
  (let [fixture
        (str
         "<script>"
         "window.__timingFullscreen=null;"
         "Object.defineProperty(document,'fullscreenElement',{configurable:true,get(){return window.__timingFullscreen;}});"
         "Element.prototype.requestFullscreen=function(){window.__timingFullscreen=this;document.dispatchEvent(new Event('fullscreenchange'));return Promise.resolve();};"
         "document.exitFullscreen=function(){window.__timingFullscreen=null;document.dispatchEvent(new Event('fullscreenchange'));return Promise.resolve();};"
         "</script>")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),workspace=document.getElementById('video-player'),dock=document.getElementById('timing-dock'),timelineWrap=document.getElementById('video-timeline-wrap'),chrome=document.getElementById('video-chrome'),next=document.getElementById('wizard-next'),transparent=document.querySelector('input[name=\"wizard-outcome\"][value=\"transparent-overlay\"]'),finished=document.querySelector('input[name=\"wizard-outcome\"][value=\"finished-video\"]'),start=document.getElementById('section-start-at'),end=document.getElementById('section-end-at'),zone=document.getElementById('timezone'),camera=document.getElementById('camera-sync-at'),telemetrySync=document.getElementById('telemetry-sync-at'),timerStart=document.getElementById('timer-start-at'),timerEnd=document.getElementById('timer-end-at'),input=node=>node.dispatchEvent(new Event('input',{bubbles:true})),currentPanel=()=>[...document.querySelectorAll('[data-wizard-panel]')].find(panel=>!panel.hidden),before=(first,second)=>first.getBoundingClientRect().top<=second.getBoundingClientRect().top,visible=node=>!node.hidden&&getComputedStyle(node).display!=='none'&&node.getBoundingClientRect().height>0;"
         "const initial={workspaceHidden:workspace.hidden,current:workflow.dataset.currentStep};transparent.click();const chosen={workspaceHidden:workspace.hidden,current:workflow.dataset.currentStep};next.click();const activity={current:workflow.dataset.currentStep,workspaceHidden:workspace.hidden,beforePanel:before(workspace,currentPanel())};document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T09:00:00Z,120';next.click();const localOption=zone.options[0].textContent,overlay={workspaceHidden:workspace.hidden,current:workflow.dataset.currentStep,panel:currentPanel()?.dataset.stepId||null,beforePanel:before(workspace,currentPanel()),parents:[zone.closest('label').parentElement.id,start.closest('label').parentElement.id,end.closest('label').parentElement.id],localOption,browserZone:Intl.DateTimeFormat().resolvedOptions().timeZone};"
         "start.value='2026-07-17T09:00:00';const keys=['ArrowLeft','ArrowRight','Home','End','PageUp','PageDown','f','F',' '],keyboard=keys.map(key=>{const event=new KeyboardEvent('keydown',{bubbles:true,cancelable:true,key,code:key===' '?'Space':''});start.dispatchEvent(event);return {key,prevented:event.defaultPrevented,value:start.value};});"
         "zone.value='UTC';input(zone);input(start);const incomplete={timelineHidden:timelineWrap.hidden,dockVisible:visible(dock)};end.value='2026-07-17T09:00:02';input(end);const ready={timelineHidden:timelineWrap.hidden,dockVisible:visible(dock),workspaceHidden:workspace.hidden};next.click();document.getElementById('timer-enabled').click();next.click();const timer={current:workflow.dataset.currentStep,workspaceHidden:workspace.hidden,parent:timerStart.closest('#timing-dock')?.id||null,fieldsVisible:visible(start)&&visible(timerStart)&&visible(timerEnd)};"
         "const identities={start,timerStart,camera};document.getElementById('video-fullscreen').click();const fullscreen={element:document.fullscreenElement?.id||null,dockInside:chrome.contains(dock),sameInputs:identities.start===document.getElementById('section-start-at')&&identities.timerStart===document.getElementById('timer-start-at')&&identities.camera===document.getElementById('camera-sync-at'),scrollable:getComputedStyle(document.getElementById('video-controls-dock')).overflowY,fieldsVisible:visible(start)&&visible(timerStart)};start.focus();document.exitFullscreen();const exited={focusRestored:document.activeElement===start,sameInput:identities.start===document.getElementById('section-start-at')};"
         "finished.click();const switched={current:workflow.dataset.currentStep,workspaceHidden:workspace.hidden};next.click();document.getElementById('source-video-file-id').value='drive-source';next.click();const sourceClock={current:workflow.dataset.currentStep,workspaceHidden:workspace.hidden,panel:currentPanel()?.dataset.stepId||null,beforePanel:before(workspace,currentPanel()),sameDock:dock===document.getElementById('timing-dock')};document.getElementById('telemetry-format').value='polar-csv';document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T09:00:00Z,120';next.click();document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();next.click();const matching={current:workflow.dataset.currentStep,workspaceHidden:workspace.hidden,parent:telemetrySync.closest('#timing-dock')?.id||null,fieldsVisible:visible(document.getElementById('manual-sync-marker'))&&visible(telemetrySync)};"
         "outcome={viewportWidth:innerWidth,initial,chosen,overlay,keyboard,incomplete,ready,activity,matching,timer,fullscreen,exited,switched,sourceClock,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<script>(function(){"
                              (str fixture "<script>(function(){"))
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-persistent-timing-dock-browser-"
     "Persistent timing dock regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- synchronization-mode-browser-outcome [page]
  (let [manual (assoc (fixture/render-request)
                      :sourceVideo {:fileId "drive-source"
                                    :recordingStartAt "2026-07-17T09:00:00Z"
                                    :timeZone "Europe/Warsaw"}
                      :outputFormat "h264-mp4"
                      :fitMode "letterbox"
                      :audioMode "source+heartbeat"
                      :synchronizationMode "manual-anchor")
        shared (-> manual
                   (assoc :synchronizationMode "shared-clock")
                   (dissoc :telemetrySyncAt :cameraSyncAt))
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),next=document.getElementById('wizard-next'),manualFields=document.getElementById('manual-synchronization-fields'),preview=document.getElementById('preview-button'),submit=document.getElementById('submit-button'),jsonStatus=document.getElementById('json-status'),workflow=document.getElementById('compose-workflow'),selected=()=>document.querySelector('input[name=\"synchronization-mode\"]:checked')?.value||null,generated=()=>JSON.parse(document.getElementById('render-request').value),snapshot=()=>({current:workflow.dataset.currentStep,selected:selected(),manualHidden:manualFields.hidden,previewDisabled:preview.disabled,submitDisabled:submit.disabled,request:generated()});"
         "const manualRequest=" (json/write-str manual) ",sharedRequest=" (json/write-str shared) ",without=(request,...keys)=>Object.fromEntries(Object.entries(request).filter(([key])=>!keys.includes(key))),validate=request=>{raw.value=JSON.stringify(request);apply.click();return jsonStatus.textContent;};"
         "const initial=snapshot();"
         "raw.value=JSON.stringify(manualRequest);apply.click();next.click();next.click();next.click();const manualChoice=snapshot();next.click();const manualStep=snapshot();"
         "raw.value=JSON.stringify(sharedRequest);apply.click();next.click();next.click();next.click();const sharedChoice=snapshot();next.click();const sharedStep=snapshot();"
         "const missing=validate(without(manualRequest,'synchronizationMode')),unknown=validate({...manualRequest,synchronizationMode:'automatic'}),validation={manualMissingTelemetry:validate(without(manualRequest,'telemetrySyncAt')),manualMissingCamera:validate(without(manualRequest,'cameraSyncAt')),manualMissingBoth:validate(without(manualRequest,'telemetrySyncAt','cameraSyncAt')),manualInvalidTelemetry:validate({...manualRequest,telemetrySyncAt:'invalid'}),manualInvalidCamera:validate({...manualRequest,cameraSyncAt:'invalid'}),manualBlankTelemetry:validate({...manualRequest,telemetrySyncAt:''}),manualNullCamera:validate({...manualRequest,cameraSyncAt:null}),sharedTelemetry:validate({...sharedRequest,telemetrySyncAt:manualRequest.telemetrySyncAt}),sharedCamera:validate({...sharedRequest,cameraSyncAt:manualRequest.cameraSyncAt}),sharedBoth:validate({...sharedRequest,telemetrySyncAt:manualRequest.telemetrySyncAt,cameraSyncAt:manualRequest.cameraSyncAt})};"
         "const labels=[...document.querySelectorAll('input[name=\"synchronization-mode\"]')].map(input=>input.labels[0].textContent.trim());"
         "outcome={initial,manualChoice,manualStep,sharedChoice,sharedStep,missing,unknown,validation,labels};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-synchronization-mode-browser-"
     "Synchronization mode form regression requires Chrome or Chromium"
     html)))

(defn- elapsed-manual-sync-browser-outcome [page window-size]
  (let [request (-> (fixture/render-request)
                    (assoc :synchronizationMode "shared-clock"
                           :displayTimeZone "UTC"
                           :sourceVideo
                           {:fileId "drive-source"
                            :recordingStartAt "2026-07-17T09:00:00.000Z"
                            :timeZone "UTC"}
                           :outputFormat "h264-mp4"
                           :fitMode "letterbox"
                           :audioMode "source-only")
                    (dissoc :telemetrySyncAt :cameraSyncAt))
        clock-request
        (fn [start end zone]
          (assoc request
                 :sectionStartAt start
                 :sectionEndAt end
                 :displayTimeZone zone
                 :sourceVideo
                 {:fileId "drive-source"
                  :recordingStartAt start
                  :timeZone zone}))
        gap-request
        (clock-request "2026-03-08T06:59:59.000Z"
                       "2026-03-08T07:00:01.000Z"
                       "America/New_York")
        repeat-request
        (clock-request "2026-11-01T05:59:59.000Z"
                       "2026-11-01T06:00:01.000Z"
                       "America/New_York")
        kathmandu-request
        (clock-request "2026-07-17T09:00:00.000Z"
                       "2026-07-17T09:00:02.000Z"
                       "Asia/Kathmandu")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const workflow=document.getElementById('compose-workflow'),raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),next=document.getElementById('wizard-next'),video=document.getElementById('source-video-player'),videoStage=document.getElementById('video-stage'),controlsDock=document.getElementById('video-controls-dock'),timeContext=document.getElementById('video-time-context'),timeContextVisual=document.getElementById('video-time-context-visual'),dateRow=document.getElementById('video-dates'),timeline=document.getElementById('video-timeline'),modeLabel=document.getElementById('timeline-mode-label'),modeStatus=document.getElementById('timeline-mode-status'),marker=document.getElementById('manual-sync-marker'),sourceElapsed=document.getElementById('manual-sync-source-seconds'),sourceElapsedText=document.getElementById('manual-sync-elapsed'),activity=document.getElementById('telemetry-sync-at'),camera=document.getElementById('camera-sync-at'),submit=document.getElementById('submit-button'),transportNode=document.getElementById('video-time'),visibleContext=()=>[...(timeContextVisual?.children||[])].filter(node=>!node.hidden).map(node=>node.textContent.trim()).join(' '),input=node=>node.dispatchEvent(new Event('input',{bubbles:true})),snapshot=()=>({current:workflow.dataset.currentStep,mode:modeLabel?.textContent||null,status:modeStatus?.textContent||null,context:{visual:timeContextVisual?visibleContext():null,accessible:timeContext?.getAttribute('aria-label')||null},dates:[...(dateRow?.children||[])].map(node=>node.textContent),transport:transportNode.textContent,transportAccessible:transportNode.getAttribute('aria-label'),ticks:[...document.getElementById('video-ticks').children].map(node=>node.textContent),timelineText:timeline.getAttribute('aria-valuetext'),marker:{hidden:marker.hidden,disabled:marker.disabled,value:marker.getAttribute('aria-valuenow'),text:marker.getAttribute('aria-valuetext')},sourceSeconds:sourceElapsed?.value??null,sourceText:sourceElapsedText?.textContent||null,videoCurrent:video.currentTime,cameraType:camera.type,clockPanelHidden:document.getElementById('video-clock-confirmation').hidden,submitDisabled:submit.disabled,browserOption:document.getElementById('timezone').options[0].textContent});"
         "raw.value=JSON.stringify(" (json/write-str request) ");apply.click();next.click();next.click();next.click();document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();next.click();const unavailable=snapshot();Object.defineProperty(video,'duration',{configurable:true,value:125.5});Object.defineProperty(video,'paused',{configurable:true,value:true});video.dispatchEvent(new Event('loadedmetadata'));const elapsed=snapshot();"
         "marker.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));const selected=snapshot();activity.value='2026-10-25T01:30:00';input(activity);const synced=snapshot(),generated=JSON.parse(document.getElementById('render-request').value);"
         "activity.value='';input(activity);const reversed=snapshot();sourceElapsed.value='';const pointerStartedEmpty=sourceElapsed.value===''&&camera.value==='';const rect=timeline.getBoundingClientRect(),clientX=rect.left+rect.width*.4;marker.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true,clientX,pointerId:14}));marker.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true,clientX,pointerId:14}));const pointer=snapshot();"
         "const clockSnapshot=()=>({context:{visual:visibleContext(),accessible:timeContext.getAttribute('aria-label')},dates:[...dateRow.children].map(node=>node.textContent),transport:transportNode.textContent,timelineText:timeline.getAttribute('aria-valuetext'),ticks:[...document.getElementById('video-ticks').children].map(node=>node.textContent)}),clockCase=request=>{raw.value=JSON.stringify(request);apply.click();Object.defineProperty(video,'duration',{configurable:true,value:2});video.__clockTime=0;Object.defineProperty(video,'currentTime',{configurable:true,get(){return this.__clockTime;},set(value){this.__clockTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}});video.currentTime=0;video.dispatchEvent(new Event('loadedmetadata'));const start=clockSnapshot();video.currentTime=1;const transition=clockSnapshot();video.currentTime=2;const end=clockSnapshot();return {start,transition,end};},gap=clockCase(" (json/write-str gap-request) "),repeat=clockCase(" (json/write-str repeat-request) "),kathmandu=clockCase(" (json/write-str kathmandu-request) ");"
         "outcome={viewportWidth:innerWidth,stepCount:document.querySelectorAll('#wizard-step-list li').length,unavailable,elapsed,selected,synced,reversed,pointerStartedEmpty,pointer,gap,repeat,kathmandu,generated:{mode:generated.synchronizationMode,telemetrySyncAt:generated.telemetrySyncAt,cameraSyncAt:generated.cameraSyncAt,sourceVideo:generated.sourceVideo,sectionStartAt:generated.sectionStartAt,sectionEndAt:generated.sectionEndAt},contextOrder:videoStage?.nextElementSibling===timeContext&&timeContext?.nextElementSibling===controlsDock,modeInsideContext:timeContext?.contains(modeLabel)&&timeContext?.contains(modeStatus),noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-elapsed-manual-sync-browser-"
     "Elapsed-first manual synchronization requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- no-source-timer-browser-outcome [page window-size]
  (let [base (-> (fixture/render-request)
                 (assoc :displayTimeZone "UTC"
                        :synchronizationMode "shared-clock")
                 (dissoc :telemetrySyncAt :cameraSyncAt))
        restored (assoc base :timer {:startAt "2026-07-17T09:00:00.400Z"
                                     :endAt "2026-07-17T09:00:01.600Z"})
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{const base=" (json/write-str base)
         ",restoredRequest=" (json/write-str restored)
         ",raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),toggle=document.getElementById('timer-enabled'),start=document.getElementById('timer-start-at'),end=document.getElementById('timer-end-at'),startMarker=document.getElementById('timer-start-marker'),endMarker=document.getElementById('timer-end-marker'),workspace=document.getElementById('video-player'),timelineWrap=document.getElementById('video-timeline-wrap'),stage=document.getElementById('video-stage'),transport=document.querySelector('.video-transport'),rangeStatus=document.getElementById('no-source-range-status'),timeline=document.getElementById('video-timeline'),syncMarker=document.getElementById('manual-sync-marker'),sectionStart=document.getElementById('section-start-at'),sectionEnd=document.getElementById('section-end-at'),generated=()=>JSON.parse(document.getElementById('render-request').value),input=node=>node.dispatchEvent(new Event('input',{bubbles:true})),timelineHidden=()=>timelineWrap.hidden;"
         "const initial={workspaceHidden:workspace.hidden,timelineHidden:timelineHidden(),sourceControlsHidden:document.getElementById('source-output-controls').hidden,summaryHidden:document.getElementById('no-source-output-summary').hidden};"
         "document.getElementById('timezone').value='UTC';input(document.getElementById('timezone'));sectionStart.value='2026-07-17T09:00:00';input(sectionStart);const incomplete={timelineHidden:timelineHidden(),status:rangeStatus.textContent};"
         "sectionEnd.value='2026-07-17T09:00:01.02';input(sectionEnd);const offFrame={timelineHidden:timelineHidden(),status:rangeStatus.textContent};"
         "sectionEnd.value='2026-07-17T09:00:02';input(sectionEnd);const valid={timelineHidden:timelineHidden(),stageHidden:stage.hidden,transportHidden:transport.hidden,label:timeline.getAttribute('aria-label'),valueText:timeline.getAttribute('aria-valuetext'),context:[...document.getElementById('video-time-context-visual').children].filter(node=>!node.hidden).map(node=>node.textContent).join(' '),contextAria:document.getElementById('video-time-context').getAttribute('aria-label'),dates:[...document.getElementById('video-dates').children].map(label=>label.textContent),tickValues:[...document.getElementById('video-ticks').children].map(tick=>tick.textContent),ticks:document.getElementById('video-ticks').children.length,status:rangeStatus.textContent};"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();document.getElementById('camera-sync-at').value='2026-07-17T09:00:01';input(document.getElementById('camera-sync-at'));document.getElementById('telemetry-sync-at').value='2026-07-17T10:00:01';input(document.getElementById('telemetry-sync-at'));const manual={hidden:syncMarker.hidden,disabled:syncMarker.disabled,value:syncMarker.getAttribute('aria-valuenow'),valueText:syncMarker.getAttribute('aria-valuetext')};"
         "raw.value=JSON.stringify(base);apply.click();const applied={enabled:toggle.checked,fields:[start.value,end.value],request:generated().timer||null,markers:[startMarker.hidden,endMarker.hidden],timelineHidden:timelineHidden(),sourceVideo:generated().sourceVideo||null,outputFormat:generated().outputFormat||null};"
         "toggle.click();const timelineRect=timeline.getBoundingClientRect(),markerRects=[startMarker.getBoundingClientRect(),endMarker.getBoundingClientRect()],enabled={enabled:toggle.checked,fields:[start.value,end.value],request:generated().timer||null,markers:[startMarker.hidden,endMarker.hidden],fieldsHidden:document.getElementById('timer-fields').hidden,markersInside:markerRects.every(rect=>rect.left>=timelineRect.left-.5&&rect.right<=timelineRect.right+.5&&rect.top>=timelineRect.top-.5&&rect.bottom<=timelineRect.bottom+.5)};"
         "toggle.click();const disabled={enabled:toggle.checked,request:generated().timer||null,markers:[startMarker.hidden,endMarker.hidden]};"
         "raw.value=JSON.stringify(restoredRequest);apply.click();const restored={enabled:toggle.checked,fields:[start.value,end.value],request:generated().timer||null,markers:[startMarker.hidden,startMarker.getAttribute('aria-valuenow'),endMarker.hidden,endMarker.getAttribute('aria-valuenow')],status:document.getElementById('json-status').textContent,timelineHidden:timelineHidden()};"
         "outcome={initial,incomplete,offFrame,valid,manual,applied,enabled,disabled,restored,viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-no-source-timer-browser-"
     "No-source timer form regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- early-access-browser-outcome [page window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "const form=document.querySelector('form[action=\"/v1/early-access/request\"]'),feedback=document.getElementById('early-access-feedback'),email=document.getElementById('early-access-email'),instagram=document.getElementById('early-access-instagram'),message=document.getElementById('early-access-message'),interactive=[...document.querySelectorAll('input:not([type=hidden]),textarea,button,a[href]')];"
         "const outcome={activeId:document.activeElement?.id||null,feedbackRole:feedback?.getAttribute('role')||null,formAction:form?.getAttribute('action')||null,emailReadOnly:email?.readOnly??null,emailLabel:email?.labels?.[0]?.getAttribute('for')||null,instagramLabel:instagram?.labels?.[0]?.getAttribute('for')||null,messageLabel:message?.labels?.[0]?.getAttribute('for')||null,keyboardReachable:interactive.every(node=>node.tabIndex>=0),keyboardOrder:interactive.map(node=>node.id||node.getAttribute('href')||node.tagName.toLowerCase()),mailto:document.querySelector('a[href=\"mailto:me@jamiep.org\"]')?.href||null,viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,formFits:!form||form.getBoundingClientRect().right<=window.innerWidth};"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (str/replace page "</body>" (str scenario "</body>"))]
    (browser-outcome
     "agg-early-access-browser-"
     "Early-access browser regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- compose-card-layout-browser-outcome [page window-size reveal-source?]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const shell=document.querySelector('.shell'),navigation=document.getElementById('wizard-navigation'),route=document.querySelector('input[name=\"wizard-outcome\"][value=\"" (if reveal-source? "finished-video" "transparent-overlay") "\"]'),next=document.getElementById('wizard-next');route.click();next.click();"
         "const rect=node=>{const value=node.getBoundingClientRect();return {left:value.left,right:value.right,width:value.width};},referenceRect=rect(navigation),panels=[...document.querySelectorAll('[data-wizard-panel]')].filter(panel=>!panel.hidden),panelRects=panels.map(panel=>({step:panel.dataset.stepId,heading:panel.querySelector('h2,summary')?.textContent||'',...rect(panel)})),aligned=value=>Math.abs(value.left-referenceRect.left)<=.5&&Math.abs(value.right-referenceRect.right)<=.5;"
         "outcome={viewportWidth:innerWidth,route:route.value,currentStep:document.getElementById('compose-workflow').dataset.currentStep,reference:referenceRect,panels:panelRects,aligned:panels.length===1&&panelRects.every(aligned),noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth,shellFits:shell.getBoundingClientRect().left>=-.5&&shell.getBoundingClientRect().right<=innerWidth+.5};"
         "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-compose-card-layout-browser-"
     "Compose card layout regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- theme-browser-outcome [page window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const parse=value=>{const parts=(value.match(/[\\d.]+/g)||[]).map(Number);return {r:parts[0]||0,g:parts[1]||0,b:parts[2]||0,a:parts.length>3?parts[3]:1};};"
         "const over=(top,bottom)=>{const a=top.a+bottom.a*(1-top.a);return a?{r:(top.r*top.a+bottom.r*bottom.a*(1-top.a))/a,g:(top.g*top.a+bottom.g*bottom.a*(1-top.a))/a,b:(top.b*top.a+bottom.b*bottom.a*(1-top.a))/a,a}:bottom;};"
         "const background=node=>{const chain=[];for(let current=node;current;current=current.parentElement)chain.unshift(parse(getComputedStyle(current).backgroundColor));return chain.reduce((result,color)=>over(color,result),{r:3,g:18,b:37,a:1});};"
         "const luminance=color=>{const channel=value=>{value/=255;return value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4);};return .2126*channel(color.r)+.7152*channel(color.g)+.0722*channel(color.b);};"
         "const ratio=(foreground,background)=>{const first=luminance(foreground),second=luminance(background);return (Math.max(first,second)+.05)/(Math.min(first,second)+.05);};"
         "const visible=node=>{const style=getComputedStyle(node),rect=node.getBoundingClientRect();return style.display!=='none'&&style.visibility!=='hidden'&&rect.width>0&&rect.height>0;};"
         "const contrastNodes=[...document.querySelectorAll('.brand,h1,h2,.eyebrow,.step,.muted,.hint,label,input,select,textarea,button,.button,.cta,a,summary,footer')].filter(visible);"
         "const contrasts=contrastNodes.map(node=>({tag:node.tagName,id:node.id||null,className:node.className||null,text:(node.textContent||node.value||'').trim().slice(0,60),ratio:ratio(parse(getComputedStyle(node).color),background(node))}));"
         "const contrastOffenders=contrasts.filter(entry=>entry.ratio<4.5);"
         "const layoutNodes=[...document.querySelectorAll('.shell,.product-header,.hero,.hero-copy,.hero-card,.feature-grid,.card,form,.field-grid,.actions,input,select,textarea,button,a')].filter(visible);"
         "const layoutOffenders=layoutNodes.map(node=>{const rect=node.getBoundingClientRect();return {tag:node.tagName,id:node.id||null,className:node.className||null,left:rect.left,right:rect.right};}).filter(rect=>rect.left<-.5||rect.right>window.innerWidth+.5);"
         "const focusTarget=document.querySelector('.cta,button.primary,button,a[href]');focusTarget?.focus({focusVisible:true});const focusStyle=focusTarget?getComputedStyle(focusTarget):null;"
         "const focusRule=[...document.styleSheets].flatMap(sheet=>[...sheet.cssRules]).find(rule=>rule.selectorText?.includes(':focus')&&parseFloat(rule.style.outlineWidth)>=3);"
         "const declaredFocus=[...document.querySelectorAll('style')].some(style=>style.textContent.includes(':focus,:focus-visible{outline:3px solid var(--color-warning)'));"
         "const contentSurface=document.querySelector('.hero-copy,.card,.faq-question'),surface=parse(getComputedStyle(contentSurface).backgroundColor),topHeader=document.querySelector('.shell>header'),headerSurface=parse(getComputedStyle(topHeader).backgroundColor),bodyStyle=getComputedStyle(document.body);"
         "const computedFocusVisible=!!focusTarget&&focusStyle.outlineStyle!=='none'&&parseFloat(focusStyle.outlineWidth)>=3;outcome={viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,layoutOffenders,contrastOffenders,minContrast:Math.min(...contrasts.map(entry=>entry.ratio)),focusVisible:!!focusTarget&&focusTarget.tabIndex>=0&&(computedFocusVisible||!!focusRule||declaredFocus),computedFocusVisible,focusActive:document.activeElement===focusTarget,focusMatches:focusTarget?.matches(':focus')||false,focusOutlineStyle:focusStyle?.outlineStyle||null,focusOutlineWidth:focusStyle?.outlineWidth||null,focusOutlineColor:focusStyle?.outlineColor||null,contentSurfaceAlpha:surface.a,headerSurfaceAlpha:headerSurface.a,backgroundIncludesAsset:bodyStyle.backgroundImage.includes('telemetry-background.webp'),backgroundAnimated:bodyStyle.animationName!=='none'};"
         "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-telemetry-theme-browser-"
     "Telemetry theme regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- product-header-browser-outcome [page window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const header=document.querySelector('.product-header'),links=[...header.querySelectorAll('a[href]')],active=header.querySelector('a[aria-current=\"page\"]'),activeStyle=active?getComputedStyle(active):null,headerRect=header.getBoundingClientRect();"
         "links[1].focus({focusVisible:true});const focusStyle=getComputedStyle(links[1]),declaredFocus=[...document.querySelectorAll('style')].some(style=>style.textContent.includes(':focus,:focus-visible{outline:3px solid var(--color-warning)'));"
         "outcome={keyboardOrder:links.map(link=>link.getAttribute('href')),allVisible:links.every(link=>{const rect=link.getBoundingClientRect(),style=getComputedStyle(link);return rect.width>0&&rect.height>0&&style.display!=='none'&&style.visibility!=='hidden';}),activeNav:active?.getAttribute('href')||null,activeNavStyled:!active||parseInt(activeStyle.fontWeight,10)>=700&&activeStyle.textDecorationLine.includes('underline'),forcedColorSupport:[...document.querySelectorAll('style')].some(style=>style.textContent.includes('@media(forced-colors:active)')&&style.textContent.includes('a[aria-current=\"page\"]')),focusVisible:focusStyle.outlineStyle!=='none'&&parseFloat(focusStyle.outlineWidth)>=3||declaredFocus,viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,headerFits:headerRect.left>=-.5&&headerRect.right<=window.innerWidth+.5};"
         "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-product-header-browser-"
     "Product header regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- contextual-help-browser-outcome [page window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{"
         "const links=[...document.querySelectorAll('.contextual-help')],styles=[...document.querySelectorAll('style')].map(node=>node.textContent).join(''),declaredFocus=styles.includes(':focus,:focus-visible{outline:3px solid var(--color-warning)'),expose=link=>{const panel=link.closest('[data-wizard-panel]'),standalone=link.closest('#video-clock-confirmation');if(panel){document.querySelectorAll('[data-wizard-panel]').forEach(candidate=>{candidate.hidden=true;});for(let node=link;node&&node!==panel.parentElement;node=node.parentElement)node.hidden=false;}else if(standalone)standalone.hidden=false;};"
         "const presentations=links.map(link=>{expose(link);link.focus({focusVisible:true});const rect=link.getBoundingClientRect(),style=getComputedStyle(link),wrapper=link.closest('.help-heading,.help-label,.toggle-help'),wrapperRect=wrapper?.getBoundingClientRect(),associated=wrapper?.querySelector(':scope>h1,:scope>h2,:scope>h3,:scope>label,:scope>strong,:scope>.toggle'),associatedRect=associated?.getBoundingClientRect(),mark=link.querySelector('.contextual-help-mark'),markRect=mark?.getBoundingClientRect(),centerDelta=markRect&&associatedRect?Math.abs((markRect.top+markRect.bottom-associatedRect.top-associatedRect.bottom)/2):null,computedFocus=style.outlineStyle!=='none'&&parseFloat(style.outlineWidth)>=3,overlapsSibling=wrapper?[...wrapper.children].some(node=>{if(node===link)return false;const siblingRect=node.getBoundingClientRect();return rect.left<siblingRect.right&&rect.right>siblingRect.left&&rect.top<siblingRect.bottom&&rect.bottom>siblingRect.top;}):true;return {href:link.getAttribute('href'),name:link.getAttribute('aria-label'),target:link.getAttribute('target'),text:link.textContent.trim(),symbolHidden:link.querySelector('[aria-hidden=\"true\"]')?.textContent==='?',width:rect.width,height:rect.height,markWidth:markRect?.width??null,markHeight:markRect?.height??null,associatedWidth:associatedRect?.width??null,associatedHeight:associatedRect?.height??null,wrapperWidth:wrapperRect?.width??null,wrapperContained:!!wrapper&&wrapper.scrollWidth<=wrapper.clientWidth+.5,associatedFontSize:associated?parseFloat(getComputedStyle(associated).fontSize):null,centerDelta,aligned:centerDelta!==null&&centerDelta<=1,fits:rect.left>=-.5&&rect.right<=window.innerWidth+.5,visible:style.display!=='none'&&style.visibility!=='hidden',keyboardReachable:link.tabIndex>=0,focusVisible:computedFocus||declaredFocus,associated:!!associated,overlapsSibling,insideLabel:!!link.closest('label')};});"
         "outcome={presentations,viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,hoverStyled:styles.includes('.contextual-help:hover .contextual-help-mark{background:var(--color-accent);border-color:var(--color-accent)}')};"
         "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-contextual-help-browser-"
     "Contextual help regression requires Chrome or Chromium"
     html
     (str "--window-size=" window-size))))

(defn- faq-browser-outcome [page initial-fragment window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "const runFaqFixture=()=>{const initialFragment='" initial-fragment "',changedFragment='source-and-activity-data-retention',snapshot=id=>{const target=document.getElementById(id),rect=target?.getBoundingClientRect();return {id,hash:location.hash,open:target?.open??null,inView:!!rect&&rect.bottom>0&&rect.top<window.innerHeight,top:rect?.top??null};},record=outcome=>{const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));},navigate=fragment=>{const oldURL=location.href;history.pushState(null,'','#'+fragment);window.dispatchEvent(new HashChangeEvent('hashchange',{oldURL,newURL:location.href}));};"
         "try{const initial=snapshot(initialFragment);navigate(changedFragment);const changed=snapshot(changedFragment),initialStillOpen=document.getElementById(initialFragment).open,summaryNodes=[...document.querySelectorAll('.faq-question>summary')],details=[...document.querySelectorAll('.faq-question')],permalinks=[...document.querySelectorAll('.faq-permalink a')],activeNavNode=document.querySelector('nav a[aria-current=\"page\"]'),activeNavStyle=activeNavNode?getComputedStyle(activeNavNode):null,base={initial,changed,initialStillOpen,summaryCount:summaryNodes.length,summariesKeyboardReachable:summaryNodes.every(node=>node.tabIndex>=0&&node.textContent.trim()),permalinksAccessible:permalinks.length===summaryNodes.length&&permalinks.every(link=>link.getAttribute('aria-label')?.includes(link.closest('details').querySelector('summary').textContent.trim())&&link.getAttribute('href')==='#'+link.closest('details').id),nestedDetails:document.querySelectorAll('details details').length,activeNav:activeNavNode?.getAttribute('href')||null,activeNavStyled:!!activeNavStyle&&parseInt(activeNavStyle.fontWeight,10)>=700&&activeNavStyle.textDecorationLine.includes('underline'),viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,detailsFit:details.every(node=>{const rect=node.getBoundingClientRect();return rect.left>=-.5&&rect.right<=window.innerWidth+.5;})};navigate(initialFragment);record({...base,back:snapshot(initialFragment),changedStillOpen:document.getElementById(changedFragment).open});}catch(error){record({error:error.message,stack:error.stack});}};"
         "runFaqFixture();"
         "</script>")
        html (str/replace page "</body>" (str scenario "</body>"))
        temp (File/createTempFile "agg-faq-browser-" ".html")]
    (try
      (spit temp html)
      (browser-location-outcome
       "FAQ fragment and responsive behavior requires Chrome or Chromium"
       (str (.toURI temp) "#" initial-fragment)
       5000
       30000
       [(str "--window-size=" window-size)])
      (finally
        (.delete temp)))))

(defn- faq-deep-link-browser-outcome [page initial-fragment window-size]
  (let [scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "const runFaqDeepLinkFixture=()=>{const fragment='" initial-fragment "',snapshot=()=>{const target=document.getElementById(fragment),rect=target?.getBoundingClientRect();return {hash:location.hash,open:target?.open??null,inView:!!rect&&rect.bottom>0&&rect.top<window.innerHeight,top:rect?.top??null};},record=outcome=>{const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));};"
         "try{const details=[...document.querySelectorAll('.faq-question')];record({initial:snapshot(),viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,detailsFit:details.every(node=>{const rect=node.getBoundingClientRect();return rect.left>=-.5&&rect.right<=window.innerWidth+.5;})});}catch(error){record({error:error.message,stack:error.stack});}};"
         "runFaqDeepLinkFixture();"
         "</script>")
        html (str/replace page "</body>" (str scenario "</body>"))
        temp (File/createTempFile "agg-faq-deep-link-browser-" ".html")]
    (try
      (spit temp html)
      (browser-location-outcome
       "FAQ deep-link behavior requires Chrome or Chromium"
       (str (.toURI temp) "#" initial-fragment)
       5000
       30000
       [(str "--window-size=" window-size)])
      (finally
        (.delete temp)))))

(defn- contextual-help-dialog-browser-outcome
  [page window-size exercise-history?]
  (let [fixture
        (str
         "<script>"
         "window.__helpMedia={playCalls:0,pauseCalls:0};"
         "Object.defineProperties(HTMLMediaElement.prototype,{"
         "currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);}},"
         "paused:{configurable:true,get(){return this.__paused!==false;}}});"
         "HTMLMediaElement.prototype.play=function(){this.__paused=false;window.__helpMedia.playCalls++;this.dispatchEvent(new Event('play'));return Promise.resolve();};"
         "HTMLMediaElement.prototype.pause=function(){this.__paused=true;window.__helpMedia.pauseCalls++;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "document.addEventListener('DOMContentLoaded',async()=>{let outcome;try{"
         "document.getElementById('source-output-controls').hidden=false;document.getElementById('video-clock-confirmation').hidden=false;const dialog=document.getElementById('contextual-help-dialog'),title=document.getElementById('contextual-help-title'),answer=document.getElementById('contextual-help-answer'),full=document.getElementById('contextual-help-full'),close=document.querySelector('.contextual-help-close'),links=[...document.querySelectorAll('.contextual-help')],video=document.getElementById('source-video-player'),baseUrl=location.href,expose=link=>{const panel=link.closest('[data-wizard-panel]'),standalone=link.closest('#video-clock-confirmation');if(panel){document.querySelectorAll('[data-wizard-panel]').forEach(candidate=>{candidate.hidden=true;});for(let node=link;node&&node!==panel.parentElement;node=node.parentElement)node.hidden=false;}else if(standalone)standalone.hidden=false;};"
         "if(!dialog||!title||!answer||!full||!close)throw new Error('Contextual help dialog is unavailable');"
         "const tick=()=>new Promise(resolve=>setTimeout(resolve,0)),waitPop=(action,expectedFragment)=>new Promise(resolve=>{const onPop=event=>{if((event.state?.contextualHelp||null)!==expectedFragment)return;window.removeEventListener('popstate',onPop);setTimeout(()=>resolve(true),0);};window.addEventListener('popstate',onPop);action();}),safeClick=link=>{link.addEventListener('click',event=>event.preventDefault(),{once:true});link.click();if(!dialog.open)throw new Error('Contextual help link did not open the dialog');};"
         "const fragment=link=>new URL(link.href).hash.slice(1),templateFor=link=>[...dialog.querySelectorAll('template[data-contextual-help-fragment]')].find(template=>template.dataset.contextualHelpFragment===fragment(link));"
         "const inspect=link=>{const template=templateFor(link),rect=dialog.getBoundingClientRect();return {fragment:fragment(link),open:dialog.open,modal:dialog.matches(':modal'),focusContained:dialog.contains(document.activeElement),title:title.textContent,expectedTitle:template?.dataset.contextualHelpQuestion||null,answer:answer.innerHTML,expectedAnswer:template?.content.firstElementChild?.innerHTML||null,fullHref:full.getAttribute('href'),fullTarget:full.getAttribute('target'),fullRel:full.getAttribute('rel'),urlUnchanged:location.href===baseUrl,historyFragment:history.state?.contextualHelp||null,fits:rect.left>=-.5&&rect.right<=innerWidth+.5&&rect.top>=-.5&&rect.bottom<=innerHeight+.5,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};};"
         "document.querySelector('input[name=\"synchronization-mode\"][value=\"manual-anchor\"]').click();"
         "const telemetryContent='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120\\n2026-07-17T10:00:02Z,124',telemetryFile=document.getElementById('telemetry-file'),transfer=new DataTransfer(),selectedFile=new File([telemetryContent],'activity.csv',{type:'text/csv'});transfer.items.add(selectedFile);telemetryFile.files=transfer.files;document.getElementById('source-video-file-id').value='private-drive-video';document.getElementById('picker-selection').textContent='training.mp4';document.getElementById('output-format').value='prores-422-mov';document.getElementById('fit-mode').value='crop';document.getElementById('audio-mode').value='source-only';document.getElementById('preset').value='2.7k25';document.getElementById('timezone').value='UTC';document.getElementById('future-trace-opacity-percent').value='37';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:02'],['timer-start-at','2026-07-17T10:00:00'],['timer-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);document.getElementById('spo2-enabled').checked=true;document.getElementById('spo2-telemetry').value='reading_time,spo2\\n2026-07-17T10:00:00Z,97';document.getElementById('timer-enabled').checked=true;telemetryFile.dispatchEvent(new Event('change',{bubbles:true}));await new Promise((resolve,reject)=>{const deadline=Date.now()+1000,check=()=>{if(document.getElementById('telemetry-status').classList.contains('success'))resolve();else if(Date.now()>deadline)reject(new Error('Telemetry file was not loaded'));else setTimeout(check,5);};check();});document.getElementById('video-recording-start').value='2026-07-17T10:00:00';document.getElementById('video-timezone').value='UTC';document.getElementById('confirm-video-clock').click();"
         "const previewResult=document.getElementById('preview-result'),jobResult=document.getElementById('job-result'),renderForm=document.getElementById('render-form'),raw=document.getElementById('raw-json');previewResult.innerHTML='<article class=\"preview-gallery\" data-preview-operation=\"preview-live\"><h2>Preview ready</h2></article>';jobResult.innerHTML='<article class=\"job\" id=\"job-live\" data-job-state=\"running\"><h2>Creating finished video</h2></article>';document.getElementById('video-player').hidden=false;video.setAttribute('src','/v1/drive/playback/live-compose-state');video.currentTime=42.25;"
         "const snapshotState=async()=>({drive:{fileId:document.getElementById('source-video-file-id').value,selection:document.getElementById('picker-selection').textContent,playerSrc:video.getAttribute('src'),playhead:video.currentTime},form:{outputFormat:document.getElementById('output-format').value,fitMode:document.getElementById('fit-mode').value,audioMode:document.getElementById('audio-mode').value,preset:document.getElementById('preset').value,timeZone:document.getElementById('timezone').value,opacity:document.getElementById('future-trace-opacity-percent').value,spo2Enabled:document.getElementById('spo2-enabled').checked,timerEnabled:document.getElementById('timer-enabled').checked},file:{count:telemetryFile.files.length,name:telemetryFile.files[0]?.name||null,text:telemetryFile.files[0]?await telemetryFile.files[0].text():null,loadedValue:document.getElementById('telemetry').value},raw:raw.value,hidden:document.getElementById('render-request').value,preview:previewResult.innerHTML,job:jobResult.innerHTML});const stateBefore=await snapshotState(),liveNodes={form:renderForm,file:telemetryFile,preview:previewResult,job:jobResult};"
         "const exerciseHistory=" exercise-history? ";await video.play();const first=links[0];expose(first);first.focus();safeClick(first);await tick();const opened=inspect(first),pausedOnOpen={paused:video.paused,currentTime:video.currentTime,playCalls:window.__helpMedia.playCalls,pauseCalls:window.__helpMedia.pauseCalls},presentations=[opened];let back=null,forward=null,closed=null;"
         "if(exerciseHistory){const backPopped=await waitPop(()=>history.back(),null);back={popped:backPopped,open:dialog.open,focusReturned:document.activeElement===first,currentTime:video.currentTime,paused:video.paused};const forwardPopped=await waitPop(()=>history.forward(),fragment(first));forward={popped:forwardPopped,...inspect(first)};const closePopped=await waitPop(()=>close.click(),null);closed={popped:closePopped,open:dialog.open,focusReturned:document.activeElement===first,currentTime:video.currentTime,paused:video.paused};}else{history.replaceState(null,'',location.href);dialog.close();opened.closed=!dialog.open&&document.activeElement===first;}"
         "for(const link of links.slice(1)){expose(link);link.focus();safeClick(link);await tick();const presentation=inspect(link);presentations.push(presentation);if(exerciseHistory&&link===links[1]){const popPromise=waitPop(()=>{},null),cancelEvent=new Event('cancel',{cancelable:true}),cancelPrevented=!dialog.dispatchEvent(cancelEvent),popped=await popPromise;presentation.escape={cancelPrevented,popped,open:dialog.open,focusReturned:document.activeElement===link};}else{history.replaceState(null,'',location.href);dialog.close();presentation.closePopped=true;presentation.closed=!dialog.open&&document.activeElement===link;}}"
         "document.getElementById('copy-json').click();await tick();const stateAfter=await snapshotState(),statePreserved=JSON.stringify(stateBefore)===JSON.stringify(stateAfter)&&liveNodes.form===document.getElementById('render-form')&&liveNodes.file===document.getElementById('telemetry-file')&&liveNodes.preview===document.getElementById('preview-result')&&liveNodes.job===document.getElementById('job-result')&&selectedFile===document.getElementById('telemetry-file').files[0];outcome={exerciseHistory,linkCount:links.length,presentations,pausedOnOpen,back,forward,closed,stateBefore,stateAfter,statePreserved,finalPlayback:{paused:video.paused,currentTime:video.currentTime,playCalls:window.__helpMedia.playCalls},viewportWidth:innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=innerWidth};"
         "}catch(error){outcome={error:error.message,stack:error.stack};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));},{once:true});"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "<script>(function(){"
                              (str fixture "<script>(function(){"))
                 (str/replace "</body>" (str scenario "</body>")))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        port (.getPort (.getAddress server))]
    (.createContext
     server "/"
     (reify HttpHandler
       (handle [_ exchange]
         (if (= "/" (.getPath (.getRequestURI exchange)))
           (respond-browser-fixture!
            exchange 200 "text/html; charset=utf-8" html nil)
           (respond-browser-fixture!
            exchange 404 "text/plain; charset=utf-8" "not found" nil)))))
    (.setExecutor server nil)
    (.start server)
    (try
      (browser-location-outcome
       "Contextual help behavior requires Chrome or Chromium"
       (str "http://127.0.0.1:" port "/")
       2500
       [(str "--window-size=" window-size)])
      (finally
        (.stop server 0)))))

(deftest signed-in-compose-page-keeps-product-navigation-and-account-controls
  (let [page (ui/page {:user {:email "owner@example.com" :role :owner}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? true})
        faq-position (str/index-of page "href=\"/faq\"")
        privacy-position (str/index-of page "href=\"/privacy\"")
        terms-position (str/index-of page "href=\"/terms\"")
        product-header (second
                        (re-find
                         #"(?s)(<header class=\"product-header\">.*?</header>)"
                         page))]
    (is (= 1 (count (re-seq #"class=\"product-header\"" page))))
    (is (str/includes? page
                       "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
    (is (str/includes? page "<nav aria-label=\"Product\">"))
    (is (every? some? [faq-position privacy-position terms-position]))
    (when (every? some? [faq-position privacy-position terms-position])
      (is (< faq-position privacy-position terms-position)))
    (is (= 1 (count (re-seq #"href=\"/faq\"" product-header))))
    (is (= 1 (count (re-seq #"href=\"/privacy\"" page))))
    (is (= 1 (count (re-seq #"href=\"/terms\"" page))))
    (is (not (re-find #"<a[^>]+aria-current=\"page\"" page)))
    (is (str/includes? page "Signed in as owner@example.com"))
    (is (str/includes? page
                       "<form method=\"post\" action=\"/v1/auth/logout\">"))))

(deftest operational-logs-page-keeps-product-navigation-and-account-controls
  (let [page (ui/logs-page {:user {:email "owner@example.com"}
                            :csrf "csrf-test"
                            :logs []
                            :view "formatted"})
        faq-position (str/index-of page "href=\"/faq\"")
        privacy-position (str/index-of page "href=\"/privacy\"")
        terms-position (str/index-of page "href=\"/terms\"")]
    (is (= 1 (count (re-seq #"class=\"product-header\"" page))))
    (is (str/includes? page
                       "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
    (is (every? some? [faq-position privacy-position terms-position]))
    (when (every? some? [faq-position privacy-position terms-position])
      (is (< faq-position privacy-position terms-position)))
    (is (= 1 (count (re-seq #"href=\"/faq\"" page))))
    (is (= 1 (count (re-seq #"href=\"/privacy\"" page))))
    (is (= 1 (count (re-seq #"href=\"/terms\"" page))))
    (is (str/includes? page "Signed in as owner@example.com"))
    (is (str/includes? page
                       "<form method=\"post\" action=\"/v1/auth/logout\">"))
    (is (str/includes? page
                       "<input type=\"hidden\" name=\"csrf\" value=\"csrf-test\">"))))

(deftest token-and-member-admin-pages-keep-product-navigation-and-htmx-account-controls
  (doseq [[label page heading action]
          [["token"
            (ui/token-page {:user {:email "owner@example.com"}
                            :csrf "csrf-test"
                            :tokens []})
            "Personal API tokens"
            "hx-post=\"/ui/tokens\""]
           ["member admin"
            (ui/member-admin-page {:user {:email "owner@example.com"}
                                   :csrf "csrf-test"
                                   :members []})
            "Member admin"
            "hx-post=\"/ui/admin/members\""]]]
    (testing label
      (is (= 1 (count (re-seq #"class=\"product-header\"" page))))
      (is (str/includes? page heading))
      (is (str/includes? page "Signed in as owner@example.com"))
      (is (str/includes? page "<form method=\"post\" action=\"/v1/auth/logout\">"))
      (is (str/includes? page "htmx.org@2.0.10"))
      (is (str/includes? page "hx-headers=\"{&quot;X-CSRF-Token&quot;:&quot;csrf-test&quot;}\""))
      (is (str/includes? page action)))))

(deftest public-pages-use-one-product-navigation-with-the-current-page-marked
  (let [pages
        {"anonymous home" [ui/anonymous-page nil]
         "FAQ" [ui/faq-page "/faq"]
         "Privacy" [ui/privacy-page "/privacy"]
         "Terms" [ui/terms-page "/terms"]
         "Drive recovery" [ui/drive-recovery-page nil]
         "early access" [(ui/early-access-page
                          {:email "verified@example.com"
                           :proof "signed-proof"})
                         nil]}]
    (doseq [[surface [page active-path]] pages]
      (testing surface
        (let [header (second
                      (re-find
                       #"(?s)(<header class=\"product-header\">.*?</header>)"
                       page))
              positions (mapv #(some-> header (str/index-of %))
                              ["href=\"/faq\""
                               "href=\"/privacy\""
                               "href=\"/terms\""])
              active-link (some->> header
                                   (re-find
                                    #"<a href=\"([^\"]+)\" aria-current=\"page\">")
                                   second)]
          (is (string? header))
          (when header
            (is (str/includes? header
                               "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
            (is (str/includes? header "<nav aria-label=\"Product\">"))
            (is (every? some? positions))
            (when (every? some? positions)
              (is (apply < positions)))
            (is (= active-path active-link))))))))

(deftest partial-html-responses-do-not-receive-product-navigation
  (doseq [[surface fragment]
          {"token panel" (ui/token-panel [])
           "member panel" (ui/member-panel [])
           "Drive recovery fragment" ui/drive-recovery-fragment
           "preview fragment" (ui/preview-stale-fragment "generation-1")}]
    (testing surface
      (is (not (str/includes? fragment
                              "<header class=\"product-header\">"))))))

(deftest public-product-and-legal-pages-identify-alpha-compose
  (let [port (available-port)
        {:keys [auth-system]} (fixture)
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [homepage (request! port :get "/" nil {})
            privacy (request! port :get "/privacy" nil {})
            terms (request! port :get "/terms" nil {})]
        (is (= 200 (.statusCode homepage)))
        (is (str/includes? (.body homepage) "Alpha Compose"))
        (is (str/includes? (.body homepage) "class=\"shell\""))
        (is (str/includes? (.body homepage) "class=\"hero\""))
        (is (str/includes? (.body homepage) "class=\"hero-card\""))
        (is (str/includes? (.body homepage) "class=\"feature-grid\""))
        (is (str/includes? (.body homepage) "class=\"card trust-card\""))
        (is (str/includes? (.body homepage) "Continue with Google"))
        (is (str/includes? (.body homepage) "href=\"/privacy\""))
        (is (str/includes? (.body homepage) "href=\"/terms\""))
        (doseq [copy ["Workout data, built into your video"
                      "Turn your activity into a video worth sharing."
                      "No video editing required."
                      "Your video, finished for you."
                      "Choose your video and data"
                      "Make it yours"
                      "Get the finished video"
                      "Your Google Drive stays under your control."
                      "only use files you choose"
                      "cannot access the rest of your Google Drive"]]
          (is (str/includes? (.body homepage) copy) copy))
        (doseq [old-term ["drive.file" "composition" "render"
                          "transparent overlay" "your editor"]]
          (is (not (str/includes? (str/lower-case (.body homepage)) old-term))
              old-term))
        (is (= 200 (.statusCode privacy)))
        (is (str/includes? (.body privacy)
                           "<a href=\"/privacy\" aria-current=\"page\">Privacy</a>"))
        (is (str/includes? (.body privacy) "Privacy policy"))
        (is (str/includes? (.body privacy) "Google Drive"))
        (is (str/includes? (.body privacy) "Google API Services User Data Policy"))
        (is (str/includes? (.body privacy) "Limited Use"))
        (is (str/includes? (.body privacy) "Project JSON"))
        (is (str/includes? (.body privacy)
                           "Alpha Compose does not automatically persist Project JSON"))
        (is (str/includes? (.body privacy)
                           "Credentials, CSRF values, signed URLs, recording-clock candidates, preview images, playback state, and job results are excluded from Project JSON"))
        (is (str/includes? (.body privacy) "early-access request"))
        (is (str/includes? (.body privacy) "Instagram handle"))
        (is (str/includes? (.body privacy) "optional message"))
        (is (str/includes? (.body privacy) "Resend"))
        (is (str/includes? (.body privacy)
                           "does not retain early-access requests"))
        (is (str/includes? (.body privacy) "contact or deletion request"))
        (is (= 200 (.statusCode terms)))
        (is (str/includes? (.body terms)
                           "<a href=\"/terms\" aria-current=\"page\">Terms</a>"))
        (is (str/includes? (.body terms) "Terms of service"))
        (is (str/includes? (.body terms) "me@jamiep.org")))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest consumer-copy-uses-activity-data-without-renaming-the-json-contract
  (let [compose (ui/page {:user {:email "owner@example.com" :role :member}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? false})
        heart-rate-error
        (ui/preview-failure-fragment
         {:failureCode "unsupported_telemetry_columns"
          :field "telemetry"
          :documentationPath
          "/openapi.yaml#/components/schemas/RenderRequest"})
        oxygen-saturation-error
        (ui/preview-failure-fragment
         {:failureCode "malformed_telemetry_row"
          :field "spo2.telemetry"})
        expected-compose-copy
        ["Activity data for video"
         "Create your video"
         "Choose your heart-rate file"
         "Upload a FIT or CSV file"
         "detects Garmin FIT or Polar CSV automatically"
         "Advanced activity data"
         "OxiWear heart-rate CSV"
         "Heart-rate data format"
         "Heart-rate file"
         "Yes - the camera and activity device clocks matched"
         "No - the camera and activity device clocks were different"
         "Selected source-video frame"
         "Activity-data time at the selected frame"
         "Move the orange marker on the full-source timeline"
         "Enter the same recognizable instant from the uploaded activity data"
         "Preview"
         "Create finished video"
         "Alpha Compose calls these inputs activity data"
         "API contract uses the exact field names below"]
        retired-compose-copy
        ["Telemetry overlays for video"
         "Compose your overlay"
         "Select a supported telemetry format"
         "Telemetry file"
         "Or paste telemetry content"
         "Or paste heart-rate data"
         "Telemetry sync time"
         "Heart-rate sync time"
         "Telemetry timestamps"
         "Preview overlay"]
        technical-identifiers
        ["<code>telemetryFormat</code>"
         "<code>telemetry</code>"
         "<code>synchronizationMode</code>"
         "<code>telemetrySyncAt</code>"
         "Request.telemetryFormat"
         "Request.telemetry"
         "Request.spo2.telemetry"]]
    (doseq [copy expected-compose-copy]
      (is (str/includes? compose copy) copy))
    (doseq [copy retired-compose-copy]
      (is (not (str/includes? compose copy)) copy))
    (doseq [identifier technical-identifiers]
      (is (str/includes? compose identifier) identifier))
    (is (str/includes? heart-rate-error "Heart-rate data"))
    (is (str/includes? heart-rate-error
                       "selected heart-rate data format"))
    (is (str/includes? oxygen-saturation-error
                       "Oxygen-saturation data (SpO2)"))
    (doseq [copy ["Heart-rate telemetry"
                  "SpO2 telemetry"
                  "telemetry input"]]
      (is (not (str/includes?
                (str/lower-case
                 (str heart-rate-error oxygen-saturation-error))
                (str/lower-case copy)))
          copy))
    (doseq [[page expected retired]
            [[ui/privacy-page
              ["process activity data"
               "use activity data for advertising"
               "logs exclude activity-data values"]
              ["process telemetry"
               "use telemetry for advertising"
               "logs exclude telemetry values"]]
             [ui/terms-page
              ["content and activity data"
               "Alpha Compose outputs are not medical advice"]
              ["content and telemetry"
               "Telemetry overlays are not medical advice"]]]]
      (doseq [copy expected]
        (is (str/includes? page copy) copy))
      (doseq [copy retired]
        (is (not (str/includes? page copy)) copy)))))

(deftest public-faq-provides-stable-product-guidance-without-authentication
  (let [port (available-port)
        {:keys [auth-system]} (fixture)
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [homepage (request! port :get "/" nil {})
            response (request! port :get "/faq" nil {})
            page (.body response)
            categories ["What Alpha Compose makes"
                        "Heart rate and the heartbeat sound"
                        "Supported activity data"
                        "Files and synchronization"
                        "Google Drive and privacy"
                        "Safety and medical limitations"]
            questions [["what-alpha-compose-does" "What does Alpha Compose do?"]
                       ["beyond-freediving" "Is Alpha Compose only for freediving?"]
                       ["progress-over-time" "Can Alpha Compose compare my progress over time?"]
                       ["preview-admission-cost" "Why does Preview have an admission cost?"]
                       ["why-show-heart-rate" "Why put heart rate on a workout video?"]
                       ["generated-heartbeat-sound" "Is the heartbeat sound a recording of my heart?"]
                       ["audio-options" "Can I keep the source audio, use only the heartbeat, or combine them?"]
                       ["supported-activity-data" "What activity data is supported today?"]
                       ["compatible-activity-export" "How do I export a compatible activity file?"]
                       ["oxygen-saturation-support" "Does Alpha Compose support oxygen saturation?"]
                       ["future-graphs" "Will other graphs be supported?"]
                       ["file-sources" "Where are my video and activity-data files read from?"]
                       ["video-timezone" "Which timezone should I choose for my video?"]
                       ["which-devices-should-i-synchronize" "Which devices should I synchronize?"]
                       ["how-should-i-synchronize-devices-before-recording" "How should I synchronize devices before recording?"]
                       ["when-do-i-need-a-matching-moment" "When do I need a matching moment on the timeline?"]
                       ["why-can-camera-timecode-differ-from-clock-time" "Why can camera timecode differ from clock time?"]
                       ["synchronizing-data-and-camera" "Why do I need to synchronize the activity data and camera time?"]
                       ["output-file" "What output does Alpha Compose create?"]
                       ["transparent-overlay-editors" "Which editor should I use for a transparent overlay?"]
                       ["completed-output-playback" "Which finished outputs can I play in the browser?"]
                       ["project-json" "What is Project JSON and when does Alpha Compose save it?"]
                       ["ai-assisted-preparation" "Can AI help me prepare Project JSON, FFmpeg commands, or editor steps?"]
                       ["google-drive-access" "What can Alpha Compose access in Google Drive?"]
                       ["finished-video-location" "Where is my finished video saved?"]
                       ["source-and-activity-data-retention" "Does Alpha Compose retain my source video or log my activity data?"]
                       ["medical-or-training-advice" "Is Alpha Compose medical or training advice?"]
                       ["displayed-value-accuracy" "How accurate are the displayed values?"]]]
        (is (= 200 (.statusCode response)))
        (is (str/includes? page "<title>FAQ · Alpha Compose</title>"))
        (is (str/includes? page "<h1>Frequently asked questions</h1>"))
        (doseq [category categories]
          (is (str/includes? page category) category))
        (is (= (count questions)
               (count (re-seq #"<details class=\"faq-question\"" page))))
        (doseq [[fragment question] questions]
          (is (str/includes? page
                             (str "<details class=\"faq-question\" id=\""
                                  fragment "\""))
              fragment)
          (is (str/includes? page (str "<summary><h3>" question "</h3></summary>"))
              question))
        (doseq [claim ["grew from freediving"
                       "not limited to freediving"
                       "does not store sessions, analyze trends, or compare activities"
                       "generated from your recorded heart-rate data"
                       "not a recording of your heart"
                       "Polar CSV"
                       "Garmin FIT"
                       "OxiWear heart-rate CSV"
                       "Garmin Connect, open the activity and choose Export Original"
                       "Polar Flow, open one session and export a CSV file"
                       "Strava's Export Original can work when the original uploaded file still contains compatible heart-rate data"
                       "Apple Health exports all data as XML"
                       "Health Connect backups export a Health Connect.zip archive"
                       "not supported activity-file inputs for Alpha Compose today"
                       "optional OxiWear SpO2"
                       "embedded recording timestamp may contain only a UTC offset"
                       "Alpha Compose never treats the Google Drive upload time as the recording time"
                       "Garmin watch + DJI camera"
                       "phone recording activity data + GoPro"
                       "For synchronization, trust the time in your activity data."
                       "automatic date and time"
                       "film the activity device's clock with seconds visible"
                       "Alpha Compose currently applies one fixed offset"
                       "camera's system clock, recording timestamp, and production timecode"
                       "GoPro product manuals"
                       "DJI Osmo Action date and time"
                       "Garmin watch time troubleshooting and synchronization"
                       "DaVinci Resolve is the recommended first choice"
                       "Adobe Premiere Pro"
                       "Final Cut Pro"
                       "Completed H.264 MP4 outputs can open in the browser"
                       "ProRes outputs remain download-first files for desktop editors"
                       "Project JSON is a separate browser workflow envelope"
                       "The public API accepts only the nested renderRequest"
                       "Alpha Compose creates or stores Project JSON only when you explicitly download, copy, upload, or paste it"
                       "excludes credentials, CSRF values, signed URLs, preview images, playback state, and job results"
                       "Codex can help if you give it access to the relevant files and docs"
                       "Claude can use code execution and file creation inside a chat"
                       "Claude Code is a separate terminal and IDE tool"
                       "FFmpeg itself is just the command-line media tool"
                       "plan availability and limits can change"
                       "source video is streamed"
                       "does not log activity-data values"
                       "not medical advice"
                       "does not infer emotions, health, or training readiness"]]
          (is (str/includes? page claim) claim))
        (doseq [retired ["2 GiB"
                         "Freediver connector"]]
          (is (not (str/includes? page retired)) retired))
        (let [faq-position (str/index-of (.body homepage) "href=\"/faq\"")
              privacy-position (str/index-of (.body homepage) "href=\"/privacy\"")
              terms-position (str/index-of (.body homepage) "href=\"/terms\"")]
          (is (every? some? [faq-position privacy-position terms-position]))
          (when (every? some? [faq-position privacy-position terms-position])
            (is (< faq-position privacy-position terms-position))))
        (is (str/includes? page
                           "<a href=\"/faq\" aria-current=\"page\">FAQ</a>"))
        (let [positions (mapv #(str/index-of page %)
                              ["href=\"/faq\""
                               "href=\"/privacy\""
                               "href=\"/terms\""])]
          (is (every? some? positions))
          (when (every? some? positions)
            (is (apply < positions)))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest preview-admission-cost-links-to-an-exact-faq-answer
  (let [compose (ui/page {:user {:email "member@example.com" :role :member}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? false})
        help-link (str "<a class=\"contextual-help\" "
                       "href=\"/faq#preview-admission-cost\" "
                       "aria-label=\"Learn about Preview admission cost\">"
                       "<span class=\"contextual-help-mark\" aria-hidden=\"true\">"
                       "<span>?</span></span></a>")]
    (is (str/includes? ui/faq-page
                       (str "<details class=\"faq-question\" "
                            "id=\"preview-admission-cost\">")))
    (is (str/includes? ui/faq-page
                       (str "<summary><h3>Why does Preview have an "
                            "admission cost?</h3></summary>")))
    (doseq [claim ["Each Preview attempt reserves up to PLN 1.25"
                   "Preview plus one Submit reserves up to PLN 2.50"
                   "success, failure, cancellation, or expiry"
                   "Retrying Preview makes a new reservation"]]
      (is (str/includes? ui/faq-page claim) claim))
    (is (str/includes? compose help-link))
    (is (not (str/includes? help-link "target=")))))

(deftest contextual-help-links-target-the-narrowest-faq-answers
  (let [homepage ui/anonymous-page
        compose (ui/page {:user {:email "member@example.com" :role :member}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? false})
        links [{:page homepage
                :fragment "generated-heartbeat-sound"
                :name "Learn about generated heartbeat audio"}
               {:page compose
                :fragment "google-drive-access"
                :name "Learn about Google Drive access"}
               {:page compose
                :fragment "supported-activity-data"
                :name "Learn about supported activity-data formats"}
               {:page compose
                :fragment "how-should-i-synchronize-devices-before-recording"
                :name "Learn how to synchronize devices before recording"}
               {:page compose
                :fragment "video-timezone"
                :name "Learn which video timezone to choose"}
               {:page compose
                :fragment "audio-options"
                :name "Learn about heartbeat audio options"}
               {:page compose
                :fragment "oxygen-saturation-support"
                :name "Learn about optional SpO2 data"}
               {:page compose
                :fragment "preview-admission-cost"
                :name "Learn about Preview admission cost"}]]
    (is (= 1 (count (re-seq #"class=\"contextual-help\"" homepage))))
    (is (= 7 (count (re-seq #"class=\"contextual-help\"" compose))))
    (doseq [{:keys [page fragment name]} links]
      (is (str/includes?
           page
           (str "<a class=\"contextual-help\" href=\"/faq#" fragment
                "\" aria-label=\"" name
                "\"><span class=\"contextual-help-mark\" aria-hidden=\"true\">"
                "<span>?</span></span></a>"))
          fragment))
    (doseq [page [homepage compose]]
      (is (not (re-find #"class=\"contextual-help\"[^>]+href=\"/faq\""
                        page)))
      (is (not (re-find #"class=\"contextual-help\"[^>]+target="
                        page))))))

(deftest signed-in-compose-renders-one-contextual-help-dialog-from-faq-copy
  (let [compose (ui/page {:user {:email "member@example.com" :role :member}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? false})
        fragments ["google-drive-access"
                   "audio-options"
                   "supported-activity-data"
                   "video-timezone"
                   "how-should-i-synchronize-devices-before-recording"
                   "oxygen-saturation-support"
                   "preview-admission-cost"]]
    (is (= 1 (count (re-seq #"id=\"contextual-help-dialog\"" compose))))
    (is (str/includes?
         compose
         (str "<dialog id=\"contextual-help-dialog\" "
              "aria-labelledby=\"contextual-help-title\">")))
    (is (str/includes? compose
                       "<button type=\"button\" class=\"contextual-help-close\">Close help</button>"))
    (is (str/includes?
         compose
         (str "<a id=\"contextual-help-full\" href=\"/faq\" target=\"_blank\" "
              "rel=\"noopener\">Open full FAQ in a new tab</a>")))
    (is (= (count fragments)
           (count (re-seq #"data-contextual-help-fragment=" compose))))
    (doseq [persistence-api ["localStorage"
                             "sessionStorage"
                             "indexedDB"]]
      (is (not (str/includes? compose persistence-api))
          persistence-api))
    (doseq [fragment fragments]
      (let [quoted (java.util.regex.Pattern/quote fragment)
            [_ faq-question faq-answer]
            (re-find
             (re-pattern
              (str "(?s)<details class=\"faq-question\" id=\"" quoted
                   "\"><summary><h3>(.*?)</h3></summary>"
                   "<div class=\"faq-answer\">(.*?)"
                   "<p class=\"faq-permalink\">"))
             ui/faq-page)
            [_ template-question template-answer]
            (re-find
             (re-pattern
              (str "(?s)<template data-contextual-help-fragment=\"" quoted
                   "\" data-contextual-help-question=\"(.*?)\">"
                   "<div class=\"contextual-help-copy\">(.*?)</div></template>"))
             compose)]
        (is (some? faq-question) fragment)
        (is (= faq-question template-question) fragment)
        (is (= faq-answer template-answer) fragment)))))

(deftest compose-contextual-help-is-modal-history-aware-and-pauses-playback
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(contextual-help-dialog-browser-outcome
                   page "1280,900" true)
                  (contextual-help-dialog-browser-outcome
                   page "390,844" false)]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= 7 (:linkCount outcome)))
      (is (= {:paused true
              :currentTime 42.25
              :playCalls 1
              :pauseCalls 1}
             (:pausedOnOpen outcome)))
      (doseq [presentation (:presentations outcome)]
        (is (:open presentation) presentation)
        (is (:modal presentation) presentation)
        (is (:focusContained presentation) presentation)
        (is (= (:expectedTitle presentation) (:title presentation))
            presentation)
        (is (= (:expectedAnswer presentation) (:answer presentation))
            presentation)
        (is (= (str "/faq#" (:fragment presentation))
               (:fullHref presentation)))
        (is (= "_blank" (:fullTarget presentation)))
        (is (= "noopener" (:fullRel presentation)))
        (is (= (:fragment presentation) (:historyFragment presentation)))
        (is (:urlUnchanged presentation) presentation)
        (is (:fits presentation) presentation)
        (is (:noHorizontalOverflow presentation) presentation))
      (if (:exerciseHistory outcome)
        (do
          (is (= {:popped true
                  :open false
                  :focusReturned true
                  :currentTime 42.25
                  :paused true}
                 (:back outcome)))
          (is (true? (get-in outcome [:forward :popped])))
          (is (true? (get-in outcome [:forward :open])))
          (is (true? (get-in outcome [:forward :modal])))
          (is (true? (get-in outcome [:forward :focusContained])))
          (is (= {:popped true
                  :open false
                  :focusReturned true
                  :currentTime 42.25
                  :paused true}
                 (:closed outcome)))
          (is (= {:cancelPrevented true
                  :popped true
                  :open false
                  :focusReturned true}
                 (get-in outcome [:presentations 1 :escape]))))
        (is (true? (get-in outcome [:presentations 0 :closed]))))
      (is (every? true?
                  (keep :closePopped (:presentations outcome))))
      (is (every? true?
                  (keep :closed (:presentations outcome))))
      (is (= {:paused true :currentTime 42.25 :playCalls 1}
             (:finalPlayback outcome)))
      (is (:statePreserved outcome) outcome)
      (is (= (:stateBefore outcome) (:stateAfter outcome)))
      (is (= {:fileId "private-drive-video"
              :selection "training.mp4"
              :playerSrc "/v1/drive/playback/live-compose-state"
              :playhead 42.25}
             (get-in outcome [:stateAfter :drive])))
      (is (= {:outputFormat "prores-422-mov"
              :fitMode "crop"
              :audioMode "source-only"
              :preset "2.7k25"
              :timeZone "UTC"
              :opacity "37"
              :spo2Enabled true
              :timerEnabled true}
             (get-in outcome [:stateAfter :form])))
      (is (= {:count 1
              :name "activity.csv"
              :text (str "timestamp,heart_rate\n"
                         "2026-07-17T10:00:00Z,120\n"
                         "2026-07-17T10:00:02Z,124")
              :loadedValue (str "timestamp,heart_rate\n"
                                "2026-07-17T10:00:00Z,120\n"
                                "2026-07-17T10:00:02Z,124")}
             (get-in outcome [:stateAfter :file])))
      (let [request (json/read-str (get-in outcome [:stateAfter :raw])
                                   :key-fn keyword)]
        (is (= "private-drive-video"
               (get-in request [:sourceVideo :fileId])))
        (is (= (get-in outcome [:stateAfter :file :text])
               (:telemetry request)))
        (is (= 37 (:futureTraceOpacityPercent request)))
        (is (= "reading_time,spo2\n2026-07-17T10:00:00Z,97"
               (get-in request [:spo2 :telemetry]))))
      (is (str/includes? (get-in outcome [:stateAfter :preview])
                         "Preview ready"))
      (is (str/includes? (get-in outcome [:stateAfter :job])
                         "Creating finished video"))
      (is (:noHorizontalOverflow outcome)))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest contextual-help-remains-visible-focusable-and-contained-in-a-browser
  (let [compose (ui/page {:user {:email "member@example.com" :role :member}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? false})
        wider-font-compose
        (str/replace-first
         compose
         "</style>"
         ".toggle-help>.toggle{letter-spacing:.02em}</style>")
        expected-homepage [["/faq#generated-heartbeat-sound"
                            "Learn about generated heartbeat audio"]]
        expected-compose [["/faq#google-drive-access"
                           "Learn about Google Drive access"]
                          ["/faq#audio-options"
                           "Learn about heartbeat audio options"]
                          ["/faq#how-should-i-synchronize-devices-before-recording"
                           "Learn how to synchronize devices before recording"]
                          ["/faq#video-timezone"
                           "Learn which video timezone to choose"]
                          ["/faq#supported-activity-data"
                           "Learn about supported activity-data formats"]
                          ["/faq#oxygen-saturation-support"
                           "Learn about optional SpO2 data"]
                          ["/faq#preview-admission-cost"
                           "Learn about Preview admission cost"]]
        surfaces {"homepage desktop"
                  [(contextual-help-browser-outcome
                    ui/anonymous-page "1280,900")
                   expected-homepage]
                  "homepage mobile"
                  [(contextual-help-browser-outcome
                    ui/anonymous-page "390,844")
                   expected-homepage]
                  "compose desktop"
                  [(contextual-help-browser-outcome compose "1280,900")
                   expected-compose]
                  "compose mobile"
                  [(contextual-help-browser-outcome compose "390,844")
                   expected-compose]
                  "compose mobile with wider font metrics"
                  [(contextual-help-browser-outcome wider-font-compose "390,844")
                   expected-compose]}]
    (doseq [[surface [outcome expected]] surfaces]
      (testing surface
        (is (nil? (:error outcome)) outcome)
        (is (= expected
               (mapv (juxt :href :name) (:presentations outcome))))
        (is (every? #(nil? (:target %)) (:presentations outcome)))
        (is (every? #(= "?" (:text %)) (:presentations outcome)))
        (is (every? :symbolHidden (:presentations outcome)))
        (is (every? :visible (:presentations outcome)))
        (is (every? :keyboardReachable (:presentations outcome)))
        (is (every? :focusVisible (:presentations outcome)))
        (is (every? #(and (<= 32 (:width %)) (<= 32 (:height %)))
                    (:presentations outcome)))
        (is (every? :associated (:presentations outcome)))
        (is (every? #(and (number? (:markWidth %))
                          (number? (:markHeight %))
                          (< (:markWidth %) (:width %))
                          (< (:markHeight %) (:height %)))
                    (:presentations outcome)))
        (is (every? #(<= 0.75
                         (/ (:markHeight %) (:associatedFontSize %))
                         0.85)
                    (:presentations outcome)))
        (is (every? :aligned (:presentations outcome)))
        (is (every? :wrapperContained (:presentations outcome)))
        (is (not-any? :overlapsSibling (:presentations outcome)))
        (is (not-any? :insideLabel (:presentations outcome)))
        (is (every? :fits (:presentations outcome)))
        (is (:hoverStyled outcome))
        (is (:noHorizontalOverflow outcome))))))

(deftest faq-fragments-open-scroll-and-preserve-hash-navigation
  (let [outcomes {"desktop" (faq-browser-outcome ui/faq-page
                                                 "generated-heartbeat-sound"
                                                 "1280,900")
                  "mobile" (faq-browser-outcome ui/faq-page
                                                "generated-heartbeat-sound"
                                                "390,844")}]
    (doseq [[surface outcome] outcomes]
      (testing surface
        (is (nil? (:error outcome)) (:error outcome))
        (is (true? (get-in outcome [:initial :open])))
        (is (true? (get-in outcome [:initial :inView])))
        (is (= "#generated-heartbeat-sound"
               (get-in outcome [:initial :hash])))
        (is (true? (get-in outcome [:changed :open])))
        (is (true? (get-in outcome [:changed :inView])))
        (is (= "#source-and-activity-data-retention"
               (get-in outcome [:changed :hash])))
        (is (true? (:initialStillOpen outcome)))
        (is (= "#generated-heartbeat-sound"
               (get-in outcome [:back :hash])))
        (is (true? (get-in outcome [:back :open])))
        (is (true? (get-in outcome [:back :inView])))
        (is (true? (:changedStillOpen outcome)))
        (is (= 28 (:summaryCount outcome)))
        (is (true? (:summariesKeyboardReachable outcome)))
        (is (true? (:permalinksAccessible outcome)))
        (is (zero? (:nestedDetails outcome)))
        (is (= "/faq" (:activeNav outcome)))
        (is (true? (:activeNavStyled outcome)))
        (is (true? (:noHorizontalOverflow outcome)))
        (is (true? (:detailsFit outcome)))))))

(deftest preview-admission-cost-deep-link-opens-the-new-faq-answer
  (let [outcome (faq-deep-link-browser-outcome ui/faq-page
                                               "preview-admission-cost"
                                               "390,844")]
    (is (nil? (:error outcome)) outcome)
    (is (= "#preview-admission-cost" (get-in outcome [:initial :hash])))
    (is (true? (get-in outcome [:initial :open])))
    (is (true? (get-in outcome [:initial :inView])))
    (is (true? (:noHorizontalOverflow outcome)))
    (is (true? (:detailsFit outcome)))))

(deftest pre-recording-synchronization-deep-link-opens-the-new-faq-answer
  (let [outcome (faq-deep-link-browser-outcome ui/faq-page
                                               "how-should-i-synchronize-devices-before-recording"
                                               "390,844")]
    (is (nil? (:error outcome)) outcome)
    (is (= "#how-should-i-synchronize-devices-before-recording"
           (get-in outcome [:initial :hash])))
    (is (true? (get-in outcome [:initial :open])))
    (is (true? (get-in outcome [:initial :inView])))
    (is (true? (:noHorizontalOverflow outcome)))
    (is (true? (:detailsFit outcome)))))

(deftest anonymous-homepage-explains-why-activity-video-matters
  (let [page ui/anonymous-page
        lower-page (str/lower-case page)]
    (doseq [claim ["Turn your activity into a video worth sharing."
                   "See what was happening inside you"
                   "Relive how it felt"
                   "generated heartbeat paced to your recorded heart-rate data"
                   "Share and notice change"
                   "saved videos can help you notice changes for yourself"
                   "Heart rate is the main supported graph"
                   "optional SpO2"
                   "More activity-data graphs may be supported later."]]
      (is (str/includes? page claim) claim))
    (doseq [misleading-claim ["recorded heart sounds"
                              "detect emotion"
                              "medical interpretation"
                              "automated training analysis"
                              "compares your activities"
                              "freediv"]]
      (is (not (str/includes? lower-page misleading-claim))
          misleading-claim))))

(deftest full-page-surfaces-share-the-telemetry-theme
  (let [pages
        {"anonymous" ui/anonymous-page
         "faq" ui/faq-page
         "privacy" ui/privacy-page
         "terms" ui/terms-page
         "Drive recovery" ui/drive-recovery-page
         "early access" (ui/early-access-page
                         {:email "verified@example.com"
                          :proof "signed-proof"})
         "signed-in compose, tokens, and administration"
         (ui/page {:user {:email "owner@example.com" :role :owner}
                   :csrf "csrf-test"
                   :tokens [{:id "token-1"
                             :name "Automation"
                             :createdAt "2026-07-22T12:00:00Z"
                             :revoked false}]
                   :members [{:email "member@example.com"
                              :role "member"
                              :status "active"}]
                   :logs-enabled? true})
         "operational logs"
         (ui/logs-page {:user {:email "owner@example.com"}
                        :logs []
                        :view "formatted"})}]
    (doseq [[surface page] pages]
      (testing surface
        (is (str/includes? page "data-theme=\"telemetry\""))
        (is (str/includes? page "--color-background:#031225"))
        (is (str/includes? page "background-color:var(--color-background)"))
        (is (str/includes? page "url('/telemetry-background.webp')"))
        (is (str/includes? page ":focus-visible"))
        (is (str/includes? page "<meta name=\"color-scheme\" content=\"dark\">"))))))

(deftest telemetry-theme-has-aa-contrast-focus-and-responsive-layout
  (let [compose (ui/page {:user {:email "owner@example.com" :role :owner}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? true})
        outcomes {"anonymous desktop" (theme-browser-outcome ui/anonymous-page
                                                             "1280,900")
                  "anonymous mobile" (theme-browser-outcome ui/anonymous-page
                                                            "390,844")
                  "faq desktop" (theme-browser-outcome ui/faq-page "1280,900")
                  "faq mobile" (theme-browser-outcome ui/faq-page "390,844")
                  "compose desktop" (theme-browser-outcome compose "1280,900")
                  "compose mobile" (theme-browser-outcome compose "390,844")}]
    (doseq [[surface outcome] outcomes]
      (testing surface
        (is (nil? (:error outcome)) (:error outcome))
        (is (true? (:noHorizontalOverflow outcome)))
        (is (empty? (:layoutOffenders outcome))
            (pr-str (:layoutOffenders outcome)))
        (is (empty? (:contrastOffenders outcome))
            (pr-str (:contrastOffenders outcome)))
        (is (<= 4.5 (:minContrast outcome)))
        (is (true? (:focusVisible outcome)))
        (is (<= 0.9 (:contentSurfaceAlpha outcome)))
        (is (<= 0.9 (:headerSurfaceAlpha outcome)))
        (is (true? (:backgroundIncludesAsset outcome)))
        (is (false? (:backgroundAnimated outcome)))))))

(deftest compose-wizard-panels-share-one-responsive-column
  (let [page (ui/page {:user {:email "owner@example.com" :role :owner}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? true})
        outcomes {"desktop hidden source"
                  (compose-card-layout-browser-outcome page "1280,900" false)
                  "desktop revealed source"
                  (compose-card-layout-browser-outcome page "1280,900" true)
                  "mobile hidden source"
                  (compose-card-layout-browser-outcome page "390,844" false)
                  "mobile revealed source"
                  (compose-card-layout-browser-outcome page "390,844" true)}]
    (doseq [[surface outcome] outcomes]
      (testing surface
        (is (nil? (:error outcome)) (:error outcome))
        (if (str/includes? surface "revealed source")
          (do
            (is (= "finished-video" (:route outcome)))
            (is (= "source-video" (:currentStep outcome)))
            (is (= ["source-video"] (mapv :step (:panels outcome)))))
          (do
            (is (= "transparent-overlay" (:route outcome)))
            (is (= "activity-data" (:currentStep outcome)))
            (is (= ["activity-data"] (mapv :step (:panels outcome))))))
        (is (true? (:aligned outcome)) (pr-str outcome))
        (is (true? (:noHorizontalOverflow outcome)) (pr-str outcome))
        (is (true? (:shellFits outcome)) (pr-str outcome))))))

(deftest product-header-is-keyboard-visible-and-responsive-across-complete-pages
  (let [compose (ui/page {:user {:email "owner@example.com" :role :owner}
                          :csrf "csrf-test"
                          :tokens []
                          :members []
                          :logs-enabled? true})
        logs (ui/logs-page {:user {:email "owner@example.com"}
                            :csrf "csrf-test"
                            :logs []
                            :view "formatted"})
        pages
        {"public desktop" [ui/privacy-page "/privacy" "1280,900"]
         "public mobile" [ui/terms-page "/terms" "390,844"]
         "signed-in desktop" [compose nil "1280,900"]
         "signed-in mobile" [compose nil "390,844"]
         "recovery desktop" [ui/drive-recovery-page nil "1280,900"]
         "recovery mobile" [ui/drive-recovery-page nil "390,844"]
         "admin desktop" [logs nil "1280,900"]
         "admin mobile" [logs nil "390,844"]}]
    (doseq [[surface [page active-path window-size]] pages]
      (testing surface
        (let [outcome (product-header-browser-outcome page window-size)]
          (is (nil? (:error outcome)) (:error outcome))
          (is (= ["/" "/faq" "/privacy" "/terms"]
                 (:keyboardOrder outcome)))
          (is (= active-path (:activeNav outcome)))
          (is (true? (:activeNavStyled outcome)))
          (is (true? (:forcedColorSupport outcome)))
          (is (true? (:allVisible outcome)))
          (is (true? (:focusVisible outcome)))
          (is (true? (:noHorizontalOverflow outcome)))
          (is (true? (:headerFits outcome))))))))

(deftest early-access-feedback-and-form-are-keyboard-and-mobile-safe
  (let [initial-page
        (ui/early-access-page
         {:email "verified@example.com"
          :proof "signed-proof"})
        retry-page
        (ui/early-access-page
         {:email "verified@example.com"
          :proof "signed-proof"
          :instagram "@runner"
          :message "Please let me test."
          :feedback {:kind :failure
                     :title "Request not sent"
                     :message "Retry below or email directly."}})
        success-page
        (ui/early-access-page
         {:feedback {:kind :success
                     :title "Request sent"
                     :message "Your request was sent."}})
        initial (early-access-browser-outcome initial-page "1280,900")
        retry (early-access-browser-outcome retry-page "375,800")
        success (early-access-browser-outcome success-page "1280,900")]
    (is (= 1280 (:viewportWidth initial)))
    (is (= "/v1/early-access/request" (:formAction initial)))
    (is (true? (:noHorizontalOverflow initial)))
    (is (true? (:formFits initial)))
    (is (= "early-access-feedback" (:activeId retry)))
    (is (= "alert" (:feedbackRole retry)))
    (is (= "/v1/early-access/request" (:formAction retry)))
    (is (true? (:emailReadOnly retry)))
    (is (= "early-access-email" (:emailLabel retry)))
    (is (= "early-access-instagram" (:instagramLabel retry)))
    (is (= "early-access-message" (:messageLabel retry)))
    (is (true? (:keyboardReachable retry)))
    (is (= ["/" "/faq" "/privacy" "/terms" "early-access-email"
            "early-access-instagram" "early-access-message" "button"
            "mailto:me@jamiep.org" "/v1/auth/login/start"
            "mailto:me@jamiep.org"]
           (:keyboardOrder retry)))
    (is (= "mailto:me@jamiep.org" (:mailto retry)))
    (is (<= (:viewportWidth retry) 500))
    (is (true? (:noHorizontalOverflow retry)))
    (is (true? (:formFits retry)))
    (is (= "early-access-feedback" (:activeId success)))
    (is (= "status" (:feedbackRole success)))
    (is (nil? (:formAction success)))
    (is (= "mailto:me@jamiep.org" (:mailto success)))
    (is (true? (:noHorizontalOverflow success)))))

(deftest authenticated-compose-page-renders-parseable-inline-script
  (let [port (available-port)
        {:keys [auth-system owner-cookie]} (fixture)
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [landing (request! port :get "/" nil {"Cookie" owner-cookie})
            script (second (re-find #"(?s)<script>(.*?)</script>"
                                    (.body landing)))
            configured-page (ui/page {:user {:email "owner@example.com"
                                             :role :member}
                                      :csrf "csrf-test"
                                      :picker-config {:access-token "access-test"
                                                      :api-key "key-test"
                                                      :app-id "app-test"
                                                      :csrf "csrf-test"}
                                      :tokens []
                                      :members []
                                      :logs-enabled? false})
            configured-script (second (re-find #"(?s)<script>(.*?)</script>"
                                               configured-page))
            valid? (and (string? script)
                        (string? configured-script)
                        (javascript-valid? script)
                        (javascript-valid? configured-script))]
        (is (= 200 (.statusCode landing)))
        (is (str/includes? (.body landing)
                           "<header class=\"product-header\">"))
        (is (< (str/index-of (.body landing) "href=\"/faq\"")
               (str/index-of (.body landing) "href=\"/privacy\"")
               (str/index-of (.body landing) "href=\"/terms\"")))
        (is (str/includes? (.body landing)
                           "<form method=\"post\" action=\"/v1/auth/logout\">"))
        (is (str/includes?
             (.orElse (.firstValue (.headers landing)
                                   "Content-Security-Policy")
                      "")
             "media-src 'self'"))
        (is (string? script))
        (is valid?
            "The rendered compose initialization script must parse."))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest authenticated-compose-page-orders-the-dynamic-timing-workspace
  (let [page (ui/page {:user {:email "member@example.com"
                              :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        activity "<h2>Choose your heart-rate file</h2>"
        output-controls "id=\"source-output-controls\""
        player "id=\"video-chrome\""
        timing "id=\"output-timespan-step\""
        overlays "<h2>Optional overlays</h2>"]
    (is (every? #(str/includes? page %)
                [activity output-controls player timing overlays
                 "Transparent ProRes 4444 overlay"
                 "Output start"
                 "Output end"
                 "Video/output timezone"]))
    (is (< (str/index-of page output-controls)
           (str/index-of page player)))
    (is (< (str/index-of page player)
           (str/index-of page timing)
           (str/index-of page activity)
           (str/index-of page overlays)))
    (is (re-find #"id=\"source-output-controls\"[^>]* hidden" page))
    (is (not (re-find #"<div class=\"step\">Step [23]</div>" page)))))

(deftest authenticated-compose-opens-with-the-canonical-outcome-choice
  (let [page (ui/page {:user {:email "member@example.com"
                              :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})]
    (doseq [fragment ["id=\"wizard-outcome-step\""
                      "data-step-id=\"outcome\""
                      "Step 1"
                      "What would you like to make?"
                      "A transparent overlay for my video editor."
                      "A finished video with my overlay already added."
                      "name=\"wizard-outcome\""
                      "value=\"transparent-overlay\""
                      "value=\"finished-video\""
                      "id=\"compose-workflow\""
                      "const wizardState="]]
      (is (str/includes? page fragment) fragment))
    (is (< (str/index-of page "id=\"wizard-outcome-step\"")
           (str/index-of page "id=\"compose-workflow\"")))
    (is (not (str/includes? page "localStorage")))
    (is (not (str/includes? page "sessionStorage")))))

(deftest authenticated-compose-renders-semantic-wizard-navigation
  (let [page (ui/page {:user {:email "member@example.com"
                              :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})]
    (doseq [fragment ["id=\"wizard-current-step-header\""
                      "id=\"wizard-current-step-heading\""
                      "id=\"wizard-progress\""
                      "id=\"wizard-step-overview\""
                      "id=\"wizard-step-list\""
                      "id=\"wizard-error-summary\""
                      "id=\"wizard-back\""
                      "id=\"wizard-next\""
                      "aria-current=\"step\""
                      "data-wizard-panel"
                      "data-step-id=\"source-video\""
                      "data-step-id=\"synchronization-decision\""
                      "data-step-id=\"confirm-video-clock\""
                      "data-step-id=\"matching-moment\""
                      "data-step-id=\"output-timespan\""
                      "data-step-id=\"activity-data\""
                      "data-step-id=\"optional-overlays\""
                      "data-step-id=\"output-settings\""
                      "data-step-id=\"review\""]]
      (is (str/includes? page fragment) fragment))))

(deftest compose-review-owns-actions-and-keeps-trace-opacity-advanced
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        review-start (str/index-of page "id=\"review-step\"")
        review-end (str/index-of page "</form>" review-start)]
    (doseq [fragment ["id=\"review-sections\""
                      "class=\"advanced-output-settings\""
                      "Future trace opacity (%)"
                      "id=\"preview-button\""
                      "id=\"submit-button\""]]
      (is (str/includes? page fragment) fragment))
    (let [positions [review-start
                     (str/index-of page "id=\"review-sections\"")
                     (str/index-of page "id=\"preview-button\"")
                     (str/index-of page "id=\"submit-button\"")
                     review-end]]
      (is (every? some? positions))
      (when (every? some? positions)
        (is (apply < positions))))))

(deftest review-orders-active-decisions-and-edits-populated-drafts
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(wizard-review-browser-outcome page "1280,900")
                  (wizard-review-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= ["outcome" "activity-data" "output-timespan"
              "optional-overlays" "timer-overlay"
              "output-settings"]
             (get-in outcome [:initialReview :steps])))
      (is (= (get-in outcome [:initialReview :steps])
             (get-in outcome [:initialReview :editSteps])))
      (is (= "review" (get-in outcome [:initialReview :current])))
      (is (true? (get-in outcome [:initialReview :nextHidden])))
      (is (true? (get-in outcome [:initialReview :actionsInside])))
      (is (some #(str/includes? % "ProRes 4444 MOV")
                (get-in outcome [:initialReview :summaries])))
      (is (= {:current "timer-overlay"
              :start "2026-07-17T11:00:00.4"
              :end "2026-07-17T11:00:01.6"}
             (:edited outcome)))
      (is (nil? (get-in outcome [:deselected :requestTimer])))
      (is (= "preview-stale"
             (get-in outcome [:deselected :previewClass])))
      (is (= (:edited outcome) (:restored outcome)))
      (is (= "Finish" (:finishLabel outcome)))
      (is (= (get-in outcome [:initialReview :steps])
             (get-in outcome [:restoredReview :steps])))
      (is (false? (:advancedOpen outcome)))
      (is (= {:summaryHidden false
              :sourceControlsHidden true
              :requestOutputFormat nil}
             (:transparent outcome)))
      (is (= ["outcome" "source-video" "activity-data"
              "synchronization-decision" "confirm-video-clock"
              "output-timespan" "optional-overlays"
              "timer-overlay" "output-settings"]
             (get-in outcome [:finished :review :steps])))
      (doseq [choice ["ProRes 422 MOV" "Crop to fill" "Source only"]]
        (is (some #(str/includes? % choice)
                  (get-in outcome [:finished :review :summaries]))
            choice))
      (is (= {:summaryHidden true
              :sourceControlsHidden false
              :request {:outputFormat "prores-422-mov"
                        :fitMode "crop"
                        :audioMode "source-only"}}
             (dissoc (:finished outcome) :review)))
      (is (true? (get-in outcome [:initialReview :noHorizontalOverflow]))))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest wizard-shell-gates-and-restores-navigation-on-both-routes
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(wizard-shell-browser-outcome page "1280,900")
                  (wizard-shell-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= {:current "outcome"
              :heading "What would you like to make?"
              :progress "Step 1 of 1"
              :activePanels ["outcome"]
              :currentSemantic "step"
              :currentButtons []
              :backDisabled true
              :nextHidden false
              :focus nil
              :noOverflow true}
             (:initial outcome)))
      (is (= "Step 1 of 8" (get-in outcome [:finishedRoute :progress])))
      (is (= "outcome" (get-in outcome [:finishedRoute :current])))
      (is (= "source-video" (get-in outcome [:source :current])))
      (is (= ["source-video"] (get-in outcome [:source :activePanels])))
      (is (= ["source-video"] (get-in outcome [:source :currentButtons])))
      (is (= "Step 2 of 8" (get-in outcome [:source :progress])))
      (is (true? (get-in outcome [:sourceError :errorFocused])))
      (is (str/includes? (get-in outcome [:sourceError :message])
                         "source video"))
      (is (= "activity-data" (get-in outcome [:activity :current])))
      (is (= "source-video" (get-in outcome [:backed :current])))
      (is (= "outcome" (get-in outcome [:direct :current])))
      (is (= "synchronization-decision"
             (get-in outcome [:synchronizationDecision :current])))
      (is (= 9 (get-in outcome [:shared :stepCount])))
      (is (= 9 (get-in outcome [:manual :stepCount])))
      (is (= "matching-moment" (get-in outcome [:matching :current])))
      (is (true? (get-in outcome [:matchingError :errorFocused])))
      (is (= "synchronization-decision"
             (get-in outcome [:browserBack :current])))
      (is (true? (get-in outcome [:browserBack :noOverflow])))
      (is (= "confirm-video-clock"
             (get-in outcome [:confirmVideoClock :current])))
      (is (true? (get-in outcome [:confirmError :errorFocused])))
      (is (str/includes? (get-in outcome [:confirmError :message])
                         "video recording clock"))
      (is (= "output-timespan"
             (get-in outcome [:finishedTimespan :current])))
      (is (= "Step 1 of 6"
             (get-in outcome [:transparentRoute :progress])))
      (is (= "activity-data" (get-in outcome [:transparentActivity :current])))
      (is (true? (get-in outcome [:activityError :errorFocused])))
      (is (str/includes? (get-in outcome [:activityError :message])
                         "Heart-rate"))
      (is (= "output-timespan" (get-in outcome [:timespan :current])))
      (is (true? (get-in outcome [:timingError :errorFocused])))
      (is (str/includes? (get-in outcome [:timingError :message])
                         "Output start"))
      (is (= "optional-overlays" (get-in outcome [:optional :current])))
      (is (= 7 (get-in outcome [:branched :stepCount])))
      (is (= "timer-overlay" (get-in outcome [:timer :current])))
      (is (true? (get-in outcome [:timerError :errorFocused])))
      (is (str/includes? (get-in outcome [:timerError :message])
                         "Timer start"))
      (is (= "optional-overlays" (get-in outcome [:pruned :current])))
      (is (= 6 (get-in outcome [:pruned :stepCount])))
      (is (true? (get-in outcome [:pruned :noOverflow]))))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest timeline-linked-fields-share-one-persistent-fullscreen-dock
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})]
    (doseq [fragment ["id=\"video-player\""
                      "data-timing-workspace"
                      "id=\"video-clock-confirmation\""
                      "id=\"timing-dock\""
                      "id=\"video-timeline-wrap\""
                      "@media(prefers-reduced-motion:reduce)"]]
      (is (str/includes? page fragment) fragment))
    (doseq [outcome [(persistent-timing-dock-browser-outcome page "1280,900")
                     (persistent-timing-dock-browser-outcome page "390,844")]]
      (is (nil? (:error outcome)) outcome)
      (is (= {:workspaceHidden true :current "outcome"}
             (:initial outcome)))
      (is (= {:workspaceHidden true :current "outcome"}
             (:chosen outcome)))
      (is (= "output-timespan" (get-in outcome [:overlay :current])))
      (is (false? (get-in outcome [:overlay :workspaceHidden])))
      (is (true? (get-in outcome [:overlay :beforePanel])))
      (is (= ["timing-dock" "timing-dock" "timing-dock"]
             (get-in outcome [:overlay :parents])))
      (is (= (str "My browser timezone ("
                  (get-in outcome [:overlay :browserZone])
                  ")")
             (get-in outcome [:overlay :localOption])))
      (is (every? (fn [{:keys [prevented value]}]
                    (and (false? prevented)
                         (= "2026-07-17T09:00" value)))
                  (:keyboard outcome)))
      (is (true? (get-in outcome [:incomplete :timelineHidden])))
      (is (true? (get-in outcome [:incomplete :dockVisible])))
      (is (false? (get-in outcome [:ready :timelineHidden])))
      (is (true? (get-in outcome [:ready :dockVisible])))
      (is (= {:current "activity-data"
              :workspaceHidden true
              :beforePanel true}
             (:activity outcome)))
      (is (= {:current "matching-moment"
              :workspaceHidden false
              :parent "timing-dock"
              :fieldsVisible true}
             (:matching outcome)))
      (is (= {:current "timer-overlay"
              :workspaceHidden false
              :parent "timing-dock"
              :fieldsVisible true}
             (:timer outcome)))
      (is (= {:element "video-chrome"
              :dockInside true
              :sameInputs true
              :scrollable "auto"
              :fieldsVisible true}
             (:fullscreen outcome)))
      (is (= {:focusRestored true :sameInput true}
             (:exited outcome)))
      (is (= {:current "outcome" :workspaceHidden true}
             (:switched outcome)))
      (is (= {:current "activity-data"
              :workspaceHidden true
              :panel "activity-data"
              :beforePanel true
              :sameDock true}
             (:sourceClock outcome)))
      (is (true? (:noHorizontalOverflow outcome))))))

(deftest outcome-route-switches-project-only-active-data-and-restore-dormant-source
  (let [outcome
        (wizard-outcome-browser-outcome
         (ui/page {:user {:email "member@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= {:workflowHidden false :selected nil}
           (:initial outcome)))
    (is (= {:workflowHidden false
            :route "finished-video"
            :currentStep "outcome"
            :selected "finished-video"
            :source "drive-source"
            :projectedSource "drive-source"}
           (:applied outcome)))
    (is (= {:route "transparent-overlay"
            :currentStep "outcome"
            :selected true
            :sourceDraft "drive-source"
            :projectedSource nil
            :outputFormat nil}
           (:inactive outcome)))
    (is (= {:route "finished-video"
            :currentStep "outcome"
            :selected true
            :source "drive-source"
            :projectedSource "drive-source"
            :outputFormat "h264-mp4"}
           (:restored outcome)))))

(deftest compose-page-includes-a-custom-output-framing-video-player
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :picker-config {:access-token "access-test"
                                       :api-key "key-test"
                                       :app-id "app-test"
                                       :csrf "csrf-test"}
                       :tokens []
                       :members []
                       :logs-enabled? false})]
    (doseq [fragment ["id=\"video-player\""
                      "id=\"video-chrome\""
                      "id=\"video-controls-dock\""
                      "id=\"source-video-player\""
                      "playsinline"
                      "id=\"video-play-pause\""
                      "data-seek-seconds=\"-60\""
                      "data-seek-seconds=\"-10\""
                      "data-seek-seconds=\"10\""
                      "data-seek-seconds=\"60\""
                      "id=\"video-volume\""
                      "id=\"video-fullscreen\""
                      "id=\"video-timeline\""
                      "id=\"video-clock-confirmation\""
                      "id=\"video-recording-start\""
                      "id=\"video-timezone\""
                      "id=\"confirm-video-clock\""
                      "id=\"video-clock-candidates\""
                      "id=\"video-source-summary\""
                      "Video timezone"
                      "role=\"slider\""
                      "aria-label=\"Output clock timeline\""
                      "id=\"video-buffered-ranges\""
                      "id=\"video-playhead\""
                      "id=\"video-output-range\""
                      "id=\"video-unused-before\""
                      "id=\"video-unused-after\""
                      "id=\"output-start-handle\""
                      "id=\"output-end-handle\""
                      "id=\"manual-sync-marker\""
                      "aria-label=\"Selected source-video frame\""
                      "aria-controls=\"manual-sync-source-seconds telemetry-sync-at\""
                      "aria-keyshortcuts=\"ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight Home End\""
                      "id=\"manual-sync-marker-help\""
                      "Shift+Left or Shift+Right moves 10 frames"
                      "id=\"timer-start-marker\""
                      "aria-label=\"Timer start\""
                      "aria-controls=\"timer-start-at\""
                      "id=\"timer-end-marker\""
                      "aria-label=\"Timer end\""
                      "aria-controls=\"timer-end-at\""
                      "id=\"timer-marker-help\""
                      "Timer markers. Left or Right moves 1 frame."
                      "id=\"timer-start-at\" type=\"datetime-local\" step=\".04\""
                      "id=\"timer-end-at\" type=\"datetime-local\" step=\".04\""
                      "aria-label=\"Output start\""
                      "aria-label=\"Output end\""
                      "id=\"video-range-status\""
                      "id=\"video-timeline-tooltip\""
                      "aria-keyshortcuts=\"Shift+ArrowLeft\""
                      "aria-keyshortcuts=\"ArrowLeft\""
                      "aria-keyshortcuts=\"Space\""
                      "aria-keyshortcuts=\"ArrowRight\""
                      "aria-keyshortcuts=\"Shift+ArrowRight\""
                      "aria-keyshortcuts=\"F\""
                      "aria-pressed=\"false\""
                      ".video-control:hover .video-shortcut"
                      ".video-control:focus-within .video-shortcut"
                      ".video-chrome:fullscreen"
                      ".video-chrome.is-fullscreen"
                      ".video-controls-dock"
                      "Selected source-video frame"
                      "Activity-data time at the selected frame"
                      "Timing workspace"
                      "Player audio is the original source"]]
      (is (str/includes? page fragment) fragment))
    (is (not (re-find #"<video[^>]+controls" page)))
    (is (< (str/index-of page "id=\"source-output-controls\"")
           (str/index-of page "id=\"video-player\"")))))

(deftest selected-video-player-seeks-frames-and-fails-without-clearing-render-selection
  (let [page (ui/page {:user {:email "owner@example.com" :role :member}
                       :csrf "csrf-test"
                       :picker-config {:access-token "access-test"
                                       :api-key "key-test"
                                       :app-id "app-test"
                                       :csrf "csrf-test"}
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(video-player-browser-outcome page "1280,900")
                  (video-player-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= {:start "0"
              :end "125.48"
              :startField "2026-07-23T21:59:30"
              :endField "2026-07-23T22:01:35.48"
              :unusedBefore 0}
             (dissoc (:initialRange outcome) :unusedAfter)))
      (is (pos? (get-in outcome [:initialRange :unusedAfter])))
      (is (= {:hidden true :disabled false :value "0"}
             (:markerReady outcome)))
      (is (= 1 (get-in outcome [:buffered :beforeProgress :segments])))
      (is (= 2 (get-in outcome [:buffered :afterProgress :segments])))
      (doseq [appearance [(get-in outcome [:buffered :afterProgress])
                          (get-in outcome [:fullscreen :buffered])]]
        (is (true? (:visible appearance)) appearance)
        (is (true? (:alongside appearance)) appearance)
        (is (< (:railFraction appearance) 0.5) appearance)
        (is (= "none" (:pointerEvents appearance)) appearance)
        (is (= [2 3 4 5]
               ((juxt :outputZ :unusedZ :layerZ :playheadZ) appearance))
            appearance)
        (is (true? (:colorsDistinct appearance)) appearance))
      (is (= {:start
              {:current 0
               :fields ["2026-07-23T21:59:30"
                        "2026-07-23T22:01:35.48"]
               :request {:startAt "2026-07-23T21:59:30.000Z"
                         :endAt "2026-07-23T22:01:35.480Z"}
               :markers [false "0" false "125.48"]
               :fieldsHidden false
               :markersSeparated true
               :handlesSeparated true}
              :middle
              {:current 62.75
               :fields ["2026-07-23T21:59:30"
                        "2026-07-23T22:01:35.48"]
               :request {:startAt "2026-07-23T21:59:30.000Z"
                         :endAt "2026-07-23T22:01:35.480Z"}
               :markers [false "0" false "125.48"]
               :fieldsHidden false
               :markersSeparated true
               :handlesSeparated true}
              :end
              {:current 125.48
               :fields ["2026-07-23T21:59:30"
                        "2026-07-23T22:01:35.48"]
               :request {:startAt "2026-07-23T21:59:30.000Z"
                         :endAt "2026-07-23T22:01:35.480Z"}
               :markers [false "0" false "125.48"]
               :fieldsHidden false
               :markersSeparated true
               :handlesSeparated true}
              :outside
              {:current 125.5
               :fields ["2026-07-23T21:59:30"
                        "2026-07-23T22:01:35.48"]
               :request {:startAt "2026-07-23T21:59:30.000Z"
                         :endAt "2026-07-23T22:01:35.480Z"}
               :markers [false "0" false "125.48"]
               :fieldsHidden false
               :markersSeparated true
               :handlesSeparated true}
              :disabled {:request nil :markers [true true]}}
             (:timerDefaults outcome)))
      (is (= {:clampedStart "20"
              :clampedEnd "100"
              :startMessage
              "Move or disable the timer before excluding it from the output."
              :endMessage
              "Move or disable the timer before excluding it from the output."
              :keyboardStart
              {:value "19.96"
               :field "2026-07-23T21:59:49.96"
               :highlighted true}}
             (:timerRange outcome)))
      (is (= {:start
              {:values ["20" "100"]
               :fields ["2026-07-23T21:59:50"
                        "2026-07-23T22:01:10"]
               :request {:startAt "2026-07-23T21:59:50.000Z"
                         :endAt "2026-07-23T22:01:10.000Z"}
               :startHighlighted false
               :endHighlighted false}
              :end {:values ["20" "100"]
                    :startHighlighted false
                    :endHighlighted false}
              :captures [{:element "video-timeline" :pointerId 10}
                         {:element "video-timeline" :pointerId 11}]
              :releases [{:element "video-timeline" :pointerId 10}
                         {:element "video-timeline" :pointerId 11}]
              :released [false false]
              :videoUnchanged false}
             (:timerMarkerPointer outcome)))
      (is (= {:startFocus [false false]
              :startLeftPrevented true
              :startAfterLeft "19.96"
              :startShiftLeftPrevented true
              :startAfterShiftLeft "19.96"
              :startHomePrevented true
              :startAfterHome "19.96"
              :startEndPrevented true
              :startAfterEnd "99.96"
              :endFocus [false true]
              :endLeftPrevented true
              :endAfterLeft "99.96"
              :endShiftLeftPrevented true
              :endAfterShiftLeft "99.56"
              :endHomePrevented true
              :endAfterHome "20"
              :endEndPrevented true
              :endAfterEnd "100"
              :request {:startAt "2026-07-23T21:59:49.960Z"
                        :endAt "2026-07-23T22:01:10.000Z"}
              :videoUnchanged false}
             (:timerMarkerKeyboard outcome)))
      (is (str/includes? (:invalidFrameStatus outcome)
                         "whole 25 fps frames"))
      (is (str/includes? (:shortTimerStatus outcome)
                         "at least one 25 fps frame"))
      (is (str/includes? (:negativeTrimStatus outcome)
                         "cannot precede sourceVideo.recordingStartAt"))
      (is (str/includes? (:fractionalTrimStatus outcome)
                         "whole 25 fps source frame"))
      (is (= {:hidden false
              :paused true
              :currentTime 0
              :playCalls 0
              :src "/v1/drive/playback/00000000-0000-0000-0000-000000000115"
              :selection "ride.mp4"
              :fileId "private-mp4"
              :time "23:59:30.000 / 00:01:35.500"
              :timeAria
              (str "Recording time, current 23 July 2026 at "
                   "23:59:30.000, Europe/Warsaw (GMT+02:00); "
                   "source end 24 July 2026 at 00:01:35.500, "
                   "Europe/Warsaw (GMT+02:00)")
              :context
              "Recording time · 23-24 Jul 2026 · Europe/Warsaw"
              :contextAria
              "Recording time, 23 to 24 July 2026, Europe/Warsaw"
              :timelineMax "125.5"
              :timelineValueText
              (str "Recording time, 23 July 2026 at 23:59:30.000, "
                   "Europe/Warsaw (GMT+02:00)")
              :bufferedSegments 2
              :fit "contain"
              :analysisRequest {:fileId "private-mp4"}
              :request {:fileId "private-mp4"}
              :inspectionRequest {:fileId "private-mp4"}
              :mode {:sourceControlsHidden false
                     :summaryHidden true
                     :stageHidden false
                     :transportHidden false
                     :timelineLabel "Source video timeline"}
              :clock
              {:start "2026-07-23T23:59:30"
               :zone "Europe/Warsaw"
               :confirmed "true"
               :candidates 1
               :summary
               (str "Filename authoritative-ride.mp4 Detected date "
                    "2026-07-23 23:59:30.000 Detected timezone Europe/Warsaw "
                    "Source begin 2026-07-23 23:59:30.000 Europe/Warsaw "
                    "Source end 2026-07-24 00:01:35.500 Europe/Warsaw")
               :request
               {:fileId "private-mp4"
                :recordingStartAt "2026-07-23T21:59:30.000Z"
                :timeZone "Europe/Warsaw"}}}
             (dissoc (:initial outcome)
                     :dates :dateLabelsSeparated :ticks)))
      (is (= ["23 Jul 2026" "24 Jul 2026"]
             (mapv :text (get-in outcome [:initial :dates]))))
      (is (= ["2026-07-23" "2026-07-24"]
             (mapv :date (get-in outcome [:initial :dates]))))
      (is (every? :visible (get-in outcome [:initial :dates])))
      (is (true? (get-in outcome [:initial :dateLabelsSeparated])))
      (is (< (Math/abs
              (- (/ 30.0 125.5)
                 (get-in outcome [:initial :dates 0 :end])))
             1.0e-9))
      (is (= (get-in outcome [:initial :dates 0 :end])
             (get-in outcome [:initial :dates 1 :start])))
      (is (every? #(re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" %)
                  (get-in outcome [:initial :ticks])))
      (is (= ["23:59:30.000" "00:01:35.500"]
             ((juxt first last) (get-in outcome [:initial :ticks]))))
      (is (= {:confirmed "false"
              :status
              "Video timezone must be a valid IANA timezone identifier."}
             (:fixedOffsetRejected outcome)))
      (is (= [{:name "Jump back 60 seconds"
               :keys "Shift+ArrowLeft"
               :hint "Shift+Left"
               :focusVisible true
               :stable true}
              {:name "Jump back 10 seconds"
               :keys "ArrowLeft"
               :hint "Left"
               :focusVisible true
               :stable true}
              {:name "Play video"
               :keys "Space"
               :hint "Space"
               :focusVisible true
               :stable true}
              {:name "Jump forward 10 seconds"
               :keys "ArrowRight"
               :hint "Right"
               :focusVisible true
               :stable true}
              {:name "Jump forward 60 seconds"
               :keys "Shift+ArrowRight"
               :hint "Shift+Right"
               :focusVisible true
               :stable true}
              {:name "Fullscreen"
               :keys "F"
               :hint "F"
               :focusVisible true
               :stable true}]
             (:shortcutHints outcome)))
      (is (= "cover" (:cropped outcome)))
      (is (= 66.275 (:transportTime outcome)))
      (is (= 62.75 (:scrubTime outcome)))
      (is (= 63.75 (:keyboardTime outcome)))
      (is (= {:hidden false
              :text "00:01:04.125"}
             (:hover outcome)))
      (let [states [(:initialPlayback outcome)
                    (:playing outcome)
                    (:paused outcome)
                    (:mediaPlaying outcome)
                    (:mediaPaused outcome)
                    (:spacePlaying outcome)
                    (:spacePaused outcome)
                    (get-in outcome [:fullscreen :playback])]]
        (is (= [{:paused true
                 :name "Play video"
                 :symbol "▶"
                 :decorative true
                 :visible true}
                {:paused false
                 :name "Pause video"
                 :symbol "⏸"
                 :decorative true
                 :visible true}
                {:paused true
                 :name "Play video"
                 :symbol "▶"
                 :decorative true
                 :visible true}
                {:paused false
                 :name "Pause video"
                 :symbol "⏸"
                 :decorative true
                 :visible true}
                {:paused true
                 :name "Play video"
                 :symbol "▶"
                 :decorative true
                 :visible true}
                {:paused false
                 :name "Pause video"
                 :symbol "⏸"
                 :decorative true
                 :visible true}
                {:paused true
                 :name "Play video"
                 :symbol "▶"
                 :decorative true
                 :visible true}
                {:paused true
                 :name "Play video"
                 :symbol "▶"
                 :decorative true
                 :visible true}]
               (mapv #(dissoc % :width :height) states)))
        (is (apply = (map (juxt :width :height) states))))
      (is (= {:shortcutStart 63.75
              :rightPrevented true
              :afterRight 73.75
              :shiftRightPrevented true
              :afterShiftRight 125.5
              :leftPrevented true
              :afterLeft 115.5
              :shiftLeftPrevented true
              :afterShiftLeft 55.5
              :spacePrevented true
              :afterSpacePaused false
              :pausedAfterSecondSpace true}
             (:shortcuts outcome)))
      (is (= {:editableChecks [{:kind "select"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "textarea"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "input"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "contenteditable"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "textbox"
                                :prevented false
                                :before 55.5
                                :after 55.5}]
              :modifiedChecks [{:kind "ctrl"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "meta"
                                :prevented false
                                :before 55.5
                                :after 55.5}
                               {:kind "alt"
                                :prevented false
                                :before 55.5
                                :after 55.5}]
              :focusedButtonPrevented false
              :afterFocusedButtonKey 55.5
              :afterFocusedButtonClick 65.5
              :hiddenStart 65.5
              :hiddenPrevented false
              :afterHidden 65.5}
             (:exclusions outcome)))
      (is (= {:prevented true
              :request "video-chrome"
              :elementId "video-chrome"
              :label "Exit fullscreen"
              :pressed "true"
              :hint "F or Esc"
              :hintVisible true
              :auto true
              :focusUnchanged true
              :completeChrome true
              :contextInside true
              :contextVisible true
              :datesVisible true
              :dateLabelsVisible true
              :dateLabelsSeparated true
              :dockInside true
              :fullscreenLayout true
              :dockVisible true
              :timelineVisible true
              :markerInside true
              :markerVisible false
              :markerValueText "Elapsed time, 00:00:00.000"
              :markerControls "manual-sync-source-seconds telemetry-sync-at"
              :helpVisible false
              :timerMarkersInside true
              :timerMarkersVisible true
              :timerMarkerValues ["19.96" "100"]
              :timerMarkerControls ["timer-start-at" "timer-end-at"]
              :timerHelpVisible true
              :noHorizontalOverflow true
              :timerCount 1}
             (get-in outcome [:fullscreen :entry])))
      (is (= {:auto false :hintVisible false}
             (get-in outcome [:fullscreen :afterFourSeconds])))
      (is (= {:hint "F or Esc" :visible true}
             (get-in outcome [:fullscreen :focusedHint])))
      (is (= {:prevented true
              :label "Fullscreen"
              :pressed "false"
              :elementId nil
              :auto false}
             (get-in outcome [:fullscreen :exit])))
      (is (= {:escapePrevented false
              :label "Fullscreen"
              :pressed "false"
              :auto false
              :timerCleared true}
             (get-in outcome [:fullscreen :browserExit])))
      (is (= {:request "video-chrome" :label "Exit fullscreen"}
             (get-in outcome [:fullscreen :buttonEntry])))
      (is (= {:exitCount 2 :label "Fullscreen"}
             (get-in outcome [:fullscreen :buttonExit])))
      (is (= "ride.mp4" (get-in outcome [:unsupported :selection])))
      (is (= "private-mp4" (get-in outcome [:unsupported :fileId])))
      (is (str/includes? (get-in outcome [:unsupported :message])
                         "remains selected for rendering"))
      (is (= {:disabledStart 65.5
              :disabledSeekPrevented false
              :afterDisabledSeek 65.5
              :disabledFullscreenPrevented false
              :fullscreenRequestsUnchanged true
              :range ["19.96" "100"]
              :marker {:hidden true :disabled false :value "0"}
              :timerMarkers [false false "19.96"
                             false false "100"]}
             (select-keys (:unsupported outcome)
                          [:disabledStart
                           :disabledSeekPrevented
                           :afterDisabledSeek
                           :disabledFullscreenPrevented
                           :fullscreenRequestsUnchanged
                           :range
                           :marker
                           :timerMarkers])))
      (is (= {:fileId "raw-video"
              :start ""
              :zone "UTC"
              :confirmed "true"
              :request
              {:fileId "raw-video"
               :recordingStartAt "2026-10-24T00:19:45.000Z"
               :timeZone "UTC"}
              :status "JSON applied to the form."
              :range ["10" "20"]
              :marker "15"
              :mode {:sourceControlsHidden false
                     :summaryHidden true
                     :stageHidden false
                     :transportHidden false
                     :timelineLabel "Source video timeline"}
              :timer {:enabled true
                      :fields ["2026-10-24T00:19:57"
                               "2026-10-24T00:20:03"]
                      :markers [false "12" false "18"]}}
             (:rawRestored outcome)))
      (is (nil? (:clockCorrection outcome)))
      (is (:noHorizontalOverflow outcome) outcome))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest selected-drive-playback-capability-matches-the-browser-support-matrix
  (let [page (ui/page {:user {:email "owner@example.com" :role :member}
                       :csrf "csrf-test"
                       :picker-config {:access-token "access-test"
                                       :api-key "key-test"
                                       :app-id "app-test"
                                       :csrf "csrf-test"}
                       :tokens []
                       :members []
                       :logs-enabled? false})
        cases [{:label "webcodecs present + canPlayType supported"
                :webcodecs? true
                :can-play-type "probably"
                :supported? true
                :reason nil}
               {:label "webcodecs present + canPlayType unsupported"
                :webcodecs? true
                :can-play-type ""
                :supported? false
                :reason "the media element cannot play the selected container and codec"}
               {:label "webcodecs unavailable + canPlayType supported"
                :webcodecs? false
                :can-play-type "probably"
                :supported? true
                :reason nil}
               {:label "webcodecs unavailable + canPlayType unsupported"
                :webcodecs? false
                :can-play-type ""
                :inspected-duration nil
                :supported? false
                :reason "this browser does not support the selected container and codec"}]]
    (doseq [{:keys [label webcodecs? can-play-type inspected-duration
                    supported? reason]
             :or {inspected-duration 42}} cases]
      (let [outcome (playback-capability-browser-outcome
                     page
                     {:window-size "1280,900"
                      :webcodecs? webcodecs?
                      :can-play-type can-play-type
                      :inspected-duration inspected-duration})]
        (is (nil? (:error outcome)) label)
        (is (= "ride.mov" (:selection outcome)) label)
        (is (= "hevc-source" (:fileId outcome)) label)
        (is (= [{:fileId "hevc-source"}] (:analysisRequests outcome)) label)
        (is (= ["video/quicktime; codecs=\"hvc1\""] (:canPlayTypeCalls outcome))
            label)
        (if webcodecs?
          (is (= [{:codec "hvc1"}] (:videoDecoderCalls outcome)) label)
          (is (= [] (:videoDecoderCalls outcome)) label))
        (is (true? (:summaryHidden outcome)) label)
        (is (true? (:noHorizontalOverflow outcome)) label)
        (is (= (if (nil? inspected-duration)
                 "Unavailable"
                 "2026-07-26 07:12:47.000 Europe/Warsaw")
               (:sourceEnd outcome))
            label)
        (if supported?
          (do
            (is (= [{:fileId "hevc-source"}] (:sessionRequests outcome)) label)
            (is (= "/v1/drive/playback/00000000-0000-0000-0000-000000000155"
                   (:src outcome))
                label)
            (is (= "Ready. Click or drag the timeline to seek."
                   (:status outcome))
                label)
            (is (false? (:stageHidden outcome)) label)
            (is (false? (:transportHidden outcome)) label))
          (do
            (is (= [] (:sessionRequests outcome)) label)
            (is (nil? (:src outcome)) label)
            (is (= (str "This video cannot play in this browser because "
                        reason
                        " (MOV, HEVC, hvc1, AAC). It remains selected for rendering.")
                   (:status outcome))
                label)
            (is (true? (:stageHidden outcome)) label)
            (is (false? (:transportHidden outcome)) label)))))))

(deftest selected-drive-playback-preparation-failures-expose-bounded-details
  (let [page (ui/page {:user {:email "owner@example.com" :role :member}
                       :csrf "csrf-test"
                       :picker-config {:access-token "access-test"
                                       :api-key "key-test"
                                       :app-id "app-test"
                                       :csrf "csrf-test"}
                       :tokens []
                       :members []
                       :logs-enabled? false})
        cases [{:label "analysis failure"
                :analysis-response {:ok false
                                    :status 503
                                    :body {:error "drive_source_unavailable"
                                           :retryable true}}
                :session-response {:ok true
                                   :status 201
                                   :body {:playbackUrl "/v1/drive/playback/00000000-0000-0000-0000-000000000199"
                                          :contentType "video/mp4"
                                          :size 2048}}
                :expected-status
                (str "The selected video remains selected for rendering, but playback could not be prepared. "
                     "Playback analysis failed (503, drive_source_unavailable).")
                :expected-session-requests []}
               {:label "session failure with guidance"
                :analysis-response {:ok true
                                    :status 200
                                    :body {:fileName "broken-source.mp4"
                                           :evidence {:container {:format "mp4" :majorBrand "isom"}
                                                      :video {:codec "h264" :codecTag "avc1"
                                                              :profile "High" :pixelFormat "yuv420p"}
                                                      :audio {:codec "aac"}}}}
                :session-response {:ok false
                                   :status 422
                                   :body {:error "selected_source_work_exceeded"
                                          :reason "selected_source_processing"
                                          :guidance "Shorten the range, choose another section, or optimize the video as a seekable MP4."}}
                :expected-status
                (str "The selected video remains selected for rendering, but playback could not be prepared. "
                     "Playback session failed (422, selected_source_work_exceeded): "
                     "Shorten the range, choose another section, or optimize the video as a seekable MP4.")
                :expected-session-requests [{:fileId "broken-source"}]}
               {:label "invalid playback session response"
                :analysis-response {:ok true
                                    :status 200
                                    :body {:fileName "broken-source.mp4"
                                           :evidence {:container {:format "mp4" :majorBrand "isom"}
                                                      :video {:codec "h264" :codecTag "avc1"
                                                              :profile "High" :pixelFormat "yuv420p"}
                                                      :audio {:codec "aac"}}}}
                :session-response {:ok true
                                   :status 201
                                   :body {:playbackUrl "/v1/not-drive-playback/invalid"
                                          :contentType "video/mp4"
                                          :size 2048}}
                :expected-status
                (str "The selected video remains selected for rendering, but playback could not be prepared. "
                     "Playback session returned an invalid browser playback URL.")
                :expected-session-requests [{:fileId "broken-source"}]}]]
    (doseq [{:keys [label analysis-response session-response expected-status
                    expected-session-requests]} cases]
      (let [outcome (playback-preparation-failure-browser-outcome
                     page
                     {:window-size "1280,900"
                      :analysis-response analysis-response
                      :session-response session-response})]
        (is (nil? (:error outcome)) label)
        (is (= "broken.mp4" (:selection outcome)) label)
        (is (= "broken-source" (:fileId outcome)) label)
        (is (= [{:fileId "broken-source"}] (:analysisRequests outcome)) label)
        (is (= expected-session-requests (:sessionRequests outcome)) label)
        (is (= expected-status (:status outcome)) label)
        (is (true? (:stageHidden outcome)) label)
        (is (false? (:transportHidden outcome)) label)
        (is (nil? (:src outcome)) label)
        (is (true? (:noHorizontalOverflow outcome)) label)))))

(deftest no-source-range-reveals-the-clock-timeline-and-marker-model
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(no-source-timer-browser-outcome page "1280,900")
                  (no-source-timer-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= {:workspaceHidden true
              :timelineHidden false
              :sourceControlsHidden true
              :summaryHidden false}
             (:initial outcome)))
      (is (true? (get-in outcome [:incomplete :timelineHidden])))
      (is (str/includes? (get-in outcome [:incomplete :status]) "Output end"))
      (is (true? (get-in outcome [:offFrame :timelineHidden])))
      (is (str/includes? (get-in outcome [:offFrame :status])
                         "whole 25 fps frames"))
      (is (= {:timelineHidden false
              :stageHidden true
              :transportHidden true
              :label "Output clock timeline"
              :valueText
              "Output clock, 17 July 2026 at 09:00:00.000, UTC (GMT+00:00)"
              :context "Output clock · 17 Jul 2026 · UTC"
              :contextAria "Output clock, 17 July 2026, UTC"
              :dates ["17 Jul 2026"]
              :tickValues
              (if (= 1280 (:viewportWidth outcome))
                ["09:00:00.000" "09:00:00.500" "09:00:01.000"
                 "09:00:01.500" "09:00:02.000"]
                ["09:00:00.000" "09:00:01.000" "09:00:02.000"])
              :ticks (if (= 1280 (:viewportWidth outcome)) 5 3)
              :status "Output timeline ready."}
             (:valid outcome)))
      (is (= {:hidden false
              :disabled true
              :value "1"
              :valueText
              "Output clock, 17 July 2026 at 09:00:01.000, UTC (GMT+00:00)"}
             (:manual outcome)))
      (is (= {:enabled false
              :fields ["" ""]
              :request nil
              :markers [true true]
              :timelineHidden false
              :sourceVideo nil
              :outputFormat nil}
             (:applied outcome)))
      (is (= {:enabled true
              :fields ["2026-07-17T09:00"
                       "2026-07-17T09:00:02"]
              :request {:startAt "2026-07-17T09:00:00.000Z"
                        :endAt "2026-07-17T09:00:02.000Z"}
              :markers [false false]
              :fieldsHidden true
              :markersInside true}
             (:enabled outcome)))
      (is (= {:enabled false
              :request nil
              :markers [true true]}
             (:disabled outcome)))
      (is (= {:enabled true
              :fields ["2026-07-17T09:00:00.4"
                       "2026-07-17T09:00:01.6"]
              :request {:startAt "2026-07-17T09:00:00.400Z"
                        :endAt "2026-07-17T09:00:01.600Z"}
              :markers [false "0.4" false "1.6"]
              :status "JSON applied to the form."
              :timelineHidden false}
             (:restored outcome)))
      (is (:noHorizontalOverflow outcome)))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest compose-page-exposes-bounded-future-trace-opacity-control
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})]
    (is (str/includes? page "Future trace opacity (%)"))
    (is (str/includes?
         page
         "id=\"future-trace-opacity-percent\" type=\"number\" min=\"0\" max=\"100\""))
    (is (str/includes? page "value=\"25\""))
    (is (str/includes? page "<code>futureTraceOpacityPercent</code>"))))

(deftest future-trace-opacity-round-trips-and-validates-in-a-browser
  (let [outcome
        (future-trace-opacity-browser-outcome
         (ui/page {:user {:email "member@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= {:type "number" :min "0" :max "100" :defaultValue 25}
           (:bounds outcome)))
    (is (= {:form 25 :json 25} (:omitted outcome)))
    (is (= {:form 100 :json 100} (:applied outcome)))
    (is (= 0 (:generated outcome)))
    (doseq [message [(:nonNumeric outcome) (:outOfRange outcome)]]
      (is (str/includes? message "Request.futureTraceOpacityPercent"))
      (is (str/includes? message "number from 0 through 100")))
    (is (true? (get-in outcome [:blank :prevented])))
    (is (str/includes? (get-in outcome [:blank :message])
                       "Future trace opacity"))))

(deftest synchronization-mode-is-conscious-conditional-and-round-trips
  (let [outcome
        (synchronization-mode-browser-outcome
         (ui/page {:user {:email "member@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= {:current "outcome"
            :selected nil
            :manualHidden true
            :previewDisabled true
            :submitDisabled true
            :request {}}
           (:initial outcome)))
    (is (= ["Yes - the camera and activity device clocks matched"
            "No - the camera and activity device clocks were different"]
           (:labels outcome)))
    (is (= "synchronization-decision"
           (get-in outcome [:manualChoice :current])))
    (is (= "manual-anchor"
           (get-in outcome [:manualChoice :selected])))
    (is (true? (get-in outcome [:manualChoice :manualHidden])))
    (is (false? (get-in outcome [:manualChoice :previewDisabled])))
    (is (false? (get-in outcome [:manualChoice :submitDisabled])))
    (is (= "manual-anchor"
           (get-in outcome [:manualChoice :request :synchronizationMode])))
    (is (contains? (get-in outcome [:manualChoice :request]) :telemetrySyncAt))
    (is (contains? (get-in outcome [:manualChoice :request]) :cameraSyncAt))
    (is (= "matching-moment"
           (get-in outcome [:manualStep :current])))
    (is (false? (get-in outcome [:manualStep :manualHidden])))
    (is (= "shared-clock"
           (get-in outcome [:sharedChoice :selected])))
    (is (true? (get-in outcome [:sharedChoice :manualHidden])))
    (is (false? (get-in outcome [:sharedChoice :previewDisabled])))
    (is (false? (get-in outcome [:sharedChoice :submitDisabled])))
    (is (= "shared-clock"
           (get-in outcome [:sharedChoice :request :synchronizationMode])))
    (is (= "output-settings"
           (get-in outcome [:sharedStep :current])))
    (is (not (contains? (get-in outcome [:sharedChoice :request])
                        :telemetrySyncAt)))
    (is (not (contains? (get-in outcome [:sharedChoice :request])
                        :cameraSyncAt)))
    (is (str/includes? (:missing outcome)
                       "Request.synchronizationMode is required"))
    (is (str/includes? (:unknown outcome)
                       "Request.synchronizationMode must be shared-clock or manual-anchor"))
    (doseq [[case-name field guidance]
            [[:manualMissingTelemetry "telemetrySyncAt" "required"]
             [:manualMissingCamera "cameraSyncAt" "required"]
             [:manualMissingBoth "telemetrySyncAt" "required"]
             [:manualInvalidTelemetry "telemetrySyncAt" "ISO-8601"]
             [:manualInvalidCamera "cameraSyncAt" "ISO-8601"]
             [:manualBlankTelemetry "telemetrySyncAt" "ISO-8601"]
             [:manualNullCamera "cameraSyncAt" "ISO-8601"]
             [:sharedTelemetry "telemetrySyncAt" "omitted"]
             [:sharedCamera "cameraSyncAt" "omitted"]
             [:sharedBoth "telemetrySyncAt" "omitted"]]]
      (let [message (get-in outcome [:validation case-name])]
        (is (str/includes? message field) case-name)
        (is (str/includes? message guidance) case-name)))))

(deftest manual-video-sync-transitions-from-elapsed-to-derived-recording-time
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(elapsed-manual-sync-browser-outcome page "1280,900")
                  (elapsed-manual-sync-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= 9 (:stepCount outcome)))
      (is (= "matching-moment" (get-in outcome [:elapsed :current])))
      (is (= "Elapsed time" (get-in outcome [:elapsed :mode])))
      (is (= {:visual "Elapsed time" :accessible "Elapsed time"}
             (get-in outcome [:elapsed :context])))
      (is (= [] (get-in outcome [:elapsed :dates])))
      (is (= {:hidden false
              :disabled true
              :value "0"
              :text "Elapsed time, 00:00:00.000"}
             (get-in outcome [:unavailable :marker])))
      (is (= "" (get-in outcome [:unavailable :sourceSeconds])))
      (is (str/starts-with? (get-in outcome [:elapsed :transport])
                            "00:00:00.000"))
      (is (every? #(re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" %)
                  (get-in outcome [:elapsed :ticks])))
      (is (= {:hidden false
              :disabled false
              :value "0"
              :text "Elapsed time, 00:00:00.000"}
             (get-in outcome [:elapsed :marker])))
      (is (= "" (get-in outcome [:elapsed :sourceSeconds])))
      (is (= "hidden" (get-in outcome [:elapsed :cameraType])))
      (is (true? (get-in outcome [:elapsed :clockPanelHidden])))
      (is (= "My browser timezone ("
             (subs (get-in outcome [:elapsed :browserOption]) 0 21)))
      (is (= "0.04" (get-in outcome [:selected :sourceSeconds])))
      (is (= "00:00:00.040"
             (get-in outcome [:selected :sourceText])))
      (is (= 0.04 (get-in outcome [:selected :videoCurrent])))
      (is (= "Synced recording time"
             (get-in outcome [:synced :mode])))
      (is (= {:visual "Synced recording time · 25 Oct 2026 · UTC"
              :accessible "Synced recording time, 25 October 2026, UTC"}
             (get-in outcome [:synced :context])))
      (is (= ["25 Oct 2026"] (get-in outcome [:synced :dates])))
      (is (str/includes? (get-in outcome [:synced :status])
                         "Timeline labels now show synced recording time"))
      (is (= "01:30:00.000 / 01:32:05.460"
             (get-in outcome [:synced :transport])))
      (is (= "Synced recording time, current 25 October 2026 at 01:30:00.000, UTC (GMT+00:00); source end 25 October 2026 at 01:32:05.460, UTC (GMT+00:00)"
             (get-in outcome [:synced :transportAccessible])))
      (is (= "Synced recording time, 25 October 2026 at 01:30:00.000, UTC (GMT+00:00)"
             (get-in outcome [:synced :timelineText])))
      (is (every? #(re-matches #"\d{2}:\d{2}:\d{2}\.\d{3}" %)
                  (get-in outcome [:synced :ticks])))
      (is (= "manual-anchor" (get-in outcome [:generated :mode])))
      (is (= "2026-10-25T01:30:00.000Z"
             (get-in outcome [:generated :telemetrySyncAt])))
      (is (= "2026-10-25T01:30:00.000Z"
             (get-in outcome [:generated :cameraSyncAt])))
      (is (= {:fileId "drive-source"
              :recordingStartAt "2026-10-25T01:29:59.960Z"
              :timeZone "UTC"}
             (get-in outcome [:generated :sourceVideo])))
      (is (= "Elapsed time" (get-in outcome [:reversed :mode])))
      (is (= {:visual "Elapsed time" :accessible "Elapsed time"}
             (get-in outcome [:reversed :context])))
      (is (= [] (get-in outcome [:reversed :dates])))
      (is (str/includes? (get-in outcome [:reversed :status])
                         "Timeline labels now show elapsed time"))
      (is (= "0.04" (get-in outcome [:reversed :sourceSeconds])))
      (is (true? (get-in outcome [:reversed :submitDisabled])))
      (is (true? (:pointerStartedEmpty outcome)))
      (is (= {:hidden false
              :disabled false
              :value "50.2"
              :text "Elapsed time, 00:00:50.200"}
             (get-in outcome [:pointer :marker])))
      (is (= "50.2" (get-in outcome [:pointer :sourceSeconds])))
      (is (= "00:00:50.200" (get-in outcome [:pointer :sourceText])))
      (is (= 50.2 (get-in outcome [:pointer :videoCurrent])))
      (is (= {:visual
              "Recording time · 08 Mar 2026 · America/New_York"
              :accessible
              "Recording time, 08 March 2026, America/New_York"}
             (get-in outcome [:gap :start :context])))
      (is (= ["08 Mar 2026"] (get-in outcome [:gap :start :dates])))
      (is (= "01:59:59.000 / 03:00:01.000"
             (get-in outcome [:gap :start :transport])))
      (is (= "03:00:00.000 / 03:00:01.000"
             (get-in outcome [:gap :transition :transport])))
      (is (str/ends-with? (get-in outcome [:gap :start :timelineText])
                          "America/New_York (GMT-05:00)"))
      (is (str/ends-with? (get-in outcome [:gap :transition :timelineText])
                          "America/New_York (GMT-04:00)"))
      (is (= {:visual
              "Recording time · 01 Nov 2026 · America/New_York"
              :accessible
              "Recording time, 01 November 2026, America/New_York"}
             (get-in outcome [:repeat :start :context])))
      (is (= ["01 Nov 2026"] (get-in outcome [:repeat :start :dates])))
      (is (= "01:59:59.000 / 01:00:01.000"
             (get-in outcome [:repeat :start :transport])))
      (is (= "01:00:00.000 / 01:00:01.000"
             (get-in outcome [:repeat :transition :transport])))
      (is (str/ends-with? (get-in outcome [:repeat :start :timelineText])
                          "America/New_York (GMT-04:00)"))
      (is (str/ends-with? (get-in outcome [:repeat :transition :timelineText])
                          "America/New_York (GMT-05:00)"))
      (is (= {:visual "Recording time · 17 Jul 2026 · Asia/Kathmandu"
              :accessible
              "Recording time, 17 July 2026, Asia/Kathmandu"}
             (get-in outcome [:kathmandu :start :context])))
      (is (= "14:45:00.000 / 14:45:02.000"
             (get-in outcome [:kathmandu :start :transport])))
      (is (str/ends-with? (get-in outcome [:kathmandu :start :timelineText])
                          "Asia/Kathmandu (GMT+05:45)"))
      (is (true? (:contextOrder outcome)))
      (is (true? (:modeInsideContext outcome)))
      (is (true? (:noHorizontalOverflow outcome))))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest display-time-zone-generates-valid-iana-and-round-trips-raw-json
  (let [outcome
        (display-time-zone-browser-outcome
         (ui/page {:user {:email "member@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= "Europe/Warsaw" (get-in outcome [:preset :selector])))
    (is (= "Europe/Warsaw"
           (get-in outcome [:preset :request :displayTimeZone])))
    (is (= "Pacific/Auckland" (get-in outcome [:custom :selector])))
    (is (true? (get-in outcome [:custom :option])))
    (is (= "Pacific/Auckland"
           (get-in outcome [:custom :request :displayTimeZone])))
    (is (= "Pacific/Auckland"
           (get-in outcome [:custom :regenerated :displayTimeZone])))
    (is (true? (get-in outcome [:custom :absoluteTimestampsPreserved])))
    (is (= (get-in outcome [:local :browserZone])
           (get-in outcome [:local :request :displayTimeZone])))
    (is (str/includes? (get-in outcome [:missing :status])
                       "Request.displayTimeZone is required"))
    (is (str/includes? (get-in outcome [:unknown :status])
                       "Request.displayTimeZone"))
    (is (str/includes? (get-in outcome [:unknown :status])
                       "IANA timezone"))))

(deftest project-json-downloads-round-trips-and-rejects-invalid-envelopes-atomically
  (let [outcome
        (project-json-browser-outcome
         (ui/page {:user {:email "member@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= 1 (get-in outcome [:exported :schemaVersion])))
    (is (= "transparent-overlay" (get-in outcome [:exported :activeRoute])))
    (is (true? (get-in outcome [:exported :hasRenderRequest])))
    (is (= "timestamp,heart_rate\n2026-07-17T10:00:00Z,120\n2026-07-17T10:00:01Z,124\n2026-07-17T10:00:02Z,128\n"
           (get-in outcome [:exported :nestedTelemetry])))
    (is (= "transparent-overlay" (get-in outcome [:applied :route])))
    (is (= "activity-data" (get-in outcome [:applied :currentStep])))
    (is (str/includes? (get-in outcome [:applied :status])
                       "Project JSON applied"))
    (is (= "timestamp,heart_rate\n2026-07-17T09:00:00Z,111"
           (get-in outcome [:applied :telemetry])))
    (is (= "{}" (get-in outcome [:applied :renderRequest])))
    (is (= nil (get-in outcome [:applied :project :renderRequest])))
    (is (= "activity-data"
           (get-in outcome [:applied :project :currentStepId])))
    (is (str/includes? (get-in outcome [:invalid :status])
                       "unknown field extra"))
    (is (true? (get-in outcome [:invalid :preserved])))))

(deftest project-json-drive-source-validation-returns-authoritative-metadata
  (let [port (available-port)
        {:keys [auth-system owner-cookie owner-csrf]} (fixture)
        gateway (reify drive/SourceGateway
                  (source-metadata! [_ access-token file-id]
                    (is (= "drive-access" access-token))
                    (is (= "drive-file" file-id))
                    {:id file-id
                     :name "validated-source.mp4"
                     :mimeType "video/mp4"
                     :size 2048
                     :trashed false})
                  (stream-source! [_ _ _ _]
                    (throw (UnsupportedOperationException.))))
        server (start-api! port {:auth-system (assoc auth-system :drive gateway)})]
    (try
      (let [response (request! port :post "/ui/project-source-validation"
                               (json/write-str {:fileId "drive-file"})
                               {"Cookie" owner-cookie
                                "X-CSRF-Token" owner-csrf
                                "Content-Type" "application/json"})
            body (json/read-str (.body response))]
        (is (= 200 (.statusCode response)))
        (is (= {"fileId" "drive-file"
                "fileName" "validated-source.mp4"
                "mimeType" "video/mp4"}
               body)))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest compose-submit-starts-enabled-with-preview-available
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test" :tokens [] :members []
                       :logs-enabled? false})]
    (is (str/includes? page
                       "id=\"submit-button\" class=\"primary\" type=\"submit\">Create finished video"))
    (is (str/includes? page "id=\"preview-submit-status\""))
    (is (str/includes? page "Preview is optional"))
    (doseq [disclosure ["Each Preview attempt reserves up to PLN 1.25"
                        "Preview plus one Submit reserves up to PLN 2.50"
                        "Reservations remain counted after success, failure, cancellation, or expiry"
                        "Retrying Preview reserves another PLN 1.25"]]
      (is (str/includes? page disclosure)))
    (is (str/includes? page
                       "class=\"button-spinner\" aria-hidden=\"true\" hidden"))
    (is (str/includes?
         page
         "@media(prefers-reduced-motion:reduce){.button-spinner{animation:none}}"))
    (is (str/includes? page "button.primary:disabled:hover"))
    (is (not (str/includes? page "localStorage")))
    (is (not (str/includes? page "sessionStorage")))))

(deftest picker-supports-root-nested-shared-and-upload-video-flow-in-a-browser
  (let [page (ui/page {:user {:email "owner@example.com" :role :member}
                       :csrf "csrf-test"
                       :picker-config {:access-token "access-test"
                                       :api-key "key-test"
                                       :app-id "app-test"
                                       :csrf "csrf-test"}
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(picker-browser-outcome page "1280,900")
                  (picker-browser-outcome page "390,844")]]
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= "Loading Google Drive Picker…" (:initialLoading outcome)))
      (is (= "Google Drive Picker failed to load. Try again."
             (:failureMessage outcome)))
      (is (= "Loading Google Drive Picker…" (:failureRetryLoading outcome)))
      (is (= "Google Drive Picker failed to load. Try again."
             (:timeoutMessage outcome)))
      (is (= "Loading Google Drive Picker…" (:timeoutRetryLoading outcome)))
      (is (= {:selection "Choose a video file" :fileId ""}
             (:rejected outcome)))
      (is (= {:selection "Choose a video file" :fileId ""}
             (:folderRejected outcome)))
      (is (= "video.mp4" (:selected outcome)))
      (let [[normal-drive shared-drives upload] (:views outcome)
            mime-types (str/join "," drive/supported-source-video-mime-types)]
        (is (= {:kind "drive"
                :mimeTypes mime-types
                :includeFolders true
                :selectFolderEnabled false
                :mode "list"}
               normal-drive))
        (is (= (assoc normal-drive :enableDrives true) shared-drives))
        (is (= {:kind "upload" :includeFolders false} upload))
        (is (= mime-types (:selectableMimeTypes outcome)))
        (is (not (contains? normal-drive :ownedByMe))))
      (is (= [false true false true false] (:visible outcome)))
      (is (= ["error" "error" "opened" "loaded" "error" "error"
              "selected" "opened" "cancelled"]
             (mapv :phase (:diagnostics outcome))))
      (is (every? #(= #{:phase :view :listState} (set (keys %)))
                  (:diagnostics outcome)))
      (is (:noHorizontalOverflow outcome) outcome))
    (is (<= 1200 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest preview-remains-stale-safe-and-retriable-without-gating-submit
  (let [outcome (preview-status-browser-outcome
                 (ui/page {:user {:email "owner@example.com" :role :member}
                           :csrf "csrf-test"
                           :tokens []
                           :members []
                           :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= {:submitDisabled true
            :status "Preview is optional. Create the finished video when ready."}
           (select-keys (:initial outcome)
                        [:submitDisabled :status])))
    (is (= {:spinnerHidden true
            :spinnerInside true
            :previewCursor "not-allowed"}
           (select-keys (get-in outcome [:initial :presentation])
                        [:spinnerHidden :spinnerInside :previewCursor])))
    (is (= (get-in outcome [:initial :presentation :previewBackground])
           (get-in outcome [:initial :presentation :submitBackground])))
    (is (= {:text "Preparing preview…"
            :disabled true
            :submitDisabled false
            :cleared true
            :className "preview-pending"}
           (select-keys (:pending outcome)
                        [:text :disabled :submitDisabled
                         :cleared :className])))
    (is (= {:spinnerHidden false
            :spinnerInside true
            :previewCursor "not-allowed"
            :submitCursor "pointer"
            :previewShadow "none"}
           (select-keys (get-in outcome [:pending :presentation])
                        [:spinnerHidden :spinnerInside :previewCursor
                         :submitCursor :previewShadow])))
    (is (not= (get-in outcome [:pending :presentation :previewBackground])
              (get-in outcome [:pending :presentation :submitBackground])))
    (is (not= (get-in outcome [:pending :presentation :submitBackground])
              (get-in outcome [:initial :presentation :submitBackground])))
    (is (:unrelatedIgnored outcome))
    (is (:duplicateSuppressed outcome))
    (is (false? (get-in outcome [:platformFailure :disabled])))
    (is (false? (get-in outcome [:platformFailure :submitDisabled])))
    (is (not (str/includes? (get-in outcome [:platformFailure :text]) "504")))
    (is (str/includes? (get-in outcome [:platformFailure :text])
                       "reservation remains counted"))
    (is (false? (get-in outcome [:gatewayFailure :disabled])))
    (is (not (str/includes? (get-in outcome [:gatewayFailure :text]) "502")))
    (is (= false (get-in outcome [:connectionLoss :disabled])))
    (is (get-in outcome [:connectionLoss :lateRejected]))
    (is (str/includes? (get-in outcome [:connectionLoss :text])
                       "connection was lost"))
    (is (= false (get-in outcome [:clientAbort :disabled])))
    (is (true? (get-in outcome [:clientAbort :presentation :spinnerHidden])))
    (is (str/includes? (get-in outcome [:clientAbort :text]) "cancelled"))
    (is (= false (get-in outcome [:browserTimeout :disabled])))
    (is (true? (get-in outcome [:browserTimeout :presentation :spinnerHidden])))
    (is (str/includes? (get-in outcome [:browserTimeout :text])
                       "did not finish"))
    (is (= "preview-error" (get-in outcome [:terminalFailure :className])))
    (is (false? (get-in outcome [:terminalFailure :previewDisabled])))
    (is (false? (get-in outcome [:terminalFailure :submitDisabled])))
    (is (true? (get-in outcome
                       [:terminalFailure :presentation :spinnerHidden])))
    (is (= "Preview failed. Create finished video remains available."
           (get-in outcome [:terminalFailure :submitStatus])))
    (is (= "Preview failed. See details below."
           (get-in outcome [:terminalFailure :status])))
    (is (str/includes? (get-in outcome [:terminalFailure :text])
                       "Check the selected video and inputs"))
    (doseq [developer-detail ["preview_rendering" "Source content"
                              "worker_failed" "Failure code" "Request ID"]]
      (is (not (str/includes? (get-in outcome [:terminalFailure :text])
                              developer-detail))))
    (is (str/includes? (get-in outcome [:terminalFailure :text])
                       "reservation remains counted"))
    (is (= {:text "Preview ready."
            :disabled false
            :submitDisabled false
            :retried true}
           (select-keys (:succeeded outcome)
                        [:text :disabled :submitDisabled :retried])))
    (is (true? (get-in outcome [:succeeded :presentation :spinnerHidden])))
    (is (= {:firstAllowed true :duplicateSuppressed true}
           (select-keys (:submitFlow outcome)
                        [:firstAllowed :duplicateSuppressed])))
    (is (re-matches #"ui-submit-[0-9a-f-]{36}"
                    (get-in outcome [:submitFlow :idempotencyKey])))
    (is (not (contains? (:submitFlow outcome) :operation)))
    (is (= {:submitDisabled false :className "preview-stale"
            :invalidationWasPending true}
           (select-keys (:rawInvalidated outcome)
                        [:submitDisabled :className
                         :invalidationWasPending])))
    (is (true? (get-in outcome
                       [:rawInvalidated :presentation :spinnerHidden])))))

(deftest accepted-durable-submit-leaves-a-clear-idempotent-state
  (let [outcome
        (durable-submit-browser-outcome
         (ui/page {:user {:email "owner@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (:firstAllowed outcome))
    (is (:duplicateSuppressed outcome))
    (is (:disabled outcome))
    (is (= "true" (:ariaDisabled outcome)))
    (is (= "Creation started. Change any setting to start another finished video."
           (:submitStatus outcome)))
    (is (= "Finished video creation started. Track its progress below."
           (:formStatus outcome)))
    (is (= {:disabled true
            :ariaDisabled "true"
            :submitStatus
            "Finished video created. Change any setting to start another."
            :formStatus "Finished video is ready. Open it below."}
           (:succeeded outcome)))
    (is (= {:disabled true
            :ariaDisabled "true"
            :submitStatus
            "Creation failed. Review the result below, then change any setting to retry."
            :formStatus "Finished video was not created. Review the result below."}
           (:failed outcome)))
    (is (= {:disabled false
            :ariaDisabled "false"
            :submitStatus "Preview is optional."
            :formStatus "Ready to preview or create the finished video."}
           (:oldJobIgnored outcome)))
    (is (= {:disabled true
            :ariaDisabled "true"
            :submitStatus "Creating finished video…"
            :formStatus "Creating finished video…"}
           (:lateOldPoll outcome)))
    (is (= {:disabled true
            :ariaDisabled "true"
            :submitStatus
            "Creation started. Change any setting to start another finished video."
            :formStatus "Finished video creation started. Track its progress below."}
           (:nextAccepted outcome)))
    (is (= {:disabled false
            :ariaDisabled "false"
            :submitStatus
            "Creation failed. Review the error below, then retry Create finished video."
            :formStatus "Finished video was not created. Retry when ready."
            :retryAllowed true
            :sameKey true}
           (:responseError outcome)))
    (is (= {:disabled false
            :ariaDisabled "false"
            :submitStatus
            "Connection lost. Retry Create finished video. Repeating is safe."
            :formStatus "Connection lost. Retry when ready."
            :retryAllowed true
            :sameKey true}
           (:connectionError outcome)))
    (is (= {:disabled false
            :ariaDisabled "false"
            :submitStatus
            "Creation timed out. Retry Create finished video. Repeating is safe."
            :formStatus "Creation timed out. Retry when ready."
            :retryAllowed true
            :sameKey true}
           (:timeout outcome)))
    (is (= {:disabled false
            :ariaDisabled "false"
            :submitStatus
            "Creation cancelled. Retry Create finished video. Repeating is safe."
            :formStatus "Creation cancelled. Retry when ready."
            :retryAllowed true
            :sameKey true}
           (:cancelled outcome)))))

(deftest real-htmx-worker-failure-finishes-preview-in-a-browser
  (let [outcome
        (real-htmx-preview-outcome
         (ui/page {:user {:email "owner@example.com" :role :member}
                   :csrf "csrf-test"
                   :tokens []
                   :members []
                   :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= "2.0.10" (:htmxVersion outcome)) outcome)
    (is (= "preview-error" (:className outcome)) outcome)
    (is (false? (:previewDisabled outcome)) outcome)
    (is (:spinnerHidden outcome) outcome)
    (is (false? (:submitDisabled outcome)) outcome)
    (is (= "Preview failed. Create finished video remains available."
           (:submitStatus outcome))
        outcome)
    (is (= "Preview failed. See details below." (:status outcome)) outcome)
    (is (str/includes? (:text outcome)
                       "reservation remains counted"))
    (doseq [developer-detail ["preview_rendering" "Source content"
                              "worker_failed" "Failure code" "Request ID"]]
      (is (not (str/includes? (:text outcome) developer-detail)) outcome))))

(deftest preview-gallery-is-responsive-accessible-and-stale-safe-in-a-browser
  (let [desktop (preview-gallery-browser-outcome false)
        wrapping (preview-gallery-browser-outcome
                  (wrapping-preview-gallery-operation) :wrapping)
        narrow (preview-gallery-browser-outcome true)]
    (is (nil? (:error desktop)) desktop)
    (is (= "flex" (:display desktop)))
    (is (= ["Final" "Final" "Final"]
           (:roles desktop)))
    (is (= ["Final - Video start - 120 bpm - 00:00.000"
            "Final - Prominent maximum - 168 bpm - 00:01.000"
            "Final - Video start - 97 % - 00:00.000"]
           (:photoTitles desktop)
           (:sequenceTitles desktop)))
    (is (= ["/v1/previews/x/images/a000-final/full"
            "/v1/previews/x/images/a001-final/full"
            "/v1/previews/x/images/a000-final/full"]
           (:sequenceFullUrls desktop)))
    (is (<= 124 (:thumbnailWidth desktop) 132))
    (is (:titlesDoNotOverlap desktop) desktop)
    (is (every? true? (map desktop
                           [:titlePlacement :noOverflow :traceNoOverflow
                            :multipleOnFirstRow :rowsCentered
                            :meaningfulAlts :eagerImages
                            :nativeButtons :thumbnailUrlsOnly :viewerControls
                            :dialogOpened :clickedPosition :modalFocus
                            :focusContained :viewportFit
                            :controlsVisible :accessibleViewer :buttonNavigation
                            :keyboardNavigation :endStates :escapePreserved
                            :focusReturned :stale :staleSwapRejected])))
    (is (nil? (:error wrapping)) wrapping)
    (is (= "flex" (:display wrapping)))
    (is (= 7 (:momentCount wrapping) (:buttonCount wrapping)))
    (is (<= 124 (:thumbnailWidth wrapping) 132))
    (is (every? true? (map wrapping
                           [:multipleOnFirstRow :wrapped :rowsCentered
                            :noOverflow :traceNoOverflow :titlesDoNotOverlap])))
    (let [row-counts (get-in wrapping [:sectionLayouts 0 :rowCounts])]
      (is (> (count row-counts) 1))
      (is (< (last row-counts) (first row-counts))))
    (is (nil? (:error narrow)) narrow)
    (is (= "flex" (:display narrow)))
    (is (= (:roles desktop) (:roles narrow)))
    (is (= (:photoTitles desktop) (:photoTitles narrow)))
    (is (= (:sequenceTitles desktop) (:sequenceTitles narrow)))
    (is (= (:sequenceFullUrls desktop) (:sequenceFullUrls narrow)))
    (is (> (:thumbnailWidth narrow) (:thumbnailWidth desktop)))
    (is (<= (:thumbnailWidth narrow) (:viewportWidth narrow)))
    (is (:titlesDoNotOverlap narrow) narrow)
    (is (every? true? (map narrow
                           [:titlePlacement :noOverflow :traceNoOverflow
                            :wrapped :rowsCentered :meaningfulAlts :eagerImages
                            :nativeButtons :thumbnailUrlsOnly :viewerControls
                            :dialogOpened :clickedPosition :modalFocus
                            :focusContained :viewportFit
                            :controlsVisible :accessibleViewer :buttonNavigation
                            :keyboardNavigation :endStates :escapePreserved
                            :focusReturned])))))

(deftest timer-preview-manifest-reaches-every-thumbnail-and-viewer-position
  (let [operation (timer-preview-gallery-operation)
        moments (get-in operation [:result :sections 0 :moments])
        assets (get-in operation [:result :assets])
        expected-titles
        (mapv (fn [{:keys [title]}] (str "Final - " title)) moments)
        expected-full-urls
        (mapv #(get-in % [:image :fullUrl]) assets)
        outcome (preview-gallery-browser-outcome operation false)]
    (is (nil? (:error outcome)) outcome)
    (is (= 4 (:momentCount outcome)))
    (is (= 4 (:buttonCount outcome)))
    (is (= (vec (repeat 4 "Final")) (:roles outcome)))
    (is (= expected-titles (:photoTitles outcome) (:sequenceTitles outcome)))
    (is (= expected-full-urls (:sequenceFullUrls outcome)))
    (is (= (mapv #(str "Open larger image: " %) expected-titles)
           (:ariaLabels outcome)))
    (is (every? true? (map outcome
                           [:titlePlacement :titlesDoNotOverlap :noOverflow
                            :meaningfulAlts :thumbnailUrlsOnly :dialogOpened
                            :clickedPosition :accessibleViewer :buttonNavigation
                            :keyboardNavigation :endStates :focusReturned])))))

(deftest partial-preview-explains-missing-source-duration-without-developer-traces
  (let [operation-id "00000000-0000-0000-0000-000000000084"
        operation
        (-> (timer-preview-gallery-operation)
            (assoc :id operation-id)
            (update-in [:result :sections 0 :moments] #(vec (take 3 %)))
            (update-in [:result :assets] #(vec (take 3 %)))
            (assoc-in [:result :warnings]
                      [{:reason "source_duration_too_short"
                        :requestId operation-id
                        :requestedMomentCount 4
                        :generatedMomentCount 3
                        :omittedMomentCount 1
                        :requestedDurationSeconds 26/25
                        :retryable false}]))
        fragment (ui/preview-operation-fragment operation "generation-1")]
    (is (str/includes?
         fragment
         (str "We generated 3 of 4 preview frames. The selected video ends "
              "before the 1.04-second section, so 1 later preview frame is "
              "unavailable. Shorten the section or choose a longer video.")))
    (is (= 3 (count (re-seq #"class=\"preview-moment\"" fragment))))
    (is (= 3 (count (re-seq #"class=\"preview-open\"" fragment))))
    (doseq [developer-detail
            ["source_duration_too_short" "Failure code" "Category"
             "Request ID" "Stage" "Elapsed" "Retryable" "stack trace"
             "FFmpeg"]]
      (is (not (str/includes? fragment developer-detail)) developer-detail))
    (is (= 1 (count (re-seq (re-pattern operation-id) fragment))))))

(deftest zero-frame-preview-gives-actionable-copy-without-images-or-traces
  (let [operation-id "00000000-0000-0000-0000-000000000085"
        fragment
        (ui/preview-operation-fragment
         {:id operation-id
          :state "failed"
          :progressPercent 100
          :error {:code "composition_encode_failed"
                  :category "preview_rendering"
                  :requestId operation-id
                  :stage "composition_encode"
                  :reason "source_duration_too_short"
                  :requestedMomentCount 4
                  :generatedMomentCount 0
                  :omittedMomentCount 4
                  :requestedDurationSeconds 20
                  :elapsedMs 917
                  :retryable false}}
         "generation-1")]
    (is (str/includes?
         fragment
         (str "We could not generate any of the 4 preview frames. The selected "
              "video ends before the 20-second section. Shorten the section or "
              "choose a longer video.")))
    (is (not (str/includes? fragment "<img")))
    (is (not (str/includes? fragment "/images/")))
    (doseq [developer-detail
            ["composition_encode_failed" "preview_rendering"
             "source_duration_too_short" "Composition encode" "917 ms"
             "Failure code" "Category" "Request ID" "Stage" "Elapsed"
             "Retryable" "stack trace" "FFmpeg"]]
      (is (not (str/includes? fragment developer-detail)) developer-detail))
    (is (= 1 (count (re-seq (re-pattern operation-id) fragment))))))

(deftest overlay-only-hr-and-spo2-gallery-has-trace-sections-and-overlay-row
  (let [operation (preview-gallery-operation)
        moment (get-in operation [:result :sections 0 :moments 0])
        operation (-> operation
                      (assoc-in [:result :mode] "overlay")
                      (assoc-in [:result :sections]
                                [{:id "heart-rate" :name "Heart rate"
                                  :unit "bpm" :moments [moment]}
                                 {:id "spo2" :name "SpO2" :unit "%"
                                  :moments [(assoc moment
                                                   :value 97.0
                                                   :title "Video start - 97 % - 00:01.000")]}])
                      (assoc-in [:result :assets]
                                [{:id "a000" :frameIndex 25 :kind "overlay"
                                  :image {:thumbnailUrl "/v1/previews/x/images/a000-overlay/thumbnail"
                                          :fullUrl "/v1/previews/x/images/a000-overlay/full"}}]))
        fragment (ui/preview-operation-fragment operation "generation-1")]
    (is (= 2 (count (re-seq #"class=\"trace-preview\"" fragment))))
    (is (str/includes? fragment ">Heart rate</h2>"))
    (is (str/includes? fragment ">SpO2</h2>"))
    (is (= 2 (count (re-seq #">Overlay</span>" fragment))))
    (is (= 2 (count (re-seq #"class=\"preview-open\"" fragment))))
    (is (= 2 (count (re-seq
                     #"data-full=\"/v1/previews/x/images/a000-overlay/full\""
                     fragment))))
    (is (str/includes?
         fragment
         "data-title=\"Overlay - Video start - 120 bpm - 00:00.000\""))
    (is (str/includes?
         fragment
         "data-title=\"Overlay - Video start - 97 % - 00:01.000\""))
    (is (= 2 (count (re-seq #"class=\"checkerboard\"" fragment))))
    (is (not (str/includes? fragment ">Source</span>")))
    (is (not (str/includes? fragment ">Final</span>")))))

(deftest terminal-preview-fragments-retain-operation-identity
  (let [base {:id "00000000-0000-0000-0000-000000000061"
              :progressPercent 100}
        cancelled (ui/preview-operation-fragment
                   (assoc base :state "cancelled"
                          :error {:code "preview_cancelled" :retryable false})
                   "generation-1")
        failed (ui/preview-operation-fragment
                (assoc base :state "failed"
                       :error {:code "preview_timeout"
                               :category "preview_rendering"
                               :requestId (:id base)
                               :retryable true
                               :stage "composition_encode"
                               :status 504
                               :elapsedMs 45004
                               :timeoutMs 45000})
                "generation-1")
        empty (ui/preview-operation-fragment
               (assoc base :state "succeeded"
                      :result {:sections [] :assets []})
               "generation-1")]
    (doseq [fragment [cancelled failed empty]]
      (is (str/includes? fragment
                         "data-preview-operation=\"00000000-0000-0000-0000-000000000061\"")))
    (is (str/includes? cancelled "<h2>Preview cancelled</h2>"))
    (is (not (str/includes? cancelled "hx-get=")))
    (is (not (str/includes? cancelled "preview_cancelled")))
    (doseq [detail ["preview_timeout" "preview_rendering"
                    "Composition encode" "504" "45004 ms" "45000 ms"
                    "Failure code" "Request ID" "Retryable"]]
      (is (not (str/includes? failed detail))))
    (is (str/includes? failed "Preview did not finish"))
    (is (str/includes? failed
                       "reservation remains counted"))
    (is (str/includes? failed "retry with the Preview button"))
    (is (str/includes? empty "No preview moments"))))

(deftest activity-files-are-detected-locally-with-advanced-oxiwear-routing
  (let [page (ui/page {:user {:email "owner@example.com" :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        outcomes [(telemetry-file-browser-outcome page "1280,900")
                  (telemetry-file-browser-outcome page "390,844")]]
    (is (str/includes?
         page
         (str "Upload a FIT or CSV file. Alpha Compose detects Garmin FIT "
              "or Polar CSV automatically.")))
    (is (not (str/includes? page "<select id=\"telemetry-format\"")))
    (is (not (str/includes? page "<textarea id=\"telemetry\"")))
    (doseq [fragment ["id=\"telemetry-file\""
                      "accept=\".fit,.csv"
                      "id=\"advanced-activity-data\""
                      "id=\"advanced-telemetry-format\""
                      "id=\"advanced-telemetry-file\""
                      "id=\"open-advanced-activity-data\""]]
      (is (str/includes? page fragment) fragment))
    (doseq [outcome outcomes]
      (is (nil? (:error outcome)) outcome)
      (is (= "activity-data" (:activityStep outcome)))
      (is (= "garmin-fit" (get-in outcome [:fit :format])))
      (is (true? (get-in outcome [:fit :matches])))
      (is (true? (get-in outcome [:fit :success])))
      (is (true? (get-in outcome [:fit :formValid])))
      (is (empty? (get-in outcome [:fit :invalidIds])))
      (is (str/includes? (get-in outcome [:fit :status])
                         "Detected Garmin FIT"))
      (doseq [result [:polar :alternate]]
        (is (= "polar-csv" (get-in outcome [result :format])))
        (is (true? (get-in outcome [result :matches])))
        (is (true? (get-in outcome [result :success])))
        (is (str/includes? (get-in outcome [result :status])
                           "Detected Polar CSV")))
      (is (true? (get-in outcome [:polar :clearedImmediately])))
      (is (= {:clearedImmediately true
              :format ""
              :content ""
              :status ""
              :success false
              :error false
              :inputFiles 0}
             (:cancelled outcome)))
      (is (true? (get-in outcome [:oxiwear :error])))
      (is (str/includes? (get-in outcome [:oxiwear :status])
                         "OxiWear heart-rate CSV"))
      (is (true? (get-in outcome [:oxiwear :routeVisible])))
      (is (true? (get-in outcome [:oxiwear :advancedOpen])))
      (is (true? (get-in outcome [:oxiwear :focused])))
      (is (= "oxiwear-hr-csv"
             (get-in outcome [:advancedUpload :format])))
      (is (= "oxiwear-hr-csv"
             (get-in outcome [:advancedUpload :advancedFormat])))
      (is (true? (get-in outcome [:advancedUpload :matches])))
      (is (true? (get-in outcome [:advancedUpload :success])))
      (doseq [[result guidance]
              [[:summary "activity-list summary"]
               [:ambiguous "matches more than one"]
               [:wrongExtension "Choose a .fit or .csv file"]
               [:malformedCsv "row 2"]
               [:malformedFit "not a compatible Garmin FIT"]
               [:oversized "10 MiB"]
               [:readFailure "Could not read"]]]
        (is (true? (get-in outcome [result :error])) result)
        (is (str/includes? (get-in outcome [result :status]) guidance)
            result)
        (is (= "" (get-in outcome [result :content])) result))
      (is (false? (get-in outcome [:malformedCsv :privateContentLeaked])))
      (is (false? (get-in outcome [:readFailure :privateContentLeaked])))
      (is (every? #(str/includes? % "Heart-rate")
                  (vals (:labels outcome))))
      (is (= "Advanced activity data" (:advancedSummary outcome)))
      (is (true? (:noHorizontalOverflow outcome)))
      (is (true? (:panelFits outcome))))
    (is (= 1280 (:viewportWidth (first outcomes))))
    (is (<= (:viewportWidth (second outcomes)) 500))))

(deftest htmx-preview-failure-is-a-safe-correlated-html-fragment
  (let [port (available-port)
        {:keys [auth-system owner-cookie owner-csrf]} (fixture)
        server (start-api! port {:auth-system auth-system})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})
        request (assoc (fixture/render-request)
                       :telemetry
                       (str "timestamp,heart_rate\n"
                            "2026-07-17T10:00:00Z,19\n"))]
    (try
      (let [response (request! port :post "/ui/preview"
                               (form {:request (json/write-str request)})
                               headers)
            body (.body response)
            request-id (some-> response .headers (.firstValue "x-request-id")
                               (.orElse nil))]
        (is (= 200 (.statusCode response)))
        (is (= "text/html; charset=utf-8"
               (.orElse (.firstValue (.headers response) "content-type") nil)))
        (is (re-matches #"[0-9a-f-]{36}" request-id))
        (is (str/includes? body "<article id=\"preview-result\""))
        (is (str/includes? body "Preview failed"))
        (is (str/includes? body "Heart-rate data"))
        (is (str/includes? body "between 20 and 260 bpm"))
        (doseq [developer-detail
                ["request_contract" "heart_rate_out_of_range" request-id
                 "Source line" "Failure code" "Category" "Request ID"
                 "Stage" "Elapsed" "Retryable" "stack trace" "FFmpeg"]]
          (is (not (str/includes? body developer-detail)) developer-detail))
        (is (not (str/includes? body "{\"error\":\"invalid_request\"}")))
        (is (not (str/includes? body "timestamp,heart_rate")))
        (is (not (str/includes? body ",19")))
        (is (not (str/includes? body "2026-07-17"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest telemetry-preview-failures-identify-the-input-and-safe-correction
  (let [base {:category "request_contract"
              :request-id "00000000-0000-0000-0000-000000000062"
              :status 400
              :retryable false
              :field "telemetry"
              :documentationPath
              "/openapi.yaml#/components/schemas/RenderRequest"}
        summary (ui/preview-failure-fragment
                 (assoc base
                        :failureCode "unsupported_telemetry_columns"
                        :expectedSchema
                        {:timestampColumns ["timestamp" "date/time" "datetime"]
                         :valueColumns ["heart_rate" "heart rate"
                                        "heart rate (bpm)" "HR" "HR (bpm)"]}))
        corrections
        {"malformed_telemetry_row" "Correct the malformed row"
         "heart_rate_out_of_range" "between 20 and 260 bpm"
         "unordered_telemetry" "strictly increasing"
         "insufficient_telemetry_coverage" "cover the full requested section"
         "telemetry_too_large" "documented size limit"
         "telemetry_sample_limit_exceeded" "fewer activity-data samples"
         "unsupported_telemetry_format" "Polar CSV, Garmin FIT, or OxiWear"
         "unknown_failure" "Review the activity-data input"}]
    (is (str/includes? summary "Heart-rate data"))
    (is (str/includes? summary "timestamped Polar CSV"))
    (doseq [column ["timestamp" "date/time" "datetime" "heart_rate"
                    "heart rate" "heart rate (bpm)" "HR" "HR (bpm)"]]
      (is (str/includes? summary column)))
    (is (str/includes? summary "href=\"/openapi.yaml"))
    (doseq [[failure-code correction] corrections]
      (is (str/includes?
           (ui/preview-failure-fragment
            (assoc base :failureCode failure-code :line 7))
           correction)
          failure-code))
    (let [spo2 (ui/preview-failure-fragment
                (assoc base :field "spo2.telemetry"
                       :failureCode "malformed_telemetry_row"))]
      (is (str/includes? spo2 "Oxygen-saturation data (SpO2)")))))

(deftest site-icon-assets-are-served-and-linked
  (let [port (available-port)
        {:keys [auth-system]} (fixture)
        server (start-api! port {:auth-system auth-system})]
    (try
      (let [homepage (request! port :get "/" nil {})
            svg (test-http/send-string! :get
                                        (str "http://127.0.0.1:" port "/favicon.svg")
                                        nil
                                        {})
            png (test-http/send-bytes! :get
                                       (str "http://127.0.0.1:" port "/favicon-32.png")
                                       nil
                                       {})
            background (test-http/send-bytes!
                        :get
                        (str "http://127.0.0.1:" port
                             "/telemetry-background.webp")
                        nil
                        {})]
        (is (= 200 (.statusCode homepage)))
        (is (str/includes? (.body homepage) "href=\"/favicon.svg\""))
        (is (str/includes? (.body homepage) "href=\"/apple-touch-icon.png\""))
        (is (str/includes? (.orElse (.firstValue (.headers homepage)
                                                 "Content-Security-Policy")
                                    nil)
                           "img-src 'self' data:"))
        (is (= 200 (.statusCode svg)))
        (is (= "image/svg+xml; charset=utf-8"
               (.orElse (.firstValue (.headers svg) "Content-Type") nil)))
        (is (= "public, max-age=86400, immutable"
               (.orElse (.firstValue (.headers svg) "Cache-Control") nil)))
        (is (str/includes? (.body svg) "#4374C5"))
        (is (= 200 (.statusCode png)))
        (is (= "image/png"
               (.orElse (.firstValue (.headers png) "Content-Type") nil)))
        (is (= [-119 80 78 71 13 10 26 10]
               (mapv int (take 8 (.body png)))))
        (is (= 200 (.statusCode background)))
        (is (= "image/webp"
               (.orElse (.firstValue (.headers background) "Content-Type") nil)))
        (is (= "RIFF" (apply str (map char (take 4 (.body background))))))
        (is (= "WEBP" (apply str (map char (take 4 (drop 8 (.body background))))))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest openapi-contract-is-served-as-a-public-read-only-asset
  (let [port (available-port)
        server (start-api! port)]
    (try
      (let [response (test-http/send-string! :get
                                             (str "http://127.0.0.1:" port
                                                  "/openapi.yaml")
                                             nil
                                             {})
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (= "application/yaml; charset=utf-8"
               (.orElse (.firstValue (.headers response) "Content-Type") nil)))
        (is (= "public, max-age=86400, immutable"
               (.orElse (.firstValue (.headers response) "Cache-Control") nil)))
        (is (= (slurp "docs/openapi.yaml") body))
        (is (str/includes? body "openapi: 3.1.0"))
        (doseq [contract ["files shared with the user"
                          "Shared Drives"
                          "folders remain visible for navigation"
                          "video/x-matroska"
                          "Project JSON"
                          "completed-output playback"
                          "downloadable, decodable"]]
          (is (str/includes? body contract) contract))
        (is (not (str/includes? body "2 GiB")))
        (is (not (str/includes? body "client_secret"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest htmx-owner-workflow-previews-submits-polls-cancels-and-retries
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        {:keys [auth-system owner-cookie owner-csrf member-cookie]} (fixture)
        server (start-api! port {:job-service (:service lifecycle)
                                 :auth-system auth-system
                                 :token-service (:service token-system)})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})
        request-json (json/write-str (fixture/render-request))]
    (try
      (let [landing (request! port :get "/" nil {"Cookie" owner-cookie})]
        (is (= 200 (.statusCode landing)))
        (is (str/includes? (.body landing) "htmx.org@2.0.10"))
        (is (str/includes? (.body landing) "hx-post=\"/ui/preview\""))
        (is (str/includes? (.body landing) "hx-post=\"/ui/jobs\""))
        (is (str/includes? (.body landing) "id=\"telemetry-format\""))
        (is (str/includes? (.body landing) "type=\"datetime-local\""))
        (is (str/includes? (.body landing) "type=\"file\""))
        (is (str/includes? (.body landing) "Apply JSON to form"))
        (is (str/includes? (.body landing) "Accepted fields"))
        (is (str/includes? (.body landing) "sourceVideo"))
        (is (str/includes? (.body landing) "validateRequest"))
        (is (str/includes? (.body landing) "contains unknown field"))
        (is (str/includes? (.body landing) "id=\"raw-json\""))
        (is (str/includes? (.body landing) "const fileBackedValues=Object.create(null)"))
        (is (str/includes? (.body landing) "function contentValue(id)"))
        (is (str/includes? (.body landing) "event.target.type==='file'"))
        (is (str/includes? (.body landing) "setFileBackedValue(targetId"))
        (is (str/includes? (.body landing) "function openPicker()"))
        (is (not (str/includes? (.body landing) "localStorage")))
        (is (not (str/includes? (.body landing) "sessionStorage")))
        (is (not (str/includes? (.body landing)
                                "window.open('/v1/drive/picker'")))
        (is (not (str/includes? (.body landing)
                                "addEventListener('message'")))
        (is (str/includes? (.body landing) "href=\"/ui/tokens\""))
        (is (not (str/includes? (.body landing) "hx-post=\"/ui/tokens\"")))
        (is (str/includes? (.body landing) "X-CSRF-Token")))
      (testing "preview submission returns an async HTML fragment"
        (let [preview (request! port :post "/ui/preview"
                                (form {:request request-json}) headers)
              operation-id (second
                            (re-find #"data-preview-operation=\"([^\"]+)\""
                                     (.body preview)))
              status-path (str "/ui/previews/" operation-id)]
          (is (= 202 (.statusCode preview)))
          (is (string? operation-id))
          (is (str/includes? (.body preview) "Preparing preview"))
          (is (str/includes? (.body preview) status-path))
          (is (= 200 (.statusCode
                      (request! port :get status-path nil
                                {"Cookie" owner-cookie}))))
          (is (= 404 (.statusCode
                      (request! port :get status-path nil
                                {"Cookie" member-cookie}))))))
      (testing "missing CSRF is rejected before submission"
        (is (= 403
               (.statusCode
                (request! port :post "/ui/jobs"
                          (form {:request request-json})
                          (merge form-content-type {"Cookie" owner-cookie}))))))
      (let [submission (request! port :post "/ui/jobs"
                                 (form {:request request-json}) headers)
            job-id (second (re-find #"id=\"job-([^\"]+)\"" (.body submission)))
            status-path (str "/ui/jobs/" job-id)]
        (is (= 202 (.statusCode submission)))
        (is (string? job-id))
        (is (str/includes? (.body submission)
                           (str "hx-get=\"" status-path "\"")))
        (is (str/includes? (.body submission) "Finished video queued"))
        (is (= 200 (.statusCode
                    (request! port :get status-path nil {"Cookie" owner-cookie}))))
        (is (= 404 (.statusCode
                    (request! port :get status-path nil {"Cookie" member-cookie}))))
        (let [cancelled (request! port :post (str status-path "/cancel") "" headers)]
          (is (= 200 (.statusCode cancelled)))
          (is (str/includes? (.body cancelled) "Finished video cancelled"))
          (is (str/includes? (.body cancelled)
                             (str "hx-post=\"" status-path "/retry\""))))
        (let [retried (request! port :post (str status-path "/retry") "" headers)]
          (is (= 202 (.statusCode retried)))
          (is (str/includes? (.body retried) "Finished video queued"))
          (is (not (str/includes? (.body retried) "Attempt 2")))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest htmx-token-secret-is-shown-once-and-user-content-is-encoded
  (let [port (available-port)
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        {:keys [auth-system owner-cookie owner-csrf]} (fixture)
        server (start-api! port {:job-service (:service lifecycle)
                                 :auth-system auth-system
                                 :token-service (:service token-system)})
        headers (merge form-content-type
                       {"Cookie" owner-cookie "X-CSRF-Token" owner-csrf})]
    (try
      (let [created (request! port :post "/ui/tokens"
                              (form {:name "<script>alert(1)</script>"})
                              headers)
            raw-token (second (re-find #"<code>(agg_pat_[^<]+)</code>"
                                       (.body created)))
            listed (request! port :get "/ui/tokens" nil
                             {"Cookie" owner-cookie})]
        (is (= 201 (.statusCode created)))
        (is (= "no-store"
               (.orElse (.firstValue (.headers created) "Cache-Control") nil)))
        (is (string? raw-token))
        (is (str/includes? (.body created) "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (not (str/includes? (.body created) "<script>alert(1)</script>")))
        (is (= 200 (.statusCode listed)))
        (is (str/includes? (.body listed) "<header class=\"product-header\">"))
        (is (str/includes? (.body listed) "Signed in as owner@example.com"))
        (is (str/includes? (.body listed) "htmx.org@2.0.10"))
        (is (str/includes? (.body listed) "hx-post=\"/ui/tokens\""))
        (is (str/includes? (.body listed) "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (not (str/includes? (.body listed) raw-token)))
        (is (not (str/includes? (.body listed) "hash"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
