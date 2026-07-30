(ns agg.proto-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.browser-process :as browser-process]
            [agg.http-test-support :as test-http]
            [agg.proto.core :as proto]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- available-port []
  (test-http/available-port))

(defn- start-api!
  [port dependencies]
  (api/start! port dependencies))

(defn- request!
  [port method path body headers]
  (test-http/send-string! method (str "http://127.0.0.1:" port path)
                          (when (= :post method) (or body "")) headers))

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
                      :base-url "https://proto.alphacompose.test"
                      :allowlist #{"owner@example.com"}
                      :session-key (.getBytes "01234567890123456789012345678901")
                      :oauth oauth
                      :grant-store grant-store
                      :cipher cipher
                      :drive-token-client token-client})
        owner {:subject "owner-subject" :email "owner@example.com"}]
    {:auth-system auth-system
     :owner owner
     :owner-cookie (str "agg_session=" (auth/issue-session auth-system owner))}))

(def browser-fixture-timeout-ms 30000)

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
  [requirement location virtual-time-budget timeout-ms browser-args]
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
           :key-fn keyword))))))

(defn- browser-outcome
  [prefix requirement html]
  (let [temp (File/createTempFile prefix ".html")]
    (try
      (spit temp html)
      (browser-location-outcome requirement
                                (.toURI temp)
                                3000
                                browser-fixture-timeout-ms
                                [])
      (finally
        (.delete temp)))))

