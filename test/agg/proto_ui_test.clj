(ns agg.proto-ui-test
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.browser-process :as browser-process]
            [agg.http-test-support :as test-http]
            [agg.proto.core :as proto]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
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

(def browser-fixture-timeout-ms 15000)

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
                                1000
                                browser-fixture-timeout-ms
                                [])
      (finally
        (.delete temp)))))

(defn- proto-page-browser-outcome
  [{:keys [can-play-type webcodecs? timing-response]}]
  (let [page (proto/page {:user {:email "owner@example.com"}
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
         "if(path==='/v1/drive/playback-analyses'){window.__protoState.analysisRequests.push(JSON.parse(options.body));return Promise.resolve({ok:true,status:200,json:()=>Promise.resolve({fileName:'timing-ride.mp4',evidence:{container:{format:'mp4',majorBrand:'isom'},video:{codec:'h264',codecTag:'avc1',profile:'High',pixelFormat:'yuv420p'},audio:{codec:'aac'}}})});}"
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
         "HTMLMediaElement.prototype.load=function(){this.__duration=125.5;this.dispatchEvent(new Event('loadedmetadata'));};"
         "HTMLMediaElement.prototype.play=function(){this.__paused=false;this.dispatchEvent(new Event('play'));return Promise.resolve();};"
         "HTMLMediaElement.prototype.pause=function(){this.__paused=true;this.dispatchEvent(new Event('pause'));};"
         "</script>")
        scenario
        (str
         "<script>"
         "function recordOutcome(outcome){const bytes=new TextEncoder().encode(JSON.stringify(outcome));document.body.dataset.outcome=btoa(String.fromCharCode(...bytes));}"
         "window.addEventListener('load',()=>{setTimeout(async()=>{try{const first=document.querySelector('#source-list button');first.click();await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));const video=document.getElementById('proto-player');video.__bufferedRanges=[[0,30],[60,90]];video.dispatchEvent(new Event('progress'));recordOutcome({sourceStatus:document.getElementById('source-status').textContent,playerStatus:document.getElementById('player-status').textContent,selectedTitle:document.getElementById('selected-title').textContent,summary:{listing:document.getElementById('summary-listing').textContent,file:document.getElementById('summary-file').textContent,mime:document.getElementById('summary-mime').textContent,duration:document.getElementById('summary-duration').textContent,frameSize:document.getElementById('summary-size').textContent},timing:{start:document.getElementById('timing-start').textContent,end:document.getElementById('timing-end').textContent,state:document.getElementById('timing-state').textContent,confidence:document.getElementById('timing-confidence').textContent},prep:JSON.parse(document.getElementById('prep-debug').textContent),range:JSON.parse(document.getElementById('range-debug').textContent),requests:window.__protoState});}catch(error){recordOutcome({error:error.message});}},0);},{once:true});"
         "</script>")
        html (-> page
                 (str/replace "</head>" (str bootstrap "</head>"))
                 (str/replace "</body>" (str scenario "</body>"))
                 (str/replace "<body " "<body data-outcome=\"\">"))]
    (browser-outcome
     "agg-proto-browser-"
     "Proto playback browser regression requires Chrome or Chromium"
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
            listing (request! port :get "/v1/proto/sources" nil
                              {"Cookie" owner-cookie})]
        (is (= 200 (.statusCode page)))
        (is (.contains ^String (.body page)
                       "Timing workspace playback prototype"))
        (is (.contains ^String (.body page)
                       "proto.alphacompose.com"))
        (is (not (.contains ^String (.body page) "Create finished video")))
        (is (= 200 (.statusCode listing)))
        (is (= proto-sources
               (json/read-str (.body listing) :key-fn keyword))))
      (finally
        (.close server)))))

(deftest proto-page-prepares-supported-playback-and-exposes-bounded-debug
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type "probably"
                  :webcodecs? false
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
    (is (str/includes? (:playerStatus outcome) "Private playback prepared"))
    (is (= "folder-enumeration" (get-in outcome [:summary :listing])))
    (is (= "timing-ride.mp4" (get-in outcome [:summary :file])))
    (is (= "video/mp4" (get-in outcome [:summary :mime])))
    (is (= "Candidate" (get-in outcome [:timing :state])))
    (is (= "2026-07-27T22:00:00+02:00" (get-in outcome [:timing :start])))
    (is (= true (get-in outcome [:prep :support :supported])))
    (is (= "/v1/drive/playback/00000000-0000-0000-0000-000000000115"
           (get-in outcome [:prep :session :playbackUrl])))
    (is (= ["bytes=0-4095"] (get-in outcome [:requests :rangeRequests])))
    (is (= "bytes 0-4095/8192" (get-in outcome [:range :rangeProbe :contentRange])))
    (is (= [[0 30] [60 90]] (get-in outcome [:range :media :buffered])))))

(deftest proto-page-keeps-unsupported-sources-listed-with-explicit-evidence
  (let [outcome (proto-page-browser-outcome
                 {:can-play-type ""
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
    (is (str/includes? (:playerStatus outcome)
                       "could not prove direct playback support"))
    (is (= false (get-in outcome [:prep :support :supported])))
    (is (= "Duration only" (get-in outcome [:timing :state])))
    (is (= [] (get-in outcome [:requests :sessionRequests])))
    (is (nil? (get-in outcome [:range :rangeProbe])))))
