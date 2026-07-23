(ns agg.api-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
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
  [requirement location virtual-time-budget browser-args]
  (let [chrome (chrome-executable)]
    (is chrome requirement)
    (when chrome
      (let [command (into [chrome "--headless=new" "--disable-gpu"
                           "--no-sandbox" "--dump-dom"
                           (str "--virtual-time-budget=" virtual-time-budget)]
                          (concat browser-args [(str location)]))
            builder (doto (ProcessBuilder. ^java.util.List command)
                      (.redirectErrorStream true))
            process (.start builder)
            output (slurp (.getInputStream process))
            exit (.waitFor process)
            encoded (second (re-find #"data-outcome=\"([^\"]+)\"" output))]
        (is (= 0 exit) output)
        (is encoded output)
        (when encoded
          (json/read-str
           (String. (.decode (Base64/getDecoder) ^String encoded)
                    StandardCharsets/UTF_8)
           :key-fn keyword))))))

(defn- browser-outcome [prefix requirement html & browser-args]
  (let [temp (File/createTempFile prefix ".html")]
    (try
      (spit temp html)
      (browser-location-outcome requirement (.toURI temp) 1000 browser-args)
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
         "document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
         "button.click();const deadline=Date.now()+3500;function inspect(){const result=document.getElementById('preview-result'),finished=result?.classList.contains('preview-error')&&!button.disabled;if(finished||Date.now()>=deadline){recordOutcome({htmxVersion:window.htmx?.version||null,className:result?.className||'',text:result?.textContent||'',previewDisabled:button.disabled,spinnerHidden:spinner.hidden,submitDisabled:submit.disabled,submitStatus:document.getElementById('preview-submit-status').textContent,status:document.getElementById('form-status').textContent,events});return;}setTimeout(inspect,25);}setTimeout(inspect,25);"
         "}catch(error){recordOutcome({error:error.message});}},{once:true});"
         "</script>")
        html
        (-> page
            (str/replace
             #"<script src=\"https://cdn\.jsdelivr\.net/npm/htmx\.org@2\.0\.10/dist/htmx\.min\.js\"[^>]*></script>"
             "<script src=\"/htmx.min.js\"></script>")
            (str/replace "</body>" (str scenario "</body>")))
        port (available-port)
        server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
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
         "window.fetch=(_path,options)=>{window.__pickerState.diagnostics.push(JSON.parse(options.body));return Promise.resolve({ok:true});};"
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
         "document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
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
         "document.getElementById('telemetry').value='timestamp,heart_rate\\n2026-07-17T10:00:00Z,120';document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:01']].forEach(([id,value])=>document.getElementById(id).value=value);"
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

(defn- telemetry-file-browser-outcome [page]
  (let [fit-base64 (str/trim
                    (slurp (io/resource "fixtures/garmin/activity.fit.b64")))
        csv-text (str/trim (slurp (io/resource "fixtures/polar/valid.csv")))
        second-csv-text (str "timestamp,heart_rate\n"
                             "2026-07-17T10:00:00Z,130\n"
                             "2026-07-17T10:00:02Z,132")
        oxiwear-text (str/trim
                      (slurp (io/resource "fixtures/oxiwear/hr-midnight.csv")))
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "(async()=>{let outcome;try{"
         "const format=document.getElementById('telemetry-format'),input=document.getElementById('telemetry-file'),telemetry=document.getElementById('telemetry'),raw=document.getElementById('raw-json'),status=document.getElementById('telemetry-status');"
         "document.getElementById('timezone').value='UTC';[['telemetry-sync-at','2026-07-17T10:00:00'],['camera-sync-at','2026-07-17T10:00:00'],['section-start-at','2026-07-17T10:00:00'],['section-end-at','2026-07-17T10:00:02']].forEach(([id,value])=>document.getElementById(id).value=value);"
         "const upload=async file=>{const transfer=new DataTransfer();transfer.items.add(file);input.files=transfer.files;input.dispatchEvent(new Event('change',{bubbles:true}));const cleared=telemetry.value==='';await new Promise((resolve,reject)=>{const deadline=Date.now()+1000;const check=()=>{if(status.classList.contains('success'))resolve();else if(status.classList.contains('error')||Date.now()>deadline)reject(new Error('file read failed'));else setTimeout(check,5);};check();});return {request:JSON.parse(raw.value),cleared};};"
         "const selectFile=async(value,file)=>{format.value=value;format.dispatchEvent(new Event('change',{bubbles:true}));return upload(file);};"
         "const fitBytes=Uint8Array.from(atob(" (json/write-str fit-base64) "),character=>character.charCodeAt(0));"
         "const fitUpload=await selectFile('garmin-fit',new File([fitBytes],'input.fit',{type:'application/octet-stream'}));"
         "format.value='polar-csv';format.dispatchEvent(new Event('change',{bubbles:true}));"
         "const clearedOnFormatChange=telemetry.value===''&&input.files.length===0;"
         "const csvUpload=await selectFile('polar-csv',new File([" (json/write-str csv-text) "],'input.csv',{type:'text/csv'}));"
         "const secondCsvUpload=await upload(new File([" (json/write-str second-csv-text) "],'replacement.csv',{type:'text/csv'}));"
         "const oxiwearUpload=await selectFile('oxiwear-hr-csv',new File([" (json/write-str oxiwear-text) "],'input.csv',{type:'text/csv'}));"
         "outcome={fitMatches:fitUpload.request.telemetry===" (json/write-str fit-base64) ",fitFormat:fitUpload.request.telemetryFormat,csvMatches:csvUpload.request.telemetry===" (json/write-str csv-text) ",csvFormat:csvUpload.request.telemetryFormat,clearedOnFormatChange,clearedOnFileChange:secondCsvUpload.cleared,secondCsvMatches:secondCsvUpload.request.telemetry===" (json/write-str second-csv-text) ",oxiwearMatches:oxiwearUpload.request.telemetry===" (json/write-str oxiwear-text) ",oxiwearFormat:oxiwearUpload.request.telemetryFormat};"
         "}catch(_error){outcome={error:'browser telemetry upload scenario failed'};}"
         "const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));})();"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-telemetry-file-browser-"
     "Browser-level telemetry file regression requires Chrome or Chromium"
     html)))

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
  (let [base (assoc (fixture/render-request)
                    :displayTimeZone "Europe/Warsaw")
        scenario
        (str
         "<pre id=\"browser-result\">pending</pre><script>"
         "let outcome;try{const base=" (json/write-str base) ",raw=document.getElementById('raw-json'),apply=document.getElementById('apply-json'),selector=document.getElementById('timezone'),hidden=document.getElementById('render-request'),jsonStatus=document.getElementById('json-status');function applyRequest(request){raw.value=JSON.stringify(request);apply.click();return {selector:selector.value,option:[...selector.options].some(option=>option.value===request.displayTimeZone),status:jsonStatus.textContent,request:JSON.parse(hidden.value)}}const preset=applyRequest({...base,displayTimeZone:'Europe/Warsaw'}),custom=applyRequest({...base,displayTimeZone:'Pacific/Auckland'});document.getElementById('section-start-at').dispatchEvent(new Event('input',{bubbles:true}));custom.regenerated=JSON.parse(hidden.value);custom.absoluteTimestampsPreserved=['telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt'].every(key=>Date.parse(custom.regenerated[key])===Date.parse(base[key]));selector.value='local';selector.dispatchEvent(new Event('input',{bubbles:true}));const local={browserZone:Intl.DateTimeFormat().resolvedOptions().timeZone,request:JSON.parse(hidden.value)};const missingRequest={...base};delete missingRequest.displayTimeZone;const missing=applyRequest(missingRequest);const unknown=applyRequest({...base,displayTimeZone:'Private/Unknown-Zone'});outcome={preset,custom,local,missing,unknown};}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
         "</script>")
        html (-> page
                 (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" "")
                 (str/replace "</body>" (str scenario "</body>")))]
    (browser-outcome
     "agg-display-time-zone-browser-"
     "Browser-level display timezone regression requires Chrome or Chromium"
     html)))

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
         "const links=[...document.querySelectorAll('.contextual-help')],styles=[...document.querySelectorAll('style')].map(node=>node.textContent).join(''),declaredFocus=styles.includes(':focus,:focus-visible{outline:3px solid var(--color-warning)');"
         "const presentations=links.map(link=>{link.focus({focusVisible:true});const rect=link.getBoundingClientRect(),style=getComputedStyle(link),wrapper=link.closest('.help-heading,.help-label,.toggle-help'),wrapperRect=wrapper?.getBoundingClientRect(),associated=wrapper?.querySelector(':scope>h1,:scope>h2,:scope>h3,:scope>label,:scope>strong,:scope>.toggle'),associatedRect=associated?.getBoundingClientRect(),mark=link.querySelector('.contextual-help-mark'),markRect=mark?.getBoundingClientRect(),centerDelta=markRect&&associatedRect?Math.abs((markRect.top+markRect.bottom-associatedRect.top-associatedRect.bottom)/2):null,computedFocus=style.outlineStyle!=='none'&&parseFloat(style.outlineWidth)>=3,overlapsSibling=wrapper?[...wrapper.children].some(node=>{if(node===link)return false;const siblingRect=node.getBoundingClientRect();return rect.left<siblingRect.right&&rect.right>siblingRect.left&&rect.top<siblingRect.bottom&&rect.bottom>siblingRect.top;}):true;return {href:link.getAttribute('href'),name:link.getAttribute('aria-label'),target:link.getAttribute('target'),text:link.textContent.trim(),symbolHidden:link.querySelector('[aria-hidden=\"true\"]')?.textContent==='?',width:rect.width,height:rect.height,markWidth:markRect?.width??null,markHeight:markRect?.height??null,associatedWidth:associatedRect?.width??null,associatedHeight:associatedRect?.height??null,wrapperWidth:wrapperRect?.width??null,wrapperContained:!!wrapper&&wrapper.scrollWidth<=wrapper.clientWidth+.5,associatedFontSize:associated?parseFloat(getComputedStyle(associated).fontSize):null,centerDelta,aligned:centerDelta!==null&&centerDelta<=1,fits:rect.left>=-.5&&rect.right<=window.innerWidth+.5,visible:style.display!=='none'&&style.visibility!=='hidden',keyboardReachable:link.tabIndex>=0,focusVisible:computedFocus||declaredFocus,associated:!!associated,overlapsSibling,insideLabel:!!link.closest('label')};});"
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
         "document.addEventListener('DOMContentLoaded',()=>{"
         "const snapshot=id=>{const target=document.getElementById(id),rect=target?.getBoundingClientRect();return {id,hash:location.hash,open:target?.open??null,inView:!!rect&&rect.bottom>0&&rect.top<window.innerHeight,top:rect?.top??null};};"
         "const record=outcome=>{if(document.getElementById('browser-result').dataset.outcome)return;const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));};"
         "setTimeout(()=>{const initial=snapshot('" initial-fragment "'),changedFragment='source-and-activity-data-retention';location.hash='#'+changedFragment;"
         "setTimeout(()=>{const changed=snapshot(changedFragment),initialStillOpen=document.getElementById('" initial-fragment "').open,summaryNodes=[...document.querySelectorAll('.faq-question>summary')],details=[...document.querySelectorAll('.faq-question')],permalinks=[...document.querySelectorAll('.faq-permalink a')],activeNavNode=document.querySelector('nav a[aria-current=\"page\"]'),activeNavStyle=activeNavNode?getComputedStyle(activeNavNode):null,base={initial,changed,initialStillOpen,summaryCount:summaryNodes.length,summariesKeyboardReachable:summaryNodes.every(node=>node.tabIndex>=0&&node.textContent.trim()),permalinksAccessible:permalinks.length===summaryNodes.length&&permalinks.every(link=>link.getAttribute('aria-label')?.includes(link.closest('details').querySelector('summary').textContent.trim())&&link.getAttribute('href')==='#'+link.closest('details').id),nestedDetails:document.querySelectorAll('details details').length,activeNav:activeNavNode?.getAttribute('href')||null,activeNavStyled:!!activeNavStyle&&parseInt(activeNavStyle.fontWeight,10)>=700&&activeNavStyle.textDecorationLine.includes('underline'),viewportWidth:window.innerWidth,noHorizontalOverflow:document.documentElement.scrollWidth<=window.innerWidth,detailsFit:details.every(node=>{const rect=node.getBoundingClientRect();return rect.left>=-.5&&rect.right<=window.innerWidth+.5;})};"
         "let recorded=false;const finish=()=>{if(recorded)return;recorded=true;record({...base,back:snapshot('" initial-fragment "'),changedStillOpen:document.getElementById(changedFragment).open});};window.addEventListener('hashchange',()=>setTimeout(finish,50),{once:true});history.back();setTimeout(finish,250);},80);},80);"
         "},{once:true});"
         "</script>")
        html (str/replace page "</body>" (str scenario "</body>"))
        temp (File/createTempFile "agg-faq-browser-" ".html")]
    (try
      (spit temp html)
      (browser-location-outcome
       "FAQ fragment and responsive behavior requires Chrome or Chromium"
       (str (.toURI temp) "#" initial-fragment)
       1500
       [(str "--window-size=" window-size)])
      (finally
        (.delete temp)))))

(deftest signed-in-compose-page-keeps-product-navigation-and-account-controls
  (let [page (ui/page {:user {:email "owner@example.com" :role :owner}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? true})
        faq-position (str/index-of page "href=\"/faq\"")
        privacy-position (str/index-of page "href=\"/privacy\"")
        terms-position (str/index-of page "href=\"/terms\"")]
    (is (= 1 (count (re-seq #"class=\"product-header\"" page))))
    (is (str/includes? page
                       "<a class=\"brand\" href=\"/\">Alpha Compose</a>"))
    (is (str/includes? page "<nav aria-label=\"Product\">"))
    (is (every? some? [faq-position privacy-position terms-position]))
    (when (every? some? [faq-position privacy-position terms-position])
      (is (< faq-position privacy-position terms-position)))
    (is (= 1 (count (re-seq #"href=\"/faq\"" page))))
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
         "Choose your activity data"
         "Heart rate is the main supported graph"
         "optional OxiWear SpO2 (oxygen saturation)"
         "Heart-rate data format"
         "Heart-rate data file"
         "Or paste heart-rate data"
         "Heart-rate sync time"
         "Timestamp in the heart-rate data file"
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
         "Telemetry sync time"
         "Telemetry timestamps"
         "Preview overlay"]
        technical-identifiers
        ["<code>telemetryFormat</code>"
         "<code>telemetry</code>"
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
                       ["oxygen-saturation-support" "Does Alpha Compose support oxygen saturation?"]
                       ["future-graphs" "Will other graphs be supported?"]
                       ["file-sources" "Where are my video and activity-data files read from?"]
                       ["synchronizing-data-and-camera" "Why do I need to synchronize the activity data and camera time?"]
                       ["output-file" "What output does Alpha Compose create?"]
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
                       "optional OxiWear SpO2"
                       "source video is streamed"
                       "does not log activity-data values"
                       "not medical advice"
                       "does not infer emotions, health, or training readiness"]]
          (is (str/includes? page claim) claim))
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
                :fragment "synchronizing-data-and-camera"
                :name "Learn about activity-data synchronization"}
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
    (is (= 6 (count (re-seq #"class=\"contextual-help\"" compose))))
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
                          ["/faq#supported-activity-data"
                           "Learn about supported activity-data formats"]
                          ["/faq#synchronizing-data-and-camera"
                           "Learn about activity-data synchronization"]
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

(deftest faq-fragments-open-scroll-and-preserve-browser-navigation
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
        (is (= 18 (:summaryCount outcome)))
        (is (true? (:summariesKeyboardReachable outcome)))
        (is (true? (:permalinksAccessible outcome)))
        (is (zero? (:nestedDetails outcome)))
        (is (= "/faq" (:activeNav outcome)))
        (is (true? (:activeNavStyled outcome)))
        (is (true? (:noHorizontalOverflow outcome)))
        (is (true? (:detailsFit outcome)))))))

(deftest preview-admission-cost-deep-link-opens-the-new-faq-answer
  (let [outcome (faq-browser-outcome ui/faq-page
                                     "preview-admission-cost"
                                     "390,844")]
    (is (nil? (:error outcome)) outcome)
    (is (= "#preview-admission-cost" (get-in outcome [:initial :hash])))
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
        (is (string? script))
        (is valid?
            "The rendered compose initialization script must parse."))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

(deftest authenticated-compose-page-orders-numbered-sections
  (let [page (ui/page {:user {:email "member@example.com"
                              :role :member}
                       :csrf "csrf-test"
                       :tokens []
                       :members []
                       :logs-enabled? false})
        optional-source "<h2>Optional source video</h2>"
        step-1 "<div class=\"step\">Step 1</div><h2>Choose your activity data</h2>"
        step-2 "<div class=\"step\">Step 2</div><h2>Line up the timeline</h2>"
        step-3 "<div class=\"step\">Step 3</div><h2>Optional overlays</h2>"]
    (is (every? #(str/includes? page %)
                [optional-source step-1 step-2 step-3]))
    (is (< (str/index-of page optional-source)
           (str/index-of page step-1)
           (str/index-of page step-2)
           (str/index-of page step-3)))
    (is (not (re-find #"<div class=\"step\">Step [^<]+</div><h2>Optional source video</h2>"
                      page)))))

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
    (is (= {:submitDisabled false
            :status "Preview is optional. Create the finished video when ready."}
           (select-keys (:initial outcome)
                        [:submitDisabled :status])))
    (is (= {:spinnerHidden true
            :spinnerInside true
            :previewCursor "pointer"}
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
    (is (= (get-in outcome [:pending :presentation :submitBackground])
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
                        :requestedDurationSeconds 20
                        :retryable false}]))
        fragment (ui/preview-operation-fragment operation "generation-1")]
    (is (str/includes?
         fragment
         (str "We generated 3 of 4 preview frames. The selected video ends "
              "before the 20-second section, so 1 later preview frame is "
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

(deftest telemetry-files-follow-the-selected-format-in-a-browser
  (let [outcome (telemetry-file-browser-outcome
                 (ui/page {:user {:email "owner@example.com" :role :member}
                           :csrf "csrf-test"
                           :tokens []
                           :members []
                           :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (:fitMatches outcome) outcome)
    (is (= "garmin-fit" (:fitFormat outcome)))
    (is (:clearedOnFormatChange outcome) outcome)
    (is (:csvMatches outcome) outcome)
    (is (= "polar-csv" (:csvFormat outcome)))
    (is (:clearedOnFileChange outcome) outcome)
    (is (:secondCsvMatches outcome) outcome)
    (is (:oxiwearMatches outcome) outcome)
    (is (= "oxiwear-hr-csv" (:oxiwearFormat outcome)))))

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
                          "2 GiB"
                          "downloadable, decodable"]]
          (is (str/includes? body contract) contract))
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
        (is (str/includes? (.body landing) "hx-post=\"/ui/tokens\""))
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
        (is (str/includes? (.body submission) "Queued"))
        (is (= 200 (.statusCode
                    (request! port :get status-path nil {"Cookie" owner-cookie}))))
        (is (= 404 (.statusCode
                    (request! port :get status-path nil {"Cookie" member-cookie}))))
        (let [cancelled (request! port :post (str status-path "/cancel") "" headers)]
          (is (= 200 (.statusCode cancelled)))
          (is (str/includes? (.body cancelled) "Cancelled"))
          (is (str/includes? (.body cancelled)
                             (str "hx-post=\"" status-path "/retry\""))))
        (let [retried (request! port :post (str status-path "/retry") "" headers)]
          (is (= 202 (.statusCode retried)))
          (is (str/includes? (.body retried) "Queued"))
          (is (str/includes? (.body retried) "Attempt 2"))))
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
        (is (str/includes? (.body listed) "&lt;script&gt;alert(1)&lt;/script&gt;"))
        (is (not (str/includes? (.body listed) raw-token)))
        (is (not (str/includes? (.body listed) "hash"))))
      (finally
        (.close ^java.lang.AutoCloseable server)))))