(defn- proto-page-browser-outcome
  [{:keys [analysis-failure? analysis-response cache-hit? can-play-type
           cancel-and-retry? derivative? media-error? media-error-recovery?
           submit-failure supported?
           source-file-name terminal-resource timing-response webcodecs?]}]
  (let [source-file-name (or source-file-name "timing-ride.mp4")
        analysis-response
        (or analysis-response
            {:ok true
             :status 200
             :body
             {:fileName source-file-name
              :evidence
              {:container {:format "mp4" :majorBrand "isom"}
               :video {:codec "h264" :codecTag "avc1"
                       :profile "High" :pixelFormat "yuv420p"}
               :audio {:codec "aac"}}}})
        derivative-status-resource
        (merge
         {:id "00000000-0000-0000-0000-000000000196"
          :state "succeeded"
          :attempt (if cancel-and-retry? 2 1)
          :profileVersion "h264-aac-1080p25-v1"
          :assetId "00000000-0000-0000-0000-000000000296"
          :expiresAt "2026-07-31T12:00:00Z"
          :statusUrl "/v1/derivative-preparations/00000000-0000-0000-0000-000000000196"
          :cancelUrl "/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/cancel"
          :retryUrl "/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/retry"}
         terminal-resource)
        page (proto/page {:user {:email "owner@example.com"}
                          :csrf "csrf-token"
                          :folder-id proto/fixed-folder-id})
        bootstrap
        (str
         "<script>"
         "window.__protoState={analysisRequests:[],timingRequests:[],sessionRequests:[],derivativeRequests:[],derivativeStatusRequests:[],derivativePlaybackRequests:[],cancelRequests:[],retryRequests:[],rangeRequests:[]};"
         "window.fetch=(path,options={})=>{"
         "if(path==='/v1/proto/sources'){return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({listingMode:'folder-enumeration',folderId:'"
         proto/fixed-folder-id
         "',sources:[{fileId:'timing-source-1',fileName:"
         (json/write-str source-file-name)
         ",mimeType:'video/mp4',size:8192,durationSeconds:125.5,width:1920,height:1080}]})});}"
         "if(path==='/v1/drive/playback-analyses'){window.__protoState.analysisRequests.push(JSON.parse(options.body));return Promise.resolve({ok:"
         (json/write-str (:ok analysis-response))
         ",status:" (:status analysis-response)
         ",json:()=>Promise.resolve(" (json/write-str (:body analysis-response))
         ")});}"
         "if(path==='/v1/drive/recording-clock-inspections'){window.__protoState.timingRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve("
         (json/write-str timing-response)
         ")});}"
         "if(path==='/v1/drive/playback-sessions'){window.__protoState.sessionRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:201,json:()=>Promise.resolve({playbackUrl:'/v1/drive/playback/00000000-0000-0000-0000-000000000115',contentType:'video/mp4',size:8192})});}"
         "if(path==='/v1/drive/playback/00000000-0000-0000-0000-000000000115'){window.__protoState.rangeRequests.push(options.headers&&options.headers.Range||null);return Promise.resolve({ok:true,status:206,headers:new Headers({'Content-Range':'bytes 0-4095/8192','Content-Length':'4096','Content-Type':'video/mp4'}),arrayBuffer:()=>Promise.resolve(new ArrayBuffer(16))});}"
         (cond
           submit-failure
           (str "if(path==='/v1/derivative-preparations'&&options.method==='POST'){window.__protoState.derivativeRequests.push({body:JSON.parse(options.body),idempotencyKey:options.headers['Idempotency-Key']});return Promise.resolve({ok:false,status:"
                (:status submit-failure)
                ",headers:new Headers({'X-Request-Id':'request-failure-header'}),json:()=>Promise.resolve("
                (json/write-str (:body submit-failure))
                ")});}")

           cache-hit?
           "if(path==='/v1/derivative-preparations'&&options.method==='POST'){window.__protoState.derivativeRequests.push({body:JSON.parse(options.body),idempotencyKey:options.headers['Idempotency-Key']});return Promise.resolve({ok:true,status:200,headers:new Headers({'X-Request-Id':'request-cache'}),json:()=>Promise.resolve({id:'00000000-0000-0000-0000-000000000196',state:'succeeded',attempt:1,profileVersion:'h264-aac-1080p25-v1',assetId:'00000000-0000-0000-0000-000000000296',expiresAt:'2026-07-31T12:00:00Z',statusUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196',cancelUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/cancel',retryUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/retry'})});}"

           :else
           "if(path==='/v1/derivative-preparations'&&options.method==='POST'){window.__protoState.derivativeRequests.push({body:JSON.parse(options.body),idempotencyKey:options.headers['Idempotency-Key']});return Promise.resolve({ok:true,status:202,headers:new Headers({'X-Request-Id':'request-submit'}),json:()=>Promise.resolve({id:'00000000-0000-0000-0000-000000000196',state:'queued',attempt:1,profileVersion:'h264-aac-1080p25-v1',statusUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196',cancelUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/cancel',retryUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/retry'})});}")
         "if(path==='/v1/derivative-preparations/00000000-0000-0000-0000-000000000196'&&(!options.method||options.method==='GET')){window.__protoState.derivativeStatusRequests.push(path);return Promise.resolve({ok:true,status:200,headers:new Headers({'X-Request-Id':'request-status'}),json:()=>Promise.resolve("
         (json/write-str derivative-status-resource)
         ")});}"
         "if(path==='/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/cancel'){window.__protoState.cancelRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,headers:new Headers({'X-Request-Id':'request-cancel'}),json:()=>Promise.resolve({id:'00000000-0000-0000-0000-000000000196',state:'cancelled',attempt:1,profileVersion:'h264-aac-1080p25-v1',statusUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196',cancelUrl:path,retryUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/retry'})});}"
         "if(path==='/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/retry'){window.__protoState.retryRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:202,headers:new Headers({'X-Request-Id':'request-retry'}),json:()=>Promise.resolve({id:'00000000-0000-0000-0000-000000000196',state:'queued',attempt:2,profileVersion:'h264-aac-1080p25-v1',statusUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196',cancelUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/cancel',retryUrl:path})});}"
         "if(path==='/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/playback-sessions'){window.__protoState.derivativePlaybackRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:201,json:()=>Promise.resolve({playbackUrl:'/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/playback/00000000-0000-0000-0000-000000000396',contentType:'video/mp4',size:4096})});}"
         "if(path==='/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/playback/00000000-0000-0000-0000-000000000396'){window.__protoState.rangeRequests.push(options.headers&&options.headers.Range||null);return Promise.resolve({ok:true,status:206,headers:new Headers({'Content-Range':'bytes 0-4095/4096','Content-Length':'4096','Content-Type':'video/mp4'}),arrayBuffer:()=>Promise.resolve(new ArrayBuffer(16))});}"
         "return Promise.resolve({ok:false,status:500,json:()=>Promise.resolve({error:'unexpected'})});};"
         "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??125.5;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},buffered:{configurable:true,get(){const ranges=this.__bufferedRanges??[];return {length:ranges.length,start:index=>ranges[index][0],end:index=>ranges[index][1]};}},error:{configurable:true,get(){return this.__mediaError??null;}}});"
         "HTMLMediaElement.prototype.canPlayType=function(type){window.__protoState.canPlayType=type;return "
         (json/write-str can-play-type)
         ";};"
         (if webcodecs?
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:{isConfigSupported(config){window.__protoState.videoDecoderConfig=config;return Promise.resolve({supported:true});}}});"
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});")
         "HTMLMediaElement.prototype.load=function(){if(this.getAttribute('src')){this.__duration=125.5;"
         (if media-error?
           (str "this.__mediaError={code:4};this.dispatchEvent(new Event('error'));"
                (when media-error-recovery?
                  "this.__mediaError=null;this.dispatchEvent(new Event('timeupdate'));"))
           "this.dispatchEvent(new Event('loadedmetadata'));")
         "}};"
         "HTMLMediaElement.prototype.play=function(){this.__paused=false;this.dispatchEvent(new Event('play'));return Promise.resolve();};"
         "HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<script>"
         "function recordOutcome(outcome){const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.body.dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "function delay(ms){return new Promise(resolve=>setTimeout(resolve,ms));}"
         "async function waitFor(label,predicate,attempts){for(let index=0;index<attempts;index+=1){const value=predicate();if(value)return value;await delay(25);}throw new Error('Timed out waiting for '+label);}"
         "async function runScenario(){try{const select=await waitFor('source select',()=>document.getElementById('source-select'),200);await waitFor('source option',()=>select.options.length>1&&select.options[1].value==='timing-source-1',200);select.value='timing-source-1';select.dispatchEvent(new Event('change',{bubbles:true}));await waitFor('selected title',()=>document.getElementById('selected-title').textContent==="
         (json/write-str source-file-name)
         ",200);"
         (cond
           derivative?
           (str
            "const prepare=await waitFor('prepare browser preview action',()=>{const button=document.getElementById('prepare-browser-preview');return button&&!button.hidden&&button;},100);const beforeAction={sessionRequests:window.__protoState.sessionRequests.length,derivativeRequests:window.__protoState.derivativeRequests.length,playerSrc:document.getElementById('proto-player').getAttribute('src')};prepare.click();"
            (if cancel-and-retry?
              "const cancel=await waitFor('cancel action',()=>{const button=document.getElementById('cancel-preview');return button&&!button.hidden&&button;},100);cancel.click();const retry=await waitFor('retry action',()=>{const button=document.getElementById('retry-preview');return button&&!button.hidden&&button;},100);retry.click();"
              "")
            "await delay(1800);")

           analysis-failure?
           "await waitFor('analysis failure status',()=>document.getElementById('player-status').textContent.includes('playback_analysis_timeout'),200);"

           (and supported? media-error?)
           (str "await waitFor('preparation debug',()=>{const text=document.getElementById('prep-debug').textContent;return text&&text.includes('\"playback\"')&&text.includes('\"session\"');},200);await waitFor('actual media load failure',()=>document.getElementById('player-status').textContent.includes('actual media load'),200);"
                (when media-error-recovery?
                  "await waitFor('recovered media diagnostic',()=>JSON.parse(document.getElementById('range-debug').textContent).media.error===false,200);"))

           supported?
           "await waitFor('preparation debug',()=>{const text=document.getElementById('prep-debug').textContent;return text&&text.includes('\"support\"')&&text.includes('\"session\"');},200);await waitFor('loaded player status',()=>document.getElementById('player-status').textContent.includes('Private playback loaded'),200);const video=document.getElementById('proto-player');video.__bufferedRanges=[[0,30],[60,90]];video.dispatchEvent(new Event('progress'));"

           :else
           "await waitFor('prepare browser preview action',()=>{const button=document.getElementById('prepare-browser-preview');return button&&!button.hidden;},200);await waitFor('browser-rejected status',()=>document.getElementById('player-status').textContent.includes('original codec is not supported'),200);")
         "recordOutcome({sourceStatus:document.getElementById('source-status').textContent,playerStatus:document.getElementById('player-status').textContent,selectedTitle:document.getElementById('selected-title').textContent,beforeAction:typeof beforeAction==='undefined'?null:beforeAction,preparationText:(document.getElementById('preparation-panel')||{}).textContent||'',actions:{prepareHidden:document.getElementById('prepare-browser-preview').hidden,waitHidden:document.getElementById('wait-for-preview').hidden,cancelHidden:document.getElementById('cancel-preview').hidden,retryHidden:document.getElementById('retry-preview').hidden},playerSrc:document.getElementById('proto-player').getAttribute('src'),sourceSelect:{disabled:select.disabled,value:select.value,labels:Array.from(select.options).map(option=>option.textContent)},summary:{listing:document.getElementById('summary-listing').textContent,file:document.getElementById('summary-file').textContent,mime:document.getElementById('summary-mime').textContent,duration:document.getElementById('summary-duration').textContent,frameSize:document.getElementById('summary-size').textContent},timing:{start:document.getElementById('timing-start').textContent,end:document.getElementById('timing-end').textContent,state:document.getElementById('timing-state').textContent,confidence:document.getElementById('timing-confidence').textContent},prep:JSON.parse(document.getElementById('prep-debug').textContent),range:JSON.parse(document.getElementById('range-debug').textContent),requests:window.__protoState});}catch(error){recordOutcome({error:error.message,requests:window.__protoState});}}"
         "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',()=>{setTimeout(runScenario,0);},{once:true});}else{setTimeout(runScenario,0);}"
         "</script>")
        html (-> page
                 (str/replace "</head>" (str bootstrap "</head>"))
                 (str/replace "</body>" (str scenario "</body>"))
                 (str/replace "<body " "<body data-outcome=\"\">"))]
    (browser-outcome
     "agg-proto-browser-"
     "Proto playback browser regression requires Chrome or Chromium"
     html)))

