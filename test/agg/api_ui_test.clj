(ns agg.api-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
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

(defn- picker-browser-outcome [page]
  (let [fixture
        (str
         "<script>"
         "window.__pickerState={loads:[],visible:[],diagnostics:[],callback:null};"
         "window.fetch=(_path,options)=>{window.__pickerState.diagnostics.push(JSON.parse(options.body));return Promise.resolve({ok:true});};"
         "class PickerView{setIncludeFolders(){return this;}setSelectFolderEnabled(){return this;}}"
         "class PickerBuilder{"
         "addView(){return this;}setSelectableMimeTypes(){return this;}setOAuthToken(){return this;}"
         "setDeveloperKey(){return this;}setAppId(){return this;}setOrigin(){return this;}"
         "setCallback(callback){window.__pickerState.callback=callback;return this;}"
         "build(){return {setVisible(visible){window.__pickerState.visible.push(visible);}};}"
         "}"
         "window.google={picker:{DocsView:PickerView,DocsUploadView:PickerView,PickerBuilder,"
         "Action:{LOADED:'loaded',PICKED:'picked',CANCEL:'cancel'}}};"
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
         "state.callback({action:google.picker.Action.PICKED,docs:[{id:'test-file-id',name:'video.mp4',mimeType:'video/mp4'}]});"
         "const selected=selection.textContent;button.click();state.callback({action:google.picker.Action.CANCEL});"
         "outcome={initialLoading,failureMessage,failureRetryLoading,timeoutMessage,timeoutRetryLoading,selected,visible:state.visible,diagnostics:state.diagnostics};"
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
                     html)))

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

(defn- preview-gallery-operation []
  {:id "00000000-0000-0000-0000-000000000061"
   :operationKind "key-moment-gallery"
   :state "succeeded"
   :progressPercent 100
   :result
   {:version 1
    :mode "source-final"
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
    [{:id "a000" :frameIndex 25 :kind "source-final" :merged false
      :source {:thumbnailUrl "/v1/previews/x/images/a000-source/thumbnail"
               :fullUrl "/v1/previews/x/images/a000-source/full"}
      :final {:thumbnailUrl "/v1/previews/x/images/a000-final/thumbnail"
              :fullUrl "/v1/previews/x/images/a000-final/full"}}
     {:id "a001" :frameIndex 25 :kind "source-final" :merged true
      :source {:thumbnailUrl "/v1/previews/x/images/a001-source/thumbnail"
               :fullUrl "/v1/previews/x/images/a001-source/full"}
      :final {:thumbnailUrl "/v1/previews/x/images/a001-source/thumbnail"
              :fullUrl "/v1/previews/x/images/a001-source/full"}}]}})

(defn- timer-preview-gallery-operation []
  {:id "00000000-0000-0000-0000-000000000082"
   :operationKind "key-moment-gallery"
   :state "succeeded"
   :progressPercent 100
   :result
   {:version 1
    :mode "source-final"
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
              {:id id :frameIndex frame :kind "source-final" :merged false
               :source {:thumbnailUrl (str "/v1/previews/x/images/" id
                                           "-source/thumbnail")
                        :fullUrl (str "/v1/previews/x/images/" id
                                      "-source/full")}
               :final {:thumbnailUrl (str "/v1/previews/x/images/" id
                                          "-final/thumbnail")
                       :fullUrl (str "/v1/previews/x/images/" id
                                     "-final/full")}}))
          (range 4) [0 13 38 49])}})

