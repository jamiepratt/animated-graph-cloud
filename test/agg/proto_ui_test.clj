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
  [{:keys [analysis-failure? analysis-response can-play-type media-error? webcodecs?
           timing-response supported?]}]
  (let [analysis-response
        (or analysis-response
            {:ok true
             :status 200
             :body
             {:fileName "timing-ride.mp4"
              :evidence
              {:container {:format "mp4" :majorBrand "isom"}
               :video {:codec "h264" :codecTag "avc1"
                       :profile "High" :pixelFormat "yuv420p"}
               :audio {:codec "aac"}}}})
        page (proto/page {:user {:email "owner@example.com"}
                          :csrf "csrf-token"
                          :folder-id proto/fixed-folder-id})
        bootstrap
        (str
         "<script>"
         "window.__protoState={analysisRequests:[],timingRequests:[],sessionRequests:[],rangeRequests:[]};"
         "window.fetch=(path,options={})=>{"
         "if(path==='/v1/proto/sources'){return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({listingMode:'folder-enumeration',folderId:'"
         proto/fixed-folder-id
         "',sources:[{fileId:'timing-source-1',fileName:'timing-ride.mp4',mimeType:'video/mp4',size:8192,durationSeconds:125.5,width:1920,height:1080}]})});}"
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
         "return Promise.resolve({ok:false,status:500,json:()=>Promise.resolve({error:'unexpected'})});};"
         "Object.defineProperties(HTMLMediaElement.prototype,{duration:{configurable:true,get(){return this.__duration??125.5;}},currentTime:{configurable:true,get(){return this.__currentTime??0;},set(value){this.__currentTime=Number(value);this.dispatchEvent(new Event('timeupdate'));}},paused:{configurable:true,get(){return this.__paused!==false;}},buffered:{configurable:true,get(){const ranges=this.__bufferedRanges??[];return {length:ranges.length,start:index=>ranges[index][0],end:index=>ranges[index][1]};}}});"
         "HTMLMediaElement.prototype.canPlayType=function(type){window.__protoState.canPlayType=type;return "
         (json/write-str can-play-type)
         ";};"
         (if webcodecs?
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:{isConfigSupported(config){window.__protoState.videoDecoderConfig=config;return Promise.resolve({supported:true});}}});"
           "Object.defineProperty(window,'VideoDecoder',{configurable:true,value:undefined});")
         "HTMLMediaElement.prototype.load=function(){if(this.getAttribute('src')){this.__duration=125.5;this.dispatchEvent(new Event('"
         (if media-error? "error" "loadedmetadata")
         "'));}};"
         "HTMLMediaElement.prototype.play=function(){this.__paused=false;this.dispatchEvent(new Event('play'));return Promise.resolve();};"
         "HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<script>"
         "function recordOutcome(outcome){const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.body.dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "function delay(ms){return new Promise(resolve=>setTimeout(resolve,ms));}"
         "async function waitFor(label,predicate,attempts){for(let index=0;index<attempts;index+=1){const value=predicate();if(value)return value;await delay(25);}throw new Error('Timed out waiting for '+label);}"
         "async function runScenario(){try{const select=await waitFor('source select',()=>document.getElementById('source-select'),200);await waitFor('source option',()=>select.options.length>1&&select.options[1].value==='timing-source-1',200);select.value='timing-source-1';select.dispatchEvent(new Event('change',{bubbles:true}));await waitFor('selected title',()=>document.getElementById('selected-title').textContent==='timing-ride.mp4',200);"
         (cond
           analysis-failure?
           "await waitFor('analysis failure status',()=>document.getElementById('player-status').textContent.includes('playback_analysis_timeout'),200);"

           media-error?
           "await waitFor('preparation debug',()=>{const text=document.getElementById('prep-debug').textContent;return text&&text.includes('\"playback\"')&&text.includes('\"session\"');},200);await waitFor('actual media load failure',()=>document.getElementById('player-status').textContent.includes('actual media load'),200);"

           supported?
           "await waitFor('preparation debug',()=>{const text=document.getElementById('prep-debug').textContent;return text&&text.includes('\"support\"')&&text.includes('\"session\"');},200);await waitFor('loaded player status',()=>document.getElementById('player-status').textContent.includes('Private playback loaded'),200);const video=document.getElementById('proto-player');video.__bufferedRanges=[[0,30],[60,90]];video.dispatchEvent(new Event('progress'));"

           :else
           "await waitFor('preparation debug',()=>{const text=document.getElementById('prep-debug').textContent;return text&&text.includes('\"support\"')&&text.includes('\"playback\"')&&text.includes('\"session\"');},200);await waitFor('heuristic-rejection playback status',()=>document.getElementById('player-status').textContent.includes('Private playback loaded after a heuristic rejection'),200);")
         "recordOutcome({sourceStatus:document.getElementById('source-status').textContent,playerStatus:document.getElementById('player-status').textContent,selectedTitle:document.getElementById('selected-title').textContent,sourceSelect:{disabled:select.disabled,value:select.value,labels:Array.from(select.options).map(option=>option.textContent)},summary:{listing:document.getElementById('summary-listing').textContent,file:document.getElementById('summary-file').textContent,mime:document.getElementById('summary-mime').textContent,duration:document.getElementById('summary-duration').textContent,frameSize:document.getElementById('summary-size').textContent},timing:{start:document.getElementById('timing-start').textContent,end:document.getElementById('timing-end').textContent,state:document.getElementById('timing-state').textContent,confidence:document.getElementById('timing-confidence').textContent},prep:JSON.parse(document.getElementById('prep-debug').textContent),range:JSON.parse(document.getElementById('range-debug').textContent),requests:window.__protoState});}catch(error){recordOutcome({error:error.message});}}"
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
    (is (= "/v1/drive/playback/00000000-0000-0000-0000-000000000115"
           (get-in outcome [:prep :session :playbackUrl])))
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

(deftest proto-page-attempts-private-playback-after-a-heuristic-rejection
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
                       "Private playback loaded after a heuristic rejection"))
    (is (= false (get-in outcome [:prep :support :supported])))
    (is (= "browser_rejected" (get-in outcome [:prep :support :reason])))
    (is (= "heuristic_rejected" (get-in outcome [:prep :playback :heuristic])))
    (is (= "actual_media_loaded" (get-in outcome [:prep :playback :actual])))
    (is (= "Duration only" (get-in outcome [:timing :state])))
    (is (= [{:fileId "timing-source-1"}]
           (get-in outcome [:requests :sessionRequests])))
    (is (= "bytes 0-4095/8192"
           (get-in outcome [:range :rangeProbe :contentRange])))))