(deftest proto-page-requires-explicit-preparation-before-loading-a-renderable-rejected-source
  (let [outcome
        (proto-page-browser-outcome
         {:analysis-response
          {:ok true
           :status 200
           :body
           {:fileName "private-name.mov"
            :fileId "private-analysis-file-id"
            :owner "private-analysis-owner"
            :downloadUrl "https://private.example/source"
            :evidence
            {:container {:format "mov" :majorBrand "qt  "}
             :video {:codec "hevc" :codecTag "hvc1"
                     :profile "Main" :pixelFormat "yuv420p"}
             :audio {:codec "aac"}}}}
          :can-play-type ""
          :derivative? true
          :source-file-name "private-name.mov"
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "manual"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= {:sessionRequests 0
            :derivativeRequests 0
            :playerSrc nil}
           (:beforeAction outcome)))
    (is (= [] (get-in outcome [:requests :sessionRequests])))
    (is (= [{:fileId "timing-source-1"}]
           (mapv :body
                 (get-in outcome [:requests :derivativeRequests]))))
    (is (every? #(and (string? %)
                      (<= 1 (count %) 128))
                (map :idempotencyKey
                     (get-in outcome [:requests :derivativeRequests]))))
    (is (= [{}] (get-in outcome [:requests :derivativePlaybackRequests])))
    (is (= "/v1/derivative-preparations/00000000-0000-0000-0000-000000000196/playback/00000000-0000-0000-0000-000000000396"
           (:playerSrc outcome)))
    (is (str/includes? (:preparationText outcome) "8 minutes"))
    (is (str/includes? (:preparationText outcome) "2 GB"))
    (is (str/includes? (:preparationText outcome) "24 hours"))
    (is (= "request-status"
           (get-in outcome [:prep :derivative :requestId])))
    (is (= {:format "mov" :majorBrand "qt  "}
           (get-in outcome [:prep :analysis :evidence :container])))
    (is (= {:codec "hevc"
            :codecTag "hvc1"
            :profile "Main"
            :pixelFormat "yuv420p"}
           (get-in outcome [:prep :analysis :evidence :video])))
    (let [debug-json (json/write-str (:prep outcome))]
      (doseq [private-value ["owner@example.com"
                             proto/fixed-folder-id
                             "timing-source-1"
                             "private-name.mov"
                             "private-analysis-file-id"
                             "private-analysis-owner"
                             "https://private.example/source"
                             "00000000-0000-0000-0000-000000000196"
                             "00000000-0000-0000-0000-000000000296"
                             "00000000-0000-0000-0000-000000000396"]]
        (is (not (str/includes? debug-json private-value)))))
    (is (= "bytes 0-4095/4096"
           (get-in outcome [:range :rangeProbe :contentRange])))))