(defn- preview-gallery-browser-outcome
  ([narrow?]
   (preview-gallery-browser-outcome (preview-gallery-operation) narrow?))
  ([operation narrow?]
   (let [fragment (ui/preview-operation-fragment operation "generation-1")
         page (-> (ui/page {:user {:email "owner@example.com" :role :member}
                            :csrf "csrf-test"
                            :tokens [] :members [] :logs-enabled? false})
                  (str/replace "<div id=\"preview-result\"></div>" fragment)
                  (str/replace #"<script src=\"[^\"]+\"[^>]*></script>" ""))
         request-json (json/write-str (fixture/render-request))
         scenario
         (str
          "<pre id=\"browser-result\">pending</pre><script>"
          "let outcome;try{const moments=document.querySelector('.preview-moments'),source=document.querySelector('.preview-cell .frame-role'),final=[...document.querySelectorAll('.frame-role')].find(node=>node.textContent==='Final'),buttons=[...document.querySelectorAll('.preview-open')],titles=[...document.querySelectorAll('.photo-title')],openIndex=Math.min(3,buttons.length-2),open=buttons[openIndex],dialog=document.getElementById('preview-dialog');"
          "const momentCount=document.querySelectorAll('.preview-moment').length,display=getComputedStyle(moments).display,roles=[...document.querySelectorAll('.frame-role')].map(node=>node.textContent),photoTitles=titles.map(node=>node.textContent),titlePlacement=titles.every((title,index)=>title.nextElementSibling===buttons[index]&&title.getBoundingClientRect().bottom<=buttons[index].getBoundingClientRect().top+1),titleRects=titles.map(title=>title.getBoundingClientRect()),titlesDoNotOverlap=titleRects.every((rect,index)=>titleRects.every((other,otherIndex)=>index===otherIndex||rect.right<=other.left||other.right<=rect.left||rect.bottom<=other.top||other.bottom<=rect.top)),thumbnailWidth=buttons[0].getBoundingClientRect().width,viewportWidth=window.innerWidth,noOverflow=document.documentElement.scrollWidth<=viewportWidth,meaningfulAlts=buttons.every(button=>button.querySelector('img').alt.length>12),eagerImages=buttons.every(button=>button.querySelector('img').loading==='eager'),nativeButtons=buttons.every(button=>button.tagName==='BUTTON');"
          "const sequenceTitles=buttons.map(button=>button.dataset.title),sequenceFullUrls=buttons.map(button=>button.dataset.full),thumbnailUrlsOnly=buttons.every(button=>button.querySelector('img').getAttribute('src').endsWith('/thumbnail'));const previous=dialog.querySelector('.preview-previous'),next=dialog.querySelector('.preview-next'),counter=dialog.querySelector('.preview-counter'),dialogTitle=dialog.querySelector('#preview-dialog-title'),dialogImage=dialog.querySelector('img'),viewerControls=!!(previous&&next&&counter&&dialogTitle);"
          "open.focus();open.click();const dialogRect=dialog.getBoundingClientRect(),dialogOpened=dialog.open&&dialogImage.alt===open.dataset.alt&&dialogImage.getAttribute('src')===open.dataset.full,clickedPosition=viewerControls&&dialogTitle.textContent===open.dataset.title&&counter.textContent==='Image '+(openIndex+1)+' of '+buttons.length,modalFocus=dialog.matches(':modal')&&dialog.contains(document.activeElement),viewportFit=dialogRect.width>=window.innerWidth-24&&dialogRect.height>=window.innerHeight-24&&getComputedStyle(dialogImage).objectFit==='contain',controlsVisible=viewerControls&&[previous,next].every(button=>button.getBoundingClientRect().width>0),accessibleViewer=viewerControls&&dialog.getAttribute('aria-labelledby')===dialogTitle.id&&counter.getAttribute('aria-live')==='polite'&&[previous,next,dialog.querySelector('.preview-dialog-close')].every(button=>(button.getAttribute('aria-label')||button.textContent).trim().length>0);"
          "let buttonNavigation=false,keyboardNavigation=false,endStates=false,escapePreserved=false;if(viewerControls){while(!next.disabled)next.click();buttonNavigation=counter.textContent==='Image '+buttons.length+' of '+buttons.length&&dialogTitle.textContent===sequenceTitles.at(-1)&&next.disabled&&!previous.disabled;dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));const stoppedAtEnd=counter.textContent==='Image '+buttons.length+' of '+buttons.length;dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true,cancelable:true}));keyboardNavigation=stoppedAtEnd&&counter.textContent==='Image '+(buttons.length-1)+' of '+buttons.length&&dialogTitle.textContent===sequenceTitles.at(-2);dialog.querySelector('.preview-dialog-close').click();const first=buttons[0];first.focus();first.click();dialog.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true,cancelable:true}));endStates=previous.disabled&&!next.disabled&&counter.textContent==='Image 1 of '+buttons.length;const cancelEvent=new Event('cancel',{cancelable:true});escapePreserved=dialog.dispatchEvent(cancelEvent);if(escapePreserved)dialog.close();}else{dialog.querySelector('.preview-dialog-close').click();}const focusReturned=document.activeElement===buttons[0];"
          "let stale=false,staleSwapRejected=false;if(!" narrow? "){const raw=document.getElementById('raw-json');raw.value=" (json/write-str request-json) ";document.getElementById('apply-json').click();const staleTarget=document.querySelector('.preview-stale');stale=!!staleTarget;const detail={target:staleTarget,xhr:{getResponseHeader:()=>\"generation-1\"},shouldSwap:true};staleTarget.dispatchEvent(new CustomEvent('htmx:beforeSwap',{bubbles:true,detail}));staleSwapRejected=!detail.shouldSwap;}"
          "outcome={momentCount,buttonCount:buttons.length,display,roles,photoTitles,ariaLabels:buttons.map(button=>button.getAttribute('aria-label')),titlePlacement,titlesDoNotOverlap,titleRects:titleRects.map(rect=>({left:rect.left,top:rect.top,right:rect.right,bottom:rect.bottom})),thumbnailWidth,viewportWidth,noOverflow,meaningfulAlts,eagerImages,nativeButtons,sequenceTitles,sequenceFullUrls,thumbnailUrlsOnly,viewerControls,dialogOpened,clickedPosition,modalFocus,viewportFit,controlsVisible,accessibleViewer,buttonNavigation,keyboardNavigation,endStates,escapePreserved,focusReturned,stale,staleSwapRejected,sourceBeforeFinal:source&&final&&source.compareDocumentPosition(final)&Node.DOCUMENT_POSITION_FOLLOWING};"
          "}catch(error){outcome={error:error.message};}const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.getElementById('browser-result').dataset.outcome=btoa(String.fromCharCode(...bytes));"
          "</script>")
         html (str/replace page "</body>" (str scenario "</body>"))]
     (if narrow?
       (browser-outcome "agg-preview-narrow-"
                        "Narrow preview regression requires Chrome or Chromium"
                        html "--window-size=390,844")
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
        (is (str/includes? (.body homepage) "Google identity"))
        (is (str/includes? (.body homepage) "approved access"))
        (is (str/includes? (.body homepage) "drive.file"))
        (is (str/includes? (.body homepage) "files you select"))
        (is (str/includes? (.body homepage) "required output delivery"))
        (is (= 200 (.statusCode privacy)))
        (is (str/includes? (.body privacy) "Privacy policy"))
        (is (str/includes? (.body privacy) "Google Drive"))
        (is (str/includes? (.body privacy) "Google API Services User Data Policy"))
        (is (str/includes? (.body privacy) "Limited Use"))
        (is (= 200 (.statusCode terms)))
        (is (str/includes? (.body terms) "Terms of service"))
        (is (str/includes? (.body terms) "me@jamiep.org")))
      (finally
        (.close ^java.lang.AutoCloseable server)))))

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
        step-1 "<div class=\"step\">Step 1</div><h2>Choose your data</h2>"
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

(deftest compose-submit-starts-enabled-with-preview-available
  (let [page (ui/page {:user {:email "member@example.com" :role :member}
                       :csrf "csrf-test" :tokens [] :members []
                       :logs-enabled? false})]
    (is (str/includes? page
                       "id=\"submit-button\" class=\"primary\" type=\"submit\">Submit render"))
    (is (str/includes? page "id=\"preview-submit-status\""))
    (is (str/includes? page "Preview is optional"))
    (is (str/includes? page
                       "class=\"button-spinner\" aria-hidden=\"true\" hidden"))
    (is (str/includes?
         page
         "@media(prefers-reduced-motion:reduce){.button-spinner{animation:none}}"))
    (is (str/includes? page "button.primary:disabled:hover"))
    (is (not (str/includes? page "localStorage")))
    (is (not (str/includes? page "sessionStorage")))))

(deftest picker-initialization-and-click-flow-recovers-in-a-browser
  (let [outcome (picker-browser-outcome
                 (ui/page {:user {:email "owner@example.com" :role :member}
                           :csrf "csrf-test"
                           :picker-config {:access-token "access-test"
                                           :api-key "key-test"
                                           :app-id "app-test"
                                           :csrf "csrf-test"}
                           :tokens []
                           :members []
                           :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= "Loading Google Drive Picker…" (:initialLoading outcome)))
    (is (= "Google Drive Picker failed to load. Try again."
           (:failureMessage outcome)))
    (is (= "Loading Google Drive Picker…" (:failureRetryLoading outcome)))
    (is (= "Google Drive Picker failed to load. Try again."
           (:timeoutMessage outcome)))
    (is (= "Loading Google Drive Picker…" (:timeoutRetryLoading outcome)))
    (is (= "video.mp4" (:selected outcome)))
    (is (= [false true false true false] (:visible outcome)))
    (is (= ["error" "error" "opened" "loaded" "selected" "opened"
            "cancelled"]
           (mapv :phase (:diagnostics outcome))))
    (is (every? #(= #{:phase :view :listState} (set (keys %)))
                (:diagnostics outcome)))))

(deftest preview-remains-stale-safe-and-retriable-without-gating-submit
  (let [outcome (preview-status-browser-outcome
                 (ui/page {:user {:email "owner@example.com" :role :member}
                           :csrf "csrf-test"
                           :tokens []
                           :members []
                           :logs-enabled? false}))]
    (is (nil? (:error outcome)) outcome)
    (is (= {:submitDisabled false
            :status "Preview is optional. Submit when ready."}
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
                       "No durable render was submitted or charged"))
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
    (is (= "Preview failed. Submit remains available."
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
                       "No durable render was submitted or charged"))
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
    (is (= "Preview failed. Submit remains available." (:submitStatus outcome))
        outcome)
    (is (= "Preview failed. See details below." (:status outcome)) outcome)
    (is (str/includes? (:text outcome)
                       "No durable render was submitted or charged"))
    (doseq [developer-detail ["preview_rendering" "Source content"
                              "worker_failed" "Failure code" "Request ID"]]
      (is (not (str/includes? (:text outcome) developer-detail)) outcome))))

(deftest preview-gallery-is-responsive-accessible-and-stale-safe-in-a-browser
  (let [desktop (preview-gallery-browser-outcome false)
        narrow (preview-gallery-browser-outcome true)]
    (is (nil? (:error desktop)) desktop)
    (is (= "grid" (:display desktop)))
    (is (= ["Source" "Final" "Source and Final - identical" "Source" "Final"]
           (:roles desktop)))
    (is (= ["Source - Video start - 120 bpm - 00:00.000"
            "Final - Video start - 120 bpm - 00:00.000"
            "Source and Final - identical - Prominent maximum - 168 bpm - 00:01.000"
            "Source - Video start - 97 % - 00:00.000"
            "Final - Video start - 97 % - 00:00.000"]
           (:photoTitles desktop)
           (:sequenceTitles desktop)))
    (is (= ["/v1/previews/x/images/a000-source/full"
            "/v1/previews/x/images/a000-final/full"
            "/v1/previews/x/images/a001-source/full"
            "/v1/previews/x/images/a000-source/full"
            "/v1/previews/x/images/a000-final/full"]
           (:sequenceFullUrls desktop)))
    (is (<= 124 (:thumbnailWidth desktop) 132))
    (is (:titlesDoNotOverlap desktop) desktop)
    (is (every? true? (map desktop
                           [:titlePlacement :noOverflow :meaningfulAlts :eagerImages
                            :nativeButtons :thumbnailUrlsOnly :viewerControls
                            :dialogOpened :clickedPosition :modalFocus :viewportFit
                            :controlsVisible :accessibleViewer :buttonNavigation
                            :keyboardNavigation :endStates :escapePreserved
                            :focusReturned :stale :staleSwapRejected])))
    (is (pos? (:sourceBeforeFinal desktop)))
    (is (nil? (:error narrow)) narrow)
    (is (= "block" (:display narrow)))
    (is (= (:roles desktop) (:roles narrow)))
    (is (= (:photoTitles desktop) (:photoTitles narrow)))
    (is (= (:sequenceTitles desktop) (:sequenceTitles narrow)))
    (is (= (:sequenceFullUrls desktop) (:sequenceFullUrls narrow)))
    (is (> (:thumbnailWidth narrow) (:thumbnailWidth desktop)))
    (is (<= (:thumbnailWidth narrow) (:viewportWidth narrow)))
    (is (:titlesDoNotOverlap narrow) narrow)
    (is (every? true? (map narrow
                           [:titlePlacement :noOverflow :meaningfulAlts :eagerImages
                            :nativeButtons :thumbnailUrlsOnly :viewerControls
                            :dialogOpened :clickedPosition :modalFocus :viewportFit
                            :controlsVisible :accessibleViewer :buttonNavigation
                            :keyboardNavigation :endStates :escapePreserved
                            :focusReturned])))
    (is (pos? (:sourceBeforeFinal narrow)))))

(deftest timer-preview-manifest-reaches-every-thumbnail-and-viewer-position
  (let [operation (timer-preview-gallery-operation)
        moments (get-in operation [:result :sections 0 :moments])
        assets (get-in operation [:result :assets])
        expected-titles
        (vec (mapcat (fn [{:keys [title]}]
                       [(str "Source - " title) (str "Final - " title)])
                     moments))
        expected-full-urls
        (vec (mapcat (fn [{:keys [source final]}]
                       [(:fullUrl source) (:fullUrl final)])
                     assets))
        outcome (preview-gallery-browser-outcome operation false)]
    (is (nil? (:error outcome)) outcome)
    (is (= 4 (:momentCount outcome)))
    (is (= 8 (:buttonCount outcome)))
    (is (= (vec (take 8 (cycle ["Source" "Final"]))) (:roles outcome)))
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
    (is (= 6 (count (re-seq #"class=\"preview-open\"" fragment))))
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
                       "No durable render was submitted or charged"))
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
        (is (str/includes? body "Heart-rate telemetry"))
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
         "telemetry_sample_limit_exceeded" "fewer telemetry samples"
         "unsupported_telemetry_format" "Polar CSV, Garmin FIT, or OxiWear"
         "unknown_failure" "Review the telemetry input"}]
    (is (str/includes? summary "Heart-rate telemetry"))
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
      (is (str/includes? spo2 "SpO₂ telemetry")))))

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
               (mapv int (take 8 (.body png))))))
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