(deftest proto-page-distinguishes-an-actual-media-load-failure-from-a-heuristic-rejection
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type ""
                  :media-error? true
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
    (is (str/includes? (:playerStatus outcome) "actual media load"))
    (is (= "heuristic_rejected" (get-in outcome [:prep :playback :heuristic])))
    (is (= "actual_media_load_failed" (get-in outcome [:prep :playback :actual])))
    (is (= true (get-in outcome [:range :media :error])))
    (is (= [{:fileId "timing-source-1"}]
           (get-in outcome [:requests :sessionRequests])))))

(deftest proto-page-ignores-stale-source-switch-completions
  (let [outcome (proto-page-race-browser-outcome)]
    (is (nil? (:error outcome)))
    (is (= "bravo.mov" (:selectedTitle outcome)))
    (is (str/includes? (:playerStatus outcome) "Private playback loaded"))
    (is (= "bravo.mov" (get-in outcome [:prep :analysis :fileName])))
    (is (= "2026-07-27T23:00:00+02:00" (get-in outcome [:prep :timing :candidates 0 :value])))
    (is (= "/v1/drive/playback/b-source" (get-in outcome [:prep :session :playbackUrl])))
    (is (= "video/quicktime" (get-in outcome [:prep :session :contentType])))
    (is (= 2222 (get-in outcome [:prep :session :size])))
    (is (= [{:fileId "timing-source-2"}] (get-in outcome [:requests :sessionRequests])))
    (is (= ["/v1/drive/playback/b-source"] (get-in outcome [:requests :rangeRequests])))
    (is (= "/v1/drive/playback/b-source" (:playerSrc outcome)))
    (is (= "video/quicktime" (get-in outcome [:range :rangeProbe :contentType])))
    (is (= (:beforeStaleMedia outcome)
           (get-in outcome [:range :media])))))