(deftest proto-page-cache-hit-skips-queue-polling-and-new-work-copy
  (let [outcome
        (proto-page-browser-outcome
         {:cache-hit? true
          :can-play-type ""
          :derivative? true
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "manual"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= [] (get-in outcome [:requests :sessionRequests])))
    (is (= [] (get-in outcome [:requests :derivativeStatusRequests])))
    (is (= true (get-in outcome [:prep :derivative :cacheHit])))
    (is (= "request-cache"
           (get-in outcome [:prep :derivative :requestId])))
    (is (str/includes? (:preparationText outcome)
                       "No new preparation work was started"))
    (is (str/includes? (:playerStatus outcome) "Browser preview ready"))))

(deftest proto-page-shows-safe-admission-failure-metadata
  (let [request-id "request-capacity"
        outcome
        (proto-page-browser-outcome
         {:can-play-type ""
          :derivative? true
          :submit-failure
          {:status 503
           :body {:error "derivative_project_backlog_exhausted"
                  :requestId request-id
                  :retryable true}}
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "manual"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= [] (get-in outcome [:requests :sessionRequests])))
    (is (= [] (get-in outcome [:requests :derivativeStatusRequests])))
    (is (str/includes? (:preparationText outcome)
                       "derivative_project_backlog_exhausted"))
    (is (str/includes? (:preparationText outcome) "Retryable: Yes"))
    (is (str/includes? (:preparationText outcome)
                       (str "Request ID: " request-id)))
    (is (= {:status 503
            :error "derivative_project_backlog_exhausted"
            :retryable true
            :requestId request-id}
           (get-in outcome [:prep :preparationError])))))

(deftest proto-page-surfaces-retryable-verification-failure
  (let [outcome
        (proto-page-browser-outcome
         {:can-play-type ""
          :derivative? true
          :terminal-resource
          {:state "failed"
           :failureCode "derivative_verification_failed"
           :retryable true}
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "manual"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (str/includes? (:preparationText outcome)
                       "derivative_verification_failed"))
    (is (str/includes? (:preparationText outcome) "Retryable: Yes"))
    (is (str/includes? (:preparationText outcome)
                       "Request ID: request-status"))
    (is (= false (get-in outcome [:actions :retryHidden])))
    (is (= [] (get-in outcome [:requests :derivativePlaybackRequests])))
    (is (nil? (:playerSrc outcome)))))

(deftest proto-page-cancels-only-on-explicit-action-and-can-retry
  (let [outcome
        (proto-page-browser-outcome
         {:cancel-and-retry? true
          :can-play-type ""
          :derivative? true
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "manual"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= [{}] (get-in outcome [:requests :cancelRequests])))
    (is (= [{}] (get-in outcome [:requests :retryRequests])))
    (is (= 2 (get-in outcome [:prep :derivative :attempt])))
    (is (= "succeeded" (get-in outcome [:prep :derivative :state])))
    (is (str/includes? (:playerStatus outcome) "Browser preview ready"))))

(defn- proto-page-race-browser-outcome []
  (let [page (proto/page {:user {:email "owner@example.com"}
                          :csrf "csrf-token"
                          :folder-id proto/fixed-folder-id})
        bootstrap
        (str
         "<script>"
         "window.__protoState={analysisRequests:[],timingRequests:[],sessionRequests:[],rangeRequests:[],deferred:{analysis:{},timing:{},session:{}}};"
         "window.__protoDeferred=()=>{let resolve,reject;const promise=new Promise((res,rej)=>{resolve=res;reject=rej;});return {promise,resolve,reject};};"
         "window.__protoJsonResponse=(status,body)=>({ok:status>=200&&status<300,status,json:()=>Promise.resolve(body)});"
         "window.__protoMediaResponse=(status,headers)=>({ok:status>=200&&status<300,status,headers:new Headers(headers),arrayBuffer:()=>Promise.resolve(new ArrayBuffer(16))});"
         "window.fetch=(path,options={})=>{"
         "if(path==='/v1/proto/sources'){return Promise.resolve(window.__protoJsonResponse(200,{listingMode:'folder-enumeration',folderId:'"
         proto/fixed-folder-id
         "',sources:[{fileId:'timing-source-1',fileName:'alpha.mp4',mimeType:'video/mp4',size:1111,durationSeconds:125.5,width:1920,height:1080},{fileId:'timing-source-2',fileName:'bravo.mov',mimeType:'video/quicktime',size:2222,durationSeconds:125.5,width:1920,height:1080}]}));}"
         "if(path==='/v1/drive/playback-analyses'){const body=JSON.parse(options.body);window.__protoState.analysisRequests.push(body);const deferred=window.__protoDeferred();window.__protoState.deferred.analysis[body.fileId]=deferred;return deferred.promise;}"
         "if(path==='/v1/drive/recording-clock-inspections'){const body=JSON.parse(options.body);window.__protoState.timingRequests.push(body);const deferred=window.__protoDeferred();window.__protoState.deferred.timing[body.fileId]=deferred;return deferred.promise;}"
         "if(path==='/v1/drive/playback-sessions'){const body=JSON.parse(options.body);window.__protoState.sessionRequests.push(body);const deferred=window.__protoDeferred();window.__protoState.deferred.session[body.fileId]=deferred;return deferred.promise;}"
         "if(path==='/v1/drive/playback/a-source'){window.__protoState.rangeRequests.push(path);return Promise.resolve(window.__protoMediaResponse(206,{'Content-Range':'bytes 0-4095/1111','Content-Length':'4096','Content-Type':'video/mp4'}));}"
         "if(path==='/v1/drive/playback/b-source'){window.__protoState.rangeRequests.push(path);return Promise.resolve(window.__protoMediaResponse(206,{'Content-Range':'bytes 0-4095/2222','Content-Length':'4096','Content-Type':'video/quicktime'}));}"
         "return Promise.resolve(window.__protoJsonResponse(500,{error:'unexpected'}));};"
         "window.__protoState.resolveAnalysis=(fileId,body,status=200)=>window.__protoState.deferred.analysis[fileId]&&window.__protoState.deferred.analysis[fileId].resolve(window.__protoJsonResponse(status,body));"
         "window.__protoState.resolveTiming=(fileId,body,status=200)=>window.__protoState.deferred.timing[fileId]&&window.__protoState.deferred.timing[fileId].resolve(window.__protoJsonResponse(status,body));"
         "window.__protoState.resolveSession=(fileId,body,status=201)=>window.__protoState.deferred.session[fileId]&&window.__protoState.deferred.session[fileId].resolve(window.__protoJsonResponse(status,body));"
         "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??125.5;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},buffered:{configurable:true,get(){const ranges=this.__bufferedRanges??[];return {length:ranges.length,start:index=>ranges[index][0],end:index=>ranges[index][1]};}}});"
         "HTMLMediaElement.prototype.canPlayType=function(type){window.__protoState.canPlayType=type;return 'probably';};"
         "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});"
         "HTMLMediaElement.prototype.load=function(){this.__duration=125.5;this.dispatchEvent(new Event('loadedmetadata'));};"
         "</script>")
        scenario
        (str
         "<script>"
         "function recordOutcome(outcome){const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.body.dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "const tick=()=>new Promise(resolve=>setTimeout(resolve,0));"
         "async function flush(times=6){for(let index=0;index<times;index++)await tick();}"
         "async function waitForSourceButtons(){for(let attempt=0;attempt<20;attempt++){const buttons=[...document.querySelectorAll('#source-list button')];if(buttons.length===2)return buttons;await tick();}throw new Error('Timed out waiting for source buttons');}"
         "window.addEventListener('load',()=>{setTimeout(async()=>{try{const buttons=await waitForSourceButtons(),state=window.__protoState;buttons[0].click();const stalePlayer=document.getElementById('proto-player');await flush();buttons[1].click();await flush();state.resolveAnalysis('timing-source-1',{fileName:'alpha.mp4',evidence:{container:{format:'mp4',majorBrand:'isom'},video:{codec:'h264',codecTag:'avc1',profile:'High',pixelFormat:'yuv420p'},audio:{codec:'aac'}}});state.resolveTiming('timing-source-1',{fileName:'alpha.mp4',status:'candidate',candidates:[{source:'movie',kind:'explicit-offset',value:'2026-07-27T22:00:00+02:00'}],recommendedIndex:0,ambiguous:false,durationSeconds:125.5,limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}});await flush();state.resolveAnalysis('timing-source-2',{fileName:'bravo.mov',evidence:{container:{format:'mov',majorBrand:'qt  '},video:{codec:'h264',codecTag:'avc1',profile:'High',pixelFormat:'yuv420p'},audio:{codec:'aac'}}});state.resolveTiming('timing-source-2',{fileName:'bravo.mov',status:'candidate',candidates:[{source:'movie',kind:'explicit-offset',value:'2026-07-27T23:00:00+02:00'}],recommendedIndex:0,ambiguous:false,durationSeconds:125.5,limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}});await flush();state.resolveSession('timing-source-2',{playbackUrl:'/v1/drive/playback/b-source',contentType:'video/quicktime',size:2222});await flush();state.resolveSession('timing-source-1',{playbackUrl:'/v1/drive/playback/a-source',contentType:'video/mp4',size:1111});await flush();const beforeStaleMedia=JSON.parse(document.getElementById('range-debug').textContent).media;stalePlayer.dispatchEvent(new Event('error'));stalePlayer.dispatchEvent(new Event('progress'));recordOutcome({error:null,playerStatus:document.getElementById('player-status').textContent,selectedTitle:document.getElementById('selected-title').textContent,prep:JSON.parse(document.getElementById('prep-debug').textContent),range:JSON.parse(document.getElementById('range-debug').textContent),beforeStaleMedia,requests:window.__protoState,playerSrc:document.getElementById('proto-player').getAttribute('src')});}catch(error){recordOutcome({error:error.message,requests:window.__protoState});}},0);},{once:true});"
         "</script>")
        html (-> page
                 (str/replace "</head>" (str bootstrap "</head>"))
                 (str/replace "</body>" (str scenario "</body>"))
                 (str/replace "<body " "<body data-outcome=\"\">"))]
    (browser-outcome
     "agg-proto-browser-race-"
     "Proto playback race regression requires Chrome or Chromium"
     html)))

(defn- proto-page-preparation-switch-outcome []
  (let [page (proto/page {:user {:email "owner@example.com"}
                          :csrf "csrf-token"
                          :folder-id proto/fixed-folder-id})
        job-id "00000000-0000-0000-0000-000000000196"
        status-url (str "/v1/derivative-preparations/" job-id)
        bootstrap
        (str
         "<script>"
         "window.__switchState={submitRequests:[],statusRequests:[],cancelRequests:[],playbackRequests:[]};"
         "window.__firstStatus={};window.__firstStatus.promise=new Promise(resolve=>window.__firstStatus.resolve=resolve);"
         "window.__readyStatusResponse=()=>({ok:true,status:200,headers:new Headers({'X-Request-Id':'switch-ready'}),json:()=>Promise.resolve({id:'"
         job-id
         "',state:'succeeded',attempt:1,profileVersion:'h264-aac-1080p25-v1',assetId:'00000000-0000-0000-0000-000000000296',expiresAt:'2026-07-31T12:00:00Z',statusUrl:'"
         status-url
         "',cancelUrl:'"
         status-url
         "/cancel',retryUrl:'"
         status-url
         "/retry'})});"
         "window.fetch=(path,options={})=>{"
         "if(path==='/v1/proto/sources')return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({listingMode:'folder-enumeration',folderId:'"
         proto/fixed-folder-id
         "',sources:[{fileId:'source-a',fileName:'private-name.mov',mimeType:'video/quicktime',size:8192,durationSeconds:120,width:1920,height:1080},{fileId:'source-b',fileName:'bravo.mov',mimeType:'video/quicktime',size:8192,durationSeconds:120,width:1920,height:1080}]})});"
         "if(path==='/v1/drive/playback-analyses'){const body=JSON.parse(options.body);return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:body.fileId==='source-a'?'private-name.mov':'bravo.mov',evidence:{container:{format:'mov',majorBrand:'qt  '},video:{codec:'hevc',codecTag:'hvc1',profile:'Main',pixelFormat:'yuv420p'},audio:{codec:'aac'}}})});}"
         "if(path==='/v1/drive/recording-clock-inspections')return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({status:'manual',candidates:[],recommendedIndex:null,ambiguous:false,durationSeconds:120,limits:{maxBytes:524288,maxRanges:2,timeoutMillis:3000}})});"
         "if(path==='/v1/derivative-preparations'&&options.method==='POST'){window.__switchState.submitRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:202,headers:new Headers({'X-Request-Id':'switch-submit'}),json:()=>Promise.resolve({id:'"
         job-id
         "',state:'queued',attempt:1,profileVersion:'h264-aac-1080p25-v1',statusUrl:'"
         status-url
         "',cancelUrl:'"
         status-url
         "/cancel',retryUrl:'"
         status-url
         "/retry'})});}"
         "if(path==='" status-url "'){window.__switchState.statusRequests.push(path);return window.__switchState.statusRequests.length===1?window.__firstStatus.promise:Promise.resolve(window.__readyStatusResponse());}"
         "if(path==='" status-url "/cancel'){window.__switchState.cancelRequests.push({});return Promise.resolve({ok:false,status:500,json:()=>Promise.resolve({error:'unexpected_cancel'})});}"
         "if(path==='" status-url "/playback-sessions'){window.__switchState.playbackRequests.push({});return Promise.resolve({ok:false,status:500,json:()=>Promise.resolve({error:'unexpected_playback'})});}"
         "return Promise.resolve({ok:false,status:500,json:()=>Promise.resolve({error:'unexpected'})});};"
         "HTMLMediaElement.prototype.canPlayType=()=>'';"
         "HTMLMediaElement.prototype.load=function(){};"
         "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});"
         "</script>")
        scenario
        (str
         "<script>"
         "function recordOutcome(value){const bytes=new TextEncoder().encode(JSON.stringify(value));document.body.dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));"
         "async function waitFor(label,predicate){for(let index=0;index<100;index++){const value=predicate();if(value)return value;await delay(20);}throw new Error('Timed out waiting for '+label);}"
         "window.addEventListener('load',()=>setTimeout(async()=>{try{const buttons=await waitFor('source buttons',()=>{const found=[...document.querySelectorAll('#source-list button')];return found.length===2&&found;});buttons[0].click();const prepare=await waitFor('prepare action',()=>{const button=document.getElementById('prepare-browser-preview');return !button.hidden&&button;});prepare.click();await waitFor('first status request',()=>window.__switchState.statusRequests.length===1);buttons[1].click();window.__firstStatus.resolve(window.__readyStatusResponse());const wait=await waitFor('wait action',()=>{const button=document.getElementById('wait-for-preview');return document.getElementById('selected-title').textContent==='bravo.mov'&&!button.hidden&&button;});await delay(50);const beforeWait={statusRequests:window.__switchState.statusRequests.length,cancelRequests:window.__switchState.cancelRequests.length,submitRequests:window.__switchState.submitRequests.length,panel:document.getElementById('preparation-summary').textContent};wait.click();await delay(1300);recordOutcome({error:null,beforeWait,selectedTitle:document.getElementById('selected-title').textContent,playerStatus:document.getElementById('player-status').textContent,panel:document.getElementById('preparation-summary').textContent,playerSrc:document.getElementById('proto-player').getAttribute('src'),requests:window.__switchState});}catch(error){recordOutcome({error:error.message,requests:window.__switchState});}},0),{once:true});"
         "</script>")
        html (-> page
                 (str/replace "</head>" (str bootstrap "</head>"))
                 (str/replace "</body>" (str scenario "</body>"))
                 (str/replace "<body " "<body data-outcome=\"\">"))]
    (browser-outcome
     "agg-proto-derivative-switch-"
     "Proto derivative source-switch regression requires Chrome or Chromium"
     html)))

(deftest proto-service-profile-serves-a-separate-playback-harness
  (let [{:keys [auth-system owner owner-cookie]} (fixture)
        port (available-port)
        proto-sources
        {:listingMode "folder-enumeration"
         :folderId "1PAoq2qflZB9qX1FEMmi1m43Zb3oZI_lt"
         :sources [{:fileId "video-1"
                    :fileName "timing-ride.mp4"
                    :mimeType "video/mp4"
                    :size 2048}]}
        server (start-api!
                port
                {:service-profile "proto"
                 :auth-system auth-system
                 :proto-source-loader (fn [{:keys [user folder-id]}]
                                        (is (= owner (select-keys user [:subject :email])))
                                        (is (= "1PAoq2qflZB9qX1FEMmi1m43Zb3oZI_lt"
                                               folder-id))
                                        proto-sources)})]
    (try
      (let [page (request! port :get "/" nil {"Cookie" owner-cookie})
            anonymous (request! port :get "/" nil {})
            listing (request! port :get "/v1/proto/sources" nil
                              {"Cookie" owner-cookie})]
        (is (= 200 (.statusCode page)))
        (is (.contains ^String (.body page)
                       "Timing workspace playback prototype"))
        (is (.contains ^String (.body page)
                       "proto.alphacompose.com"))
        (is (not (.contains ^String (.body page) "Create finished video")))
        (is (= 200 (.statusCode anonymous)))
        (is (.contains ^String (.body anonymous) "/v1/auth/proto-login/start"))
        (is (= 200 (.statusCode listing)))
        (is (= proto-sources
               (json/read-str (.body listing) :key-fn keyword))))
      (finally
        (.close server)))))

(deftest proto-page-prepares-supported-playback-and-exposes-bounded-debug
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type "probably"
                  :webcodecs? false
                  :supported? true
                  :timing-response {:fileName "timing-ride.mp4"
                                    :status "candidate"
                                    :candidates [{:source "movie"
                                                  :kind "explicit-offset"
                                                  :value "2026-07-27T22:00:00+02:00"}]
                                    :recommendedIndex 0
                                    :ambiguous false
                                    :durationSeconds 125.5
                                    :limits {:maxBytes 524288
                                             :maxRanges 2
                                             :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= "timing-ride.mp4" (:selectedTitle outcome)))
    (is (= false (get-in outcome [:sourceSelect :disabled])))
    (is (= "timing-source-1" (get-in outcome [:sourceSelect :value])))
    (is (= ["Choose a file from the folder" "timing-ride.mp4"]
           (get-in outcome [:sourceSelect :labels])))
    (is (str/includes? (:playerStatus outcome) "Private playback loaded"))
    (is (= "folder-enumeration" (get-in outcome [:summary :listing])))
    (is (= "timing-ride.mp4" (get-in outcome [:summary :file])))
    (is (= "video/mp4" (get-in outcome [:summary :mime])))
    (is (= "Candidate" (get-in outcome [:timing :state])))
    (is (= "2026-07-27T22:00:00+02:00" (get-in outcome [:timing :start])))
    (is (= "2026-07-27T22:02:05.500+02:00" (get-in outcome [:timing :end])))
    (is (= true (get-in outcome [:prep :support :supported])))
    (is (= {:kind "direct" :contentType "video/mp4" :size 8192}
           (get-in outcome [:prep :session])))
    (is (= ["bytes=0-4095"] (get-in outcome [:requests :rangeRequests])))
    (is (= "bytes 0-4095/8192" (get-in outcome [:range :rangeProbe :contentRange])))
    (is (= [[0 30] [60 90]] (get-in outcome [:range :media :buffered])))))

(deftest proto-page-exposes-playback-analysis-status-and-error-code
  (let [outcome
        (proto-page-browser-outcome
         {:analysis-failure? true
          :analysis-response {:ok false
                              :status 504
                              :body {:error "playback_analysis_timeout"
                                     :retryable true}}
          :can-play-type "probably"
          :webcodecs? false
          :timing-response {:fileName "timing-ride.mp4"
                            :status "candidate"
                            :candidates []
                            :recommendedIndex nil
                            :ambiguous false
                            :durationSeconds 125.5
                            :limits {:maxBytes 524288
                                     :maxRanges 2
                                     :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= "Playback analysis failed (504, playback_analysis_timeout)"
           (:playerStatus outcome)))
    (is (= {:status 504
            :error "playback_analysis_timeout"
            :retryable true}
           (get-in outcome [:prep :analysisFailure])))
    (is (= [] (get-in outcome [:requests :sessionRequests])))))

(deftest proto-page-does-not-load-rejected-original-media-before-explicit-preparation
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type ""
                  :webcodecs? false
                  :supported? false
                  :timing-response {:fileName "timing-ride.mp4"
                                    :status "manual"
                                    :candidates []
                                    :recommendedIndex nil
                                    :ambiguous false
                                    :durationSeconds 125.5
                                    :limits {:maxBytes 524288
                                             :maxRanges 2
                                             :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= "timing-source-1" (get-in outcome [:sourceSelect :value])))
    (is (str/includes? (:playerStatus outcome)
                       "original codec is not supported"))
    (is (= false (get-in outcome [:prep :support :supported])))
    (is (= "browser_rejected" (get-in outcome [:prep :support :reason])))
    (is (= "heuristic_rejected" (get-in outcome [:prep :playback :heuristic])))
    (is (= "not_attempted" (get-in outcome [:prep :playback :actual])))
    (is (= "Duration only" (get-in outcome [:timing :state])))
    (is (= [] (get-in outcome [:requests :sessionRequests])))
    (is (= [] (get-in outcome [:requests :derivativeRequests])))
    (is (nil? (get-in outcome [:range :rangeProbe])))))

(deftest proto-page-surfaces-an-actual-direct-media-load-failure
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type "probably"
                  :media-error? true
                  :webcodecs? false
                  :supported? true
                  :timing-response {:fileName "timing-ride.mp4"
                                    :status "manual"
                                    :candidates []
                                    :recommendedIndex nil
                                    :ambiguous false
                                    :durationSeconds 125.5
                                    :limits {:maxBytes 524288
                                             :maxRanges 2
                                             :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (str/includes? (:playerStatus outcome) "actual media load"))
    (is (= "heuristic_supported" (get-in outcome [:prep :playback :heuristic])))
    (is (= "actual_media_load_failed" (get-in outcome [:prep :playback :actual])))
    (is (= true (get-in outcome [:range :media :error])))
    (is (= [{:fileId "timing-source-1"}]
           (get-in outcome [:requests :sessionRequests])))))

(deftest proto-page-clears-a-recovered-media-load-error
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type "probably"
                  :media-error? true
                  :media-error-recovery? true
                  :webcodecs? false
                  :supported? true
                  :timing-response {:fileName "timing-ride.mp4"
                                    :status "manual"
                                    :candidates []
                                    :recommendedIndex nil
                                    :ambiguous false
                                    :durationSeconds 125.5
                                    :limits {:maxBytes 524288
                                             :maxRanges 2
                                             :timeoutMillis 3000}}})]
    (is (nil? (:error outcome)))
    (is (= "actual_media_load_failed" (get-in outcome [:prep :playback :actual])))
    (is (= false (get-in outcome [:range :media :error])))))

(deftest proto-page-ignores-stale-source-switch-completions
  (let [outcome (proto-page-race-browser-outcome)]
    (is (nil? (:error outcome)))
    (is (= "bravo.mov" (:selectedTitle outcome)))
    (is (str/includes? (:playerStatus outcome) "Private playback loaded"))
    (is (= "h264"
           (get-in outcome [:prep :analysis :evidence :video :codec])))
    (is (= "2026-07-27T23:00:00+02:00" (get-in outcome [:prep :timing :candidates 0 :value])))
    (is (= "direct" (get-in outcome [:prep :session :kind])))
    (is (= "video/quicktime" (get-in outcome [:prep :session :contentType])))
    (is (= 2222 (get-in outcome [:prep :session :size])))
    (is (= [{:fileId "timing-source-2"}] (get-in outcome [:requests :sessionRequests])))
    (is (= ["/v1/drive/playback/b-source"] (get-in outcome [:requests :rangeRequests])))
    (is (= "/v1/drive/playback/b-source" (:playerSrc outcome)))
    (is (= "video/quicktime" (get-in outcome [:range :rangeProbe :contentType])))
    (is (= (:beforeStaleMedia outcome)
           (get-in outcome [:range :media])))))

(deftest proto-page-source-switch-stops-polling-without-silent-cancellation
  (let [outcome (proto-page-preparation-switch-outcome)]
    (is (nil? (:error outcome)))
    (is (= "bravo.mov" (:selectedTitle outcome)))
    (is (= {:statusRequests 1
            :cancelRequests 0
            :submitRequests 1
            :panel
            "A browser preview for another selected source is still queued. Wait for it or cancel it explicitly before preparing this source."}
           (:beforeWait outcome)))
    (is (not (str/includes? (get-in outcome [:beforeWait :panel])
                            "private-name.mov")))
    (is (= 2 (count (get-in outcome [:requests :statusRequests]))))
    (is (= [] (get-in outcome [:requests :cancelRequests])))
    (is (= [] (get-in outcome [:requests :playbackRequests])))
    (is (nil? (:playerSrc outcome)))
    (is (str/includes? (:playerStatus outcome)
                       "existing browser preview is ready"))))
