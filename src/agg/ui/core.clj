(ns agg.ui.core
  (:require [agg.admin.core :as admin]
            [agg.drive.core :as drive]
            [agg.jobs.lifecycle :as jobs]
            [agg.ui.project :as project]
            [agg.ui.wizard :as wizard]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn escape-html [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

(defn- minor-units->pln [minor-units]
  (let [major (quot minor-units 100)
        minor (mod minor-units 100)]
    (str major "." (when (< minor 10) "0") minor)))

(defn- contextual-help-link [fragment accessible-name]
  (str "<a class=\"contextual-help\" href=\"/faq#" fragment
       "\" aria-label=\"" (escape-html accessible-name)
       "\"><span class=\"contextual-help-mark\" aria-hidden=\"true\">"
       "<span>?</span></span></a>"))

(defn- preview-admission-costs []
  {:preview (minor-units->pln
             jobs/default-preview-reservation-minor-units)
   :total (minor-units->pln
           jobs/default-preview-plus-render-exposure-minor-units)})

(defn- preview-admission-disclosure []
  (let [{:keys [preview total]} (preview-admission-costs)]
    (str "<p class=\"hint preview-admission-cost\"><span class=\"help-label\">"
         "<strong>Admission cost:</strong>"
         (contextual-help-link "preview-admission-cost"
                               "Learn about Preview admission cost")
         "</span> "
         "Each Preview attempt reserves up to PLN " preview ". "
         "Preview plus one Submit reserves up to PLN " total ". "
         "Reservations remain counted after success, failure, cancellation, or expiry. "
         "Retrying Preview reserves another PLN " preview ".</p>")))

(defn- preview-admission-faq-answer []
  (let [{:keys [preview total]} (preview-admission-costs)]
    (str "<p>Preview uses cloud rendering capacity, so Alpha Compose reserves a "
         "bounded amount against its monthly admission budget before starting. "
         "Each Preview attempt reserves up to PLN " preview ". "
         "Preview plus one Submit reserves up to PLN " total ". "
         "Reservations remain counted after success, failure, cancellation, or expiry. "
         "Retrying Preview makes a new reservation of up to PLN " preview ".</p>")))

(def ^:private faq-categories
  [{:fragment "category-what-alpha-compose-makes"
    :title "What Alpha Compose makes"
    :questions
    [{:fragment "what-alpha-compose-does"
      :question "What does Alpha Compose do?"
      :answer
      (str "<p>Alpha Compose combines a compatible activity video with supported activity "
           "data. It adds a heart-rate graph, time readouts, and a generated heartbeat "
           "soundtrack, lets you preview key moments, and creates a finished video.</p>")}
     {:fragment "beyond-freediving"
      :question "Is Alpha Compose only for freediving?"
      :answer
      (str "<p>No. Alpha Compose grew from freediving, but it is not limited to freediving. "
           "You can use any compatible activity video and supported activity data that "
           "line up on a shared timeline.</p>")}
     {:fragment "progress-over-time"
      :question "Can Alpha Compose compare my progress over time?"
      :answer
      (str "<p>Saved videos can help you notice changes over time for yourself. "
           "Alpha Compose does not store sessions, analyze trends, or compare activities. "
           "Any comparison is one you make from the finished videos you keep.</p>")}
     {:fragment "preview-admission-cost"
      :question "Why does Preview have an admission cost?"
      :answer (preview-admission-faq-answer)}]}
   {:fragment "category-heart-rate-and-heartbeat"
    :title "Heart rate and the heartbeat sound"
    :questions
    [{:fragment "why-show-heart-rate"
      :question "Why put heart rate on a workout video?"
      :answer
      (str "<p>Heart rate can show your physiological response during a memorable moment. "
           "Seeing that response alongside the action, and hearing its pace, can make "
           "reliving the activity feel more immediate.</p>")}
     {:fragment "generated-heartbeat-sound"
      :question "Is the heartbeat sound a recording of my heart?"
      :answer
      (str "<p>No. The heartbeat sound is generated from your recorded heart-rate data "
           "and paced from those samples. It is not a recording of your heart or other "
           "audio captured by a medical device.</p>")}
     {:fragment "audio-options"
      :question "Can I keep the source audio, use only the heartbeat, or combine them?"
      :answer
      (str "<p>Yes. When you make a finished video, you can keep only the source audio, "
           "use only the generated heartbeat, or combine the source audio and heartbeat.</p>")}]}
   {:fragment "category-supported-activity-data"
    :title "Supported activity data"
    :questions
    [{:fragment "supported-activity-data"
      :question "What activity data is supported today?"
      :answer
      (str "<p>Heart rate is the primary supported graph. Current heart-rate inputs are "
           "Polar CSV, Garmin FIT, and OxiWear heart-rate CSV. Each input must include "
           "timestamps so it can be synchronized with the video.</p>")}
     {:fragment "oxygen-saturation-support"
      :question "Does Alpha Compose support oxygen saturation?"
      :answer
      (str "<p>Yes, as an optional addition. Compatible heart-rate renders can include "
           "optional OxiWear SpO2 activity data and display oxygen saturation alongside "
           "the primary heart-rate graph.</p>")}
     {:fragment "compatible-activity-export"
      :question "How do I export a compatible activity file?"
      :answer
      (str "<p>For Garmin Connect, open the activity and choose Export Original. "
           "That usually gives you the device's original FIT file. For Polar Flow, "
           "open one session and export a CSV file. Alpha Compose reads one activity "
           "at a time, so prefer an individual session export over a bulk archive.</p>"
           "<p>Strava's Export Original can work when the original uploaded file still "
           "contains compatible heart-rate data, but Alpha Compose cannot recover "
           "heart-rate samples from every Strava activity page or post-processed export.</p>"
           "<p>Apple Health exports all data as XML, and Health Connect backups export "
           "a Health Connect.zip archive. Those are not supported "
           "activity-file inputs for Alpha Compose today.</p>"
           "<ul><li><a href=\"https://support.garmin.com/en-US/?faq=W1TvTPW8JZ6LfJSfK512Q8\">Garmin Connect Export Original</a></li>"
           "<li><a href=\"https://support.polar.com/us-en/export-training-sessions-flow\">Polar Flow individual session export</a></li>"
           "<li><a href=\"https://support.strava.com/en-us/articles/15401919-exporting-your-data-and-bulk-export\">Strava Export Original</a></li>"
           "<li><a href=\"https://support.apple.com/guide/iphone/share-your-health-data-iph5ede58c3d/ios\">Apple Health XML export</a></li>"
           "<li><a href=\"https://support.google.com/android/answer/15323271?hl=en\">Health Connect backup and restore</a></li></ul>")}
     {:fragment "future-graphs"
      :question "Will other graphs be supported?"
      :answer
      (str "<p>Other activity-data graphs are a possible future direction, but there "
           "are no announced data types, dates, or delivery promises.</p>")}]}
   {:fragment "category-files-and-synchronization"
    :title "Files and synchronization"
    :questions
    [{:fragment "file-sources"
      :question "Where are my video and activity-data files read from?"
      :answer
      (str "<p>You choose a source video from Google Drive. Your browser reads the "
           "activity-data file you choose and sends the required data for your render. "
           "Alpha Compose does not search your device or the rest of your Drive.</p>")}
     {:fragment "video-timezone"
      :question "Which timezone should I choose for my video?"
      :answer
      (str "<p>Choose the IANA timezone where the video was recorded, such as "
           "<code>Europe/Warsaw</code>. An embedded recording timestamp may contain "
           "only a UTC offset, which does not identify daylight-saving rules, so "
           "Alpha Compose asks you to confirm or select the timezone. A shared clock "
           "uses the confirmed video clock. With different clocks, timeline labels "
           "start as elapsed time and change to derived synced recording time after "
           "you match a source frame to activity data. Alpha Compose never treats the "
           "Google Drive upload time as the recording time.</p>")}
     {:fragment "which-devices-should-i-synchronize"
      :question "Which devices should I synchronize?"
      :answer
      (str "<p>Before you record, identify every device whose timestamps matter. "
           "The activity-data recorder is usually a watch or phone. Include every "
           "camera that may be used for footage you want to compose. If a phone is "
           "used only to set a watch or camera clock, it matters too because it "
           "supplies that time.</p>"
           "<p>For synchronization, trust the time in your activity data. Your watch "
           "or phone normally sets its clock automatically from a network, a paired "
           "phone, location services, or satellite signals. Camera clocks are easier "
           "to leave wrong and may drift.</p>"
           "<p>When Alpha Compose says only <code>time</code> or <code>current "
           "time</code>, it means activity-device time. Exceptions are named "
           "explicitly as <code>video time</code>, <code>video recording start</code>, "
           "<code>video timezone</code>, or <code>output clock</code>.</p>")}
     {:fragment "how-should-i-synchronize-devices-before-recording"
      :question "How should I synchronize devices before recording?"
      :answer
      (str "<p>Start with the simple path. If the camera and activity device were "
           "synchronized before recording and still share one clock, choose "
           "<strong>Camera and activity devices used the same clock</strong> in "
           "Alpha Compose. You can skip the timeline synchronization point.</p>"
           "<p><strong>Checklist before recording</strong></p>"
           "<ul><li>Enable automatic date and time on the phone that records activity "
           "data or syncs your other devices.</li>"
           "<li>Sync a Garmin watch from its paired phone, or let a GPS-capable model "
           "acquire satellites according to Garmin's guidance.</li>"
           "<li>Connect a GoPro or DJI camera to its official phone app, or follow "
           "that model's clock or timecode instructions.</li>"
           "<li>Check the displayed clocks immediately before recording instead of "
           "assuming an earlier sync is still correct.</li>"
           "<li>Prefer the same timezone on every device because it reduces entry "
           "mistakes. Matching timezones alone does not synchronize clocks, and "
           "different timezones can still represent the same instant.</li></ul>"
           "<p>Examples: <strong>Garmin watch + DJI camera</strong>, or "
           "<strong>phone recording activity data + GoPro</strong>. Call this "
           "<strong>automatic time</strong>, not GPS time as a universal term. "
           "Depending on the device, time may come from the network, location "
           "services, a paired phone, or satellite signals.</p>"
           "<p>Menus and capabilities vary by model and firmware. Use these official "
           "guides only if you need model-specific help:</p>"
           "<ul><li><a href=\"https://support.google.com/android/answer/2841106?hl=en\">Android automatic date, time, and timezone</a></li>"
           "<li><a href=\"https://support.apple.com/en-us/101619\">iPhone automatic date, time, and timezone</a></li>"
           "<li><a href=\"https://support.garmin.com/en-US/?faq=z9ue29cdcD8SHu28Lo8VY5\">Garmin watch time troubleshooting and synchronization</a></li>"
           "<li><a href=\"https://gopro.com/help/productmanuals\">GoPro product manuals</a></li>"
           "<li><a href=\"https://static.gopro.com/assets/blta2b8522e5372af40/blte32e2783ff44e36a/68c27e50d9e51a0e3481b803/MAX2_UM_en-US_REVA.pdf\">GoPro MAX2 manual, including Quik timecode synchronization for MAX2, HERO13 Black, and HERO12 Black</a></li>"
           "<li><a href=\"https://repair.dji.com/help/content?customId=01700006936&amp;lang=en&amp;paperDocType=ARTICLE&amp;re=US&amp;spaceId=17\">DJI Osmo Action date and time</a></li>"
           "<li><a href=\"https://repair.dji.com/help/content?customId=en-us03400007306&amp;documentType=artical&amp;lang=en&amp;paperDocType=paper&amp;re=US&amp;spaceId=34\">DJI timecode introduction and synchronization</a></li></ul>")}
     {:fragment "when-do-i-need-a-matching-moment"
      :question "When do I need a matching moment on the timeline?"
      :answer
      (str "<p>The timeline synchronization point is optional. Use it only when the "
           "camera and activity device did not share one clock, or when their recorded "
           "timestamps need a known correction.</p>"
           "<p>Immediately before the activity, film the activity device's clock with "
           "seconds visible. That filmed clock gives you a <strong>matching "
           "moment</strong>: one recognizable video frame and the activity-device time "
           "for that same instant.</p>"
           "<p>If you later choose <strong>Camera and activity devices used different "
           "clocks</strong>, drag to that filmed frame and enter the activity-device "
           "time you saw. If your devices were synchronized before recording and still "
           "share one clock, you do not need this step.</p>"
           "<p>If practical, film the clock again after a long recording. If the two "
           "shots reveal drift, Alpha Compose currently applies one fixed offset. "
           "Choose a matching moment close to the output section you are composing.</p>")}
     {:fragment "why-can-camera-timecode-differ-from-clock-time"
      :question "Why can camera timecode differ from clock time?"
      :answer
      (str "<p>The camera's system clock, recording timestamp, and production "
           "timecode are different things. The system clock is the date and time shown "
           "in menus. The recording timestamp is metadata saved with a clip. "
           "Production timecode is a separate counter some cameras expose for editing "
           "workflows.</p>"
           "<p>Production timecode can diverge from ordinary clock time, including "
           "frame-rate-related behavior documented by DJI. Alpha Compose does not "
           "currently import, interpret, or correct camera timecode. Sync or verify "
           "the camera against the activity device immediately before recording.</p>")}
     {:fragment "synchronizing-data-and-camera"
      :question "Why do I need to synchronize the activity data and camera time?"
      :answer
      (str "<p>Correct synchronization places each heart-rate or SpO2 value at the "
           "right video moment. Choose <strong>Camera and activity devices used the "
           "same clock</strong> when the camera and activity device were synchronized "
           "before recording and their timestamps still share one clock. Alpha Compose "
           "then makes no synchronization adjustment and needs no timeline "
           "synchronization point. Choose <strong>Camera and activity devices used "
           "different clocks</strong> only when they differed, then identify one "
           "matching moment so Alpha Compose can correct one fixed clock offset.</p>"
           "<p>For different clocks, a filmed clock shot is the easiest matching "
           "moment. Drag the marker on the full video timeline to the recognizable "
           "frame and enter the activity-device time for that same instant. You do "
           "not need to enter an absolute camera time. "
           "The marker can be before, inside, or after the selected output. Left or Right "
           "moves one 40 ms frame, Shift+Left or Shift+Right moves 10 frames, and Home "
           "or End moves to a source bound.</p>")}
     {:fragment "output-file"
      :question "What output does Alpha Compose create?"
      :answer
      (str "<p>With a source video, Alpha Compose creates a finished H.264 MP4 or "
           "ProRes 422 MOV. It can also create a transparent ProRes 4444 MOV overlay "
           "for a separate editing workflow. See the <a href=\"/openapi.yaml\">technical "
           "API documentation</a> for file-format details.</p>")}
     {:fragment "transparent-overlay-editors"
      :question "Which editor should I use for a transparent overlay?"
      :answer
      (str "<p>DaVinci Resolve is the recommended first choice for transparent "
           "overlays. It is the editor used in Alpha Compose's manual acceptance "
           "check for transparent ProRes 4444 exports.</p>"
           "<p>If you already pay for another editor, current officially documented "
           "alternatives with alpha-capable ProRes workflows include Adobe Premiere "
           "Pro and Final Cut Pro. Alpha Compose does not currently publish support "
           "claims for unpaid editors or community-only workflows.</p>")}
     {:fragment "completed-output-playback"
      :question "Which finished outputs can I play in the browser?"
      :answer
      (str "<p>Completed H.264 MP4 outputs can open in the browser through Alpha "
           "Compose's private completed-output playback flow.</p>"
           "<p>ProRes outputs remain download-first files for desktop editors. "
           "That includes transparent ProRes 4444 overlays and finished ProRes 422 "
           "videos.</p>")}
     {:fragment "ai-assisted-preparation"
      :question "Can AI help me prepare Project JSON, FFmpeg commands, or editor steps?"
      :answer
      (str "<p>Yes, but the tools are different. Codex can help if you give it access "
           "to the relevant files and docs. Claude can use code execution and file "
           "creation inside a chat to generate or transform files. Claude Code is a "
           "separate terminal and IDE tool with its own plan and permission model. "
           "FFmpeg itself is just the command-line media tool, not an AI assistant.</p>"
           "<p>Current plan availability and limits can change. Verify the current OpenAI or "
           "Anthropic plan pages before relying on a specific allowance, connector, "
           "or coding surface.</p>")}]}
   {:fragment "category-google-drive-and-privacy"
    :title "Google Drive and privacy"
    :questions
    [{:fragment "google-drive-access"
      :question "What can Alpha Compose access in Google Drive?"
      :answer
      (str "<p>Alpha Compose uses the restricted <code>drive.file</code> permission. "
           "It can access files you explicitly choose and files it creates for you. "
           "It cannot browse or read the rest of your Google Drive.</p>"
           "<p>Local videos upload directly and resumably from your browser to Google "
           "Drive. Source bytes never pass through Alpha Compose, and Alpha Compose "
           "does not apply a whole-file upload size limit. After upload, Drive metadata "
           "is validated before the normal player and timeline flow begins.</p>"
           "<p>If Drive rejects an upload without a specific reason, check your Drive "
           "storage and Workspace policy, or upload the video at "
           "<a href=\"https://drive.google.com\" target=\"_blank\" rel=\"noopener\">"
           "drive.google.com</a> and return here to select it.</p>")}
     {:fragment "finished-video-location"
      :question "Where is my finished video saved?"
      :answer
      (str "<p>The finished video is saved in your Alpha Compose folder in My Drive, "
           "including when the source video came from a Shared Drive or was shared with "
           "you. It remains in your Drive until you delete it.</p>")}
     {:fragment "project-json"
      :question "What is Project JSON and when does Alpha Compose save it?"
      :answer
      (str "<p>Project JSON is a separate browser workflow envelope, not the API "
           "request itself. The public API accepts only the nested renderRequest.</p>"
           "<p>Alpha Compose creates or stores Project JSON only when you explicitly "
           "download, copy, upload, or paste it. It can include your private activity "
           "data and bounded embedded assets, but it excludes credentials, CSRF "
           "values, signed URLs, preview images, playback state, and job results.</p>")}
     {:fragment "source-and-activity-data-retention"
      :question "Does Alpha Compose retain my source video or log my activity data?"
      :answer
      (str "<p>No source-video copy is retained: the source video is streamed from "
           "Google Drive into the renderer and is never persisted by Alpha Compose. "
           "Activity data may be held in encrypted temporary request objects while the "
           "render is processed; those objects are deleted after 24 hours. The service "
           "does not log activity-data values. Read the <a href=\"/privacy\">complete "
           "privacy policy</a> for retention details.</p>")}]}
   {:fragment "category-safety-and-medical-limitations"
    :title "Safety and medical limitations"
    :questions
    [{:fragment "medical-or-training-advice"
      :question "Is Alpha Compose medical or training advice?"
      :answer
      (str "<p>No. Alpha Compose output is not medical advice or training advice. "
           "Do not use it to diagnose a condition, decide whether an activity is safe, "
           "or replace qualified professional guidance.</p>")}
     {:fragment "displayed-value-accuracy"
      :question "How accurate are the displayed values?"
      :answer
      (str "<p>The output reflects the sensor data you supply and the synchronization "
           "you choose. It does not infer emotions, health, or training readiness, "
           "and it does not validate the sensor. Check the source data and timing before "
           "relying on a displayed value.</p>")}]}])

(def ^:private faq-by-fragment
  (into {}
        (map (juxt :fragment identity))
        (mapcat :questions faq-categories)))

(def ^:private compose-contextual-help-fragments
  ["google-drive-access"
   "audio-options"
   "supported-activity-data"
   "video-timezone"
   "how-should-i-synchronize-devices-before-recording"
   "oxygen-saturation-support"
   "preview-admission-cost"])

(defn- faq-question [{:keys [fragment question answer]}]
  (str "<details class=\"faq-question\" id=\"" fragment "\">"
       "<summary><h3>" question "</h3></summary>"
       "<div class=\"faq-answer\">" answer
       "<p class=\"faq-permalink\"><a href=\"#" fragment
       "\" aria-label=\"Link to: " question "\">Link to this question</a></p>"
       "</div></details>"))

(defn- faq-category [{:keys [fragment title questions]}]
  (str "<section class=\"faq-category\" aria-labelledby=\"" fragment "\">"
       "<h2 id=\"" fragment "\">" title "</h2>"
       (apply str (map faq-question questions))
       "</section>"))

(defn- contextual-help-dialog []
  (str
   "<dialog id=\"contextual-help-dialog\" aria-labelledby=\"contextual-help-title\">"
   "<div class=\"contextual-help-head\"><h2 id=\"contextual-help-title\"></h2>"
   "<button type=\"button\" class=\"contextual-help-close\">Close help</button></div>"
   "<div id=\"contextual-help-answer\" class=\"contextual-help-answer\"></div>"
   "<p class=\"contextual-help-actions\"><a id=\"contextual-help-full\" href=\"/faq\" "
   "target=\"_blank\" rel=\"noopener\">Open full FAQ in a new tab</a></p>"
   (apply str
          (for [fragment compose-contextual-help-fragments
                :let [{:keys [question answer]} (get faq-by-fragment fragment)]]
            (str "<template data-contextual-help-fragment=\""
                 (escape-html fragment)
                 "\" data-contextual-help-question=\""
                 (escape-html question)
                 "\"><div class=\"contextual-help-copy\">"
                 answer
                 "</div></template>")))
   "</dialog>"))

(defn- icon-links []
  (str "<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"
       "<link rel=\"icon\" href=\"/favicon-32.png\" type=\"image/png\" sizes=\"32x32\">"
       "<link rel=\"icon\" href=\"/favicon-16.png\" type=\"image/png\" sizes=\"16x16\">"
       "<link rel=\"apple-touch-icon\" sizes=\"180x180\" href=\"/apple-touch-icon.png\">"
       "<meta name=\"theme-color\" content=\"#031225\">"))

(def ^:private product-nav-items
  [[:faq "/faq" "FAQ"]
   [:privacy "/privacy" "Privacy"]
   [:terms "/terms" "Terms"]])

(defn product-header
  ([]
   (product-header nil))
  ([active-nav]
   (str
    "<header class=\"product-header\"><a class=\"brand\" href=\"/\">Alpha Compose</a>"
    "<nav aria-label=\"Product\">"
    (apply str
           (for [[nav-key path label] product-nav-items]
             (str "<a href=\"" path "\""
                  (when (= active-nav nav-key)
                    " aria-current=\"page\"")
                  ">" label "</a>")))
    "</nav></header>")))

(defn theme-style []
  (str
   ":root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45;"
   "--color-background:#031225;--color-surface:#091d34f2;--color-surface-strong:#0b2440;"
   "--color-surface-soft:#102b49;--color-border:#65d6ff3d;--color-border-strong:#65d6ff73;"
   "--color-text:#f4f9ff;--color-muted:#b8cce0;--color-subtle:#8ca9c2;"
   "--color-link:#7ee4ff;--color-accent:#65d6ff;--color-accent-ink:#031225;"
   "--color-danger:#ff8294;--color-success:#65e1ad;--color-warning:#ffd27a;"
   "--shadow-surface:0 1.25rem 3.5rem #00081466;"
   "color:var(--color-text);background:var(--color-background)}"
   "html{min-width:0;max-width:100%;background:var(--color-background)}"
   "*,*::before,*::after{box-sizing:border-box}"
   "body{margin:0;min-width:0;max-width:100%;overflow-x:clip;color:var(--color-text);"
   "background-color:var(--color-background);"
   "background-image:linear-gradient(180deg,#031225c7 0%,#031225e8 48rem,#031225 78rem),url('/telemetry-background.webp');"
   "background-repeat:no-repeat,no-repeat;background-position:center top,center top;"
   "background-size:100% 100%,min(64rem,100vw) auto}"
   "a{color:var(--color-link);text-underline-offset:.18em}a:hover{color:#b8f2ff}"
   "button,.button,.cta,input,select,textarea,summary,a{touch-action:manipulation}"
   ":focus,:focus-visible{outline:3px solid var(--color-warning);outline-offset:3px}"
   "::selection{color:var(--color-text);background:#be334f99}"
   ".shell,.shell>*{min-width:0}.shell>header{padding:clamp(.9rem,2vw,1.25rem);background:var(--color-surface);border:1px solid var(--color-border);border-radius:1rem;box-shadow:var(--shadow-surface)}.muted,.hint{color:var(--color-muted)}"
   ".field-hint{display:block;color:var(--color-muted);font-weight:400;margin-top:.2rem}"
   ".product-header{display:flex;align-items:center;justify-content:space-between;gap:1rem;flex-wrap:wrap;margin:1rem 0 2rem}"
   ".product-header .brand{color:var(--color-text);font-weight:800;letter-spacing:-.03em;text-decoration:none}"
   ".product-header nav{display:flex;gap:1rem;flex-wrap:wrap}.product-header nav a{color:var(--color-link)}"
   ".product-header nav a[aria-current=\"page\"]{color:var(--color-text);font-weight:800;text-decoration-line:underline;text-decoration-thickness:.18rem;text-underline-offset:.28rem}"
   ".help-heading,.toggle-help{display:flex}.help-label{display:inline-flex}.help-heading,.help-label,.toggle-help{align-items:center;gap:.4rem;max-width:100%;min-width:0}"
   ".help-heading{margin-bottom:.35rem}.help-heading h2{margin:0}.help-label>label,.help-label>strong,.toggle-help>.toggle{min-width:0}.toggle-help{flex-wrap:nowrap}"
   ".contextual-help{display:inline-flex;align-items:center;justify-content:flex-start;flex:0 0 2rem;width:2rem;height:2rem;color:var(--color-link);font-weight:900;line-height:1;text-decoration:none;vertical-align:middle}"
   ".help-heading>.contextual-help{font-size:1.25em}.help-label>label~.contextual-help,.toggle-help>.contextual-help{font-size:.9em}"
   ".contextual-help-mark{display:inline-flex;align-items:center;justify-content:center;flex:0 0 .8em;width:.8em;height:.8em;border:.08em solid currentColor;border-radius:50%;background:#06182b}"
   ".contextual-help-mark>span{font-size:.65em;line-height:1}"
   ".contextual-help:hover{color:var(--color-accent-ink)}.contextual-help:hover .contextual-help-mark{background:var(--color-accent);border-color:var(--color-accent)}"
   ".eyebrow{color:var(--color-accent)}.step{color:var(--color-subtle)}"
   ".card,.hero-card,.drive-card,.trace-preview,.preview-pending,.preview-error,.preview-stale,.preview-empty,.log-entry{"
   "background:var(--color-surface);border-color:var(--color-border);box-shadow:var(--shadow-surface)}"
   ".hero-card,.drive-card{background:var(--color-surface-strong);border:1px solid var(--color-border-strong)}"
   ".hero-card .muted,.drive-card .muted,.hero-card-note{color:#c8ddef}"
   "button,.button,.cta{background:var(--color-surface-soft);color:var(--color-text);border:1px solid var(--color-border);text-decoration:none}"
   ".button.primary,button.primary,.cta,.primary{background:var(--color-accent);color:var(--color-accent-ink);border-color:var(--color-accent);box-shadow:0 .4rem 1.2rem #0fc3ff2e}"
   ".button:hover,button:hover,.cta:hover{filter:brightness(1.08)}"
   ".button.primary:disabled,button.primary:disabled,.button.primary:disabled:hover,button.primary:disabled:hover{background:#485e73;color:#d2dce6;border-color:#485e73;box-shadow:none;filter:none}"
   "input,select,textarea{background:#06182b;color:var(--color-text);border-color:#6b8ba5}"
   "input::placeholder,textarea::placeholder{color:#8fa9bf;opacity:1}"
   "input[readonly]{background:#10263c;color:#c1d3e4}"
   "input[type=file],.source-box,.log-entry pre{background:#06182b}"
   "input:focus,select:focus,textarea:focus{outline:3px solid var(--color-warning);border-color:var(--color-warning)}"
   ".source-box{border-color:var(--color-border-strong)}.source-box textarea{background:#06182b;color:var(--color-text)}"
   ".optional,.log-entry{border-color:var(--color-border)}"
   ".status{color:var(--color-muted)}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
   "details summary,nav a,footer a,.drive-card a{color:var(--color-link)}"
   ".results img{border-color:var(--color-border);background:#06182b}"
   ".preview-warning{background:#33270d;border-color:#b98c32;color:#ffe3a3}"
   ".preview-cell .preview-open{background:#06182b}"
   "#preview-dialog{color:var(--color-text);background:var(--color-surface-strong);border:1px solid var(--color-border-strong)}"
   "#preview-dialog::backdrop{background:#010813e6}"
   ".notice{border-color:var(--color-warning);background:#2a230f}.notice code{color:var(--color-text)}"
   ".log-level{background:#173b5a;color:#d9f5ff}.log-entry time,.log-entry dt,.empty,footer{color:var(--color-muted)}"
   "@media(forced-colors:active){.product-header nav a[aria-current=\"page\"]{text-decoration-thickness:.18rem}}"
   "@media(max-width:680px){body{background-size:100% 100%,100vw auto;background-position:center top,center top}.product-header{align-items:flex-start}.product-header nav{width:100%}}"))

(defn- title-case [value]
  (-> value
      (str/replace #"[_-]+" " ")
      str/capitalize))

(defn token-panel
  ([tokens]
   (token-panel tokens nil))
  ([tokens created]
   (str
    "<section id=\"tokens\"><h2>Personal API tokens</h2>"
    (when created
      (str "<div class=\"notice\"><p>Copy this token now. It will not be shown again.</p>"
           "<code>" (escape-html (:token created)) "</code></div>"))
    "<form hx-post=\"/ui/tokens\" hx-target=\"#tokens\" hx-swap=\"outerHTML\">"
    "<label>Token name <input name=\"name\" maxlength=\"80\" required></label>"
    "<button type=\"submit\">Create token</button></form><ul>"
    (apply str
           (for [{:keys [id name createdAt revoked]} tokens]
             (str "<li><strong>" (escape-html name) "</strong> · "
                  (escape-html createdAt)
                  (if revoked
                    " · Revoked"
                    (str " <button type=\"button\" hx-post=\"/ui/tokens/"
                         (escape-html id)
                         "/revoke\" hx-target=\"#tokens\" hx-swap=\"outerHTML\">"
                         "Revoke</button>"))
                  "</li>")))
    "</ul></section>")))

(defn member-panel [members]
  (str
   "<section id=\"members\"><h2>Member administration</h2>"
   "<form hx-post=\"/ui/admin/members\" hx-target=\"#members\" hx-swap=\"outerHTML\">"
   "<label>Member email <input type=\"email\" name=\"email\" maxlength=\"254\" required></label>"
   "<button type=\"submit\">Add member</button></form><ul>"
   (apply str
          (for [{:keys [email role status]} members]
            (str "<li><strong>" (escape-html email) "</strong> · "
                 (escape-html role) " · " (escape-html status)
                 (when (and (= "member" role) (= "active" status))
                   (str " <form class=\"inline\" hx-post=\"/ui/admin/members/revoke\" "
                        "hx-target=\"#members\" hx-swap=\"outerHTML\">"
                        "<input type=\"hidden\" name=\"email\" value=\""
                        (escape-html email) "\"><button type=\"submit\">Revoke</button></form>"))
                 "</li>")))
   "</ul></section>"))

(defn- management-link [href label description]
  (str "<a class=\"management-link\" href=\"" href "\"><strong>"
       (escape-html label)
       "</strong><span>" (escape-html description) "</span></a>"))

(defn- management-links-section
  [{:keys [tokens members logs-enabled? user]}]
  (let [links (cond-> []
                (some? tokens)
                (conj
                 (management-link "/ui/tokens"
                                  "Personal API tokens"
                                  "Create and revoke personal API tokens."))

                (and (admin/administrator? (:role user)) logs-enabled?)
                (conj
                 (management-link "/ui/admin/logs"
                                  "View operation logs"
                                  "Inspect recent structured service events."))

                (some? members)
                (conj
                 (management-link "/ui/admin/members"
                                  "Member admin"
                                  "Allowlist members and revoke access.")))]
    (when (seq links)
      (str "<section class=\"card management-links-card\"><div class=\"section-head\">"
           "<div><h2>Account and admin</h2><p class=\"muted\">Open the tools that match your access.</p></div>"
           "</div><div class=\"management-links\">"
           (apply str links)
           "</div></section>"))))

(defn- url-value [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- logs-query [{:keys [view severity component]}]
  (str "?view=" (url-value view)
       (when severity (str "&severity=" (url-value severity)))
       (when component (str "&component=" (url-value component)))))

(defn- selected-attribute [value selected]
  (when (= value selected) " selected"))

(defn- json-script [value]
  (-> (json/write-str value)
      (str/replace "<" "\\u003c")
      (str/replace ">" "\\u003e")
      (str/replace "&" "\\u0026")))

(defn picker-policy-script []
  (let [mime-types (str/join "," drive/supported-source-video-mime-types)]
    (str "const pickerMimeTypes=" (json-script mime-types) ";"
         "const pickerMimeTypeSet=new Set(pickerMimeTypes.split(','));")))

(defn picker-views-script []
  (str
   "function configureVideoDriveView(view){return view"
   ".setMimeTypes(pickerMimeTypes)"
   ".setIncludeFolders(true)"
   ".setSelectFolderEnabled(false)"
   ".setMode(google.picker.DocsViewMode.LIST);}"
   "const driveView=configureVideoDriveView(new google.picker.DocsView());"
   "const sharedDrivesView=configureVideoDriveView(new google.picker.DocsView())"
   ".setEnableDrives(true);"))

(defn- direct-drive-upload-script []
  (str
   "const uploadInput=document.getElementById('source-video-upload'),"
   "uploadStatus=document.getElementById('source-video-upload-status'),"
   "uploadProgress=document.getElementById('source-video-upload-progress'),"
   "cancelUpload=document.getElementById('cancel-source-video-upload'),"
   "retryUpload=document.getElementById('retry-source-video-upload');"
   "const directUploadUrl='https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id',"
   "uploadChunkBytes=8*1024*1024,"
   "ambiguousBrowserMimeTypes=new Set(['','application/octet-stream','binary/octet-stream','application/x-unknown']);"
   "const uploadMessages={"
   "unsupported:'Alpha Compose does not support this video type. Choose MP4, MOV, WebM, MPEG, OGG, AVI, or MKV.',"
   "storage:'Google Drive does not have enough available storage for this video. Free some space or choose a video already in Drive.',"
   "authorization:'Google Drive access expired. Reconnect Drive, then try the upload again.',"
   "rate:'Google Drive could not finish the upload. Wait a moment, then resume or try again.',"
   "transient:'Google Drive could not finish the upload. Wait a moment, then resume or try again.',"
   "network:'The network connection was interrupted. Resume the Google Drive upload when your connection returns.',"
   "policy:'Your Google Workspace policy blocked this upload. Ask your Workspace administrator, or upload the video at drive.google.com and return here to select it.',"
   "unknown:'Google Drive rejected the upload without a specific reason. Check your Drive storage and Workspace policy, or upload the video at drive.google.com and return here to select it.'};"
   "let uploadState={file:null,sessionUrl:null,offset:0,fileId:null,controller:null,cancelled:false,restarts:0};"
   "function showUploadStatus(message,type=''){uploadStatus.textContent=message;uploadStatus.className='status'+(type?' '+type:'');}"
   "function setUploadControls(active,retriable=false){cancelUpload.hidden=!active;cancelUpload.disabled=!active;retryUpload.hidden=!retriable;retryUpload.disabled=!retriable;uploadInput.disabled=active;}"
   "function updateUploadProgress(offset,total){const percent=total?Math.min(100,Math.floor(offset*100/total)):0;uploadProgress.hidden=false;uploadProgress.value=percent;uploadProgress.textContent=percent+'%';showUploadStatus('Uploading directly to Google Drive: '+percent+'%');}"
   "function uploadReason(payload){return payload?.error?.errors?.[0]?.reason||payload?.error?.status||payload?.error?.reason||'';}"
   "async function uploadFailureCategory(response){let payload=null;try{payload=await response.clone().json();}catch(_error){}const reason=String(uploadReason(payload));"
   "if(response.status===401||reason==='authError'||reason==='invalidCredentials'||reason==='UNAUTHENTICATED')return 'authorization';"
   "if(['storageQuotaExceeded','storageQuotaLimitExceeded'].includes(reason))return 'storage';"
   "if(['domainPolicy','fileOwnerNotMemberOfTeamDrive','teamDriveFileLimitExceeded','insufficientFilePermissions','PERMISSION_DENIED'].includes(reason))return 'policy';"
   "if(['rateLimitExceeded','userRateLimitExceeded','dailyLimitExceeded','quotaExceeded','RESOURCE_EXHAUSTED'].includes(reason))return 'rate';"
   "if(response.status>=500||['backendError','internalError','INTERNAL','UNAVAILABLE'].includes(reason))return 'transient';"
   "return 'unknown';}"
   "function uploadDiagnostic(category,phase='error'){reportDiagnostic(phase,'upload',category);}"
   "function failUpload(category){setUploadControls(false,category!=='unsupported');showUploadStatus(uploadMessages[category]||uploadMessages.unknown,'error');uploadDiagnostic(category);}"
   "function acknowledgedOffset(response,size){const range=response.headers.get('Range')||response.headers.get('range');if(!range)return 0;const match=/bytes=0-(\\d+)/i.exec(range);if(!match)return 0;return Math.min(size,Number(match[1])+1);}"
   "async function validateUploadedSource(fileId){const response=await fetch('/ui/project-source-validation',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':"
   "uploadCsrf},body:JSON.stringify({fileId})});"
   "if(!response.ok){let payload=null;try{payload=await response.json();}catch(_error){}if(response.status===401||payload?.error==='drive_grant_required')throw {uploadCategory:'authorization'};if(payload?.error==='invalid_source_video')throw {uploadCategory:'unsupported'};throw {uploadCategory:'unknown'};}"
   "const source=await response.json();if(!source||source.fileId!==fileId||typeof source.fileName!=='string'||!pickerMimeTypeSet.has(source.mimeType))throw {uploadCategory:'unsupported'};return {id:source.fileId,name:source.fileName,mimeType:source.mimeType};}"
   "async function completeDirectUpload(fileId){if(uploadState.fileId)return;const source=await validateUploadedSource(fileId);uploadState.fileId=fileId;acceptDriveSource(source,'upload');uploadProgress.value=100;uploadProgress.textContent='100%';showUploadStatus('Upload complete. Google Drive validated the video.','success');setUploadControls(false,false);uploadDiagnostic('selected','selected');}"
   "async function createUploadSession(){const file=uploadState.file,controller=new AbortController();uploadState.controller=controller;const response=await fetch(directUploadUrl,{method:'POST',signal:controller.signal,headers:{Authorization:'Bearer '+pickerConfig.accessToken,'Content-Type':'application/json; charset=UTF-8','X-Upload-Content-Type':file.type||'application/octet-stream','X-Upload-Content-Length':String(file.size)},body:JSON.stringify({name:file.name})});"
   "if(!response.ok)throw {uploadCategory:await uploadFailureCategory(response)};const location=response.headers.get('Location')||response.headers.get('location');if(!location||!/^https:\\/\\/([a-z0-9-]+\\.)*googleapis\\.com\\//i.test(location))throw {uploadCategory:'unknown'};uploadState.sessionUrl=location;uploadState.offset=0;}"
   "async function queryUploadSession(){const controller=new AbortController();uploadState.controller=controller;const response=await fetch(uploadState.sessionUrl,{method:'PUT',signal:controller.signal,headers:{Authorization:'Bearer '+pickerConfig.accessToken,'Content-Range':'bytes */'+uploadState.file.size}});"
   "if(response.status===308){uploadState.offset=acknowledgedOffset(response,uploadState.file.size);return false;}"
   "if(response.ok){const result=await response.json();if(typeof result?.id!=='string'||!result.id)throw {uploadCategory:'unknown'};await completeDirectUpload(result.id);return true;}"
   "if(response.status===404||response.status===410){uploadState.sessionUrl=null;uploadState.offset=0;return false;}throw {uploadCategory:await uploadFailureCategory(response)};}"
   "async function uploadRemaining(){const file=uploadState.file;while(uploadState.offset<file.size){if(uploadState.cancelled)return;const start=uploadState.offset,end=Math.min(file.size,start+uploadChunkBytes),controller=new AbortController();uploadState.controller=controller;const response=await fetch(uploadState.sessionUrl,{method:'PUT',signal:controller.signal,headers:{Authorization:'Bearer '+pickerConfig.accessToken,'Content-Type':file.type||'application/octet-stream','Content-Range':'bytes '+start+'-'+(end-1)+'/'+file.size},body:file.slice(start,end)});"
   "if(response.status===308){const next=acknowledgedOffset(response,file.size);if(next<=start)throw {uploadCategory:'transient'};uploadState.offset=next;updateUploadProgress(next,file.size);continue;}"
   "if(response.ok){const result=await response.json();if(typeof result?.id!=='string'||!result.id)throw {uploadCategory:'unknown'};uploadState.offset=file.size;updateUploadProgress(file.size,file.size);await completeDirectUpload(result.id);return;}"
   "if(response.status===404||response.status===410){if(uploadState.restarts>=1)throw {uploadCategory:'transient'};uploadState.restarts++;uploadState.sessionUrl=null;uploadState.offset=0;await createUploadSession();continue;}throw {uploadCategory:await uploadFailureCategory(response)};}}"
   "async function runUpload(resume=false){if(!pickerConfig||!uploadState.file)return;uploadState.cancelled=false;setUploadControls(true,false);try{if(resume&&uploadState.sessionUrl&&await queryUploadSession())return;if(!uploadState.sessionUrl)await createUploadSession();if(uploadState.cancelled)return;if(uploadState.file.size===0){if(!await queryUploadSession())throw {uploadCategory:'unknown'};return;}updateUploadProgress(uploadState.offset,uploadState.file.size);await uploadRemaining();}catch(error){if(uploadState.cancelled||error?.name==='AbortError'){setUploadControls(false,true);showUploadStatus('Upload paused. Resume when ready.');return;}failUpload(error?.uploadCategory||'network');}}"
   "uploadInput.addEventListener('change',()=>{const file=uploadInput.files&&uploadInput.files[0];uploadProgress.hidden=true;uploadProgress.value=0;uploadState={file,sessionUrl:null,offset:0,fileId:null,controller:null,cancelled:false,restarts:0};if(!file){setUploadControls(false,false);showUploadStatus('Choose a local video to upload directly to Google Drive.');return;}const browserMime=String(file.type||'').trim().toLowerCase();if(!ambiguousBrowserMimeTypes.has(browserMime)&&!pickerMimeTypeSet.has(browserMime)){failUpload('unsupported');return;}runUpload(false);});"
   "cancelUpload.addEventListener('click',()=>{uploadState.cancelled=true;uploadState.controller?.abort();setUploadControls(false,true);showUploadStatus('Upload paused. Resume when ready.');uploadDiagnostic('cancelled','cancelled');});"
   "retryUpload.addEventListener('click',()=>runUpload(true));"))

(defn- picker-script [picker-config]
  (let [config (when picker-config
                 {"accessToken" (:access-token picker-config)
                  "apiKey" (:api-key picker-config)
                  "appId" (:app-id picker-config)})]
    (str
     "(function(){"
     "const pickerConfig=" (json-script config) ";"
     (picker-policy-script)
     "const selection=document.getElementById('picker-selection');"
     "const uploadCsrf=" (json-script (:csrf picker-config)) ";"
     "function reportDiagnostic(phase,view='unknown',listState='unknown'){"
     "fetch('/v1/drive/picker/diagnostic',{method:'POST',credentials:'same-origin',keepalive:true,"
     "headers:{'Content-Type':'application/json','X-CSRF-Token':"
     (json-script (:csrf picker-config)) "},"
     "body:JSON.stringify({phase,view,listState})}).catch(()=>{});}"
     "function acceptDriveSource(file,view='drive'){"
     "chooseWizardOutcome('finished-video',false);"
     "document.getElementById('source-video-file-id').value=file.id;"
     "selection.textContent=file.name||'Selected video';"
     "acceptWizardSourceSelection();inspectRecordingClock(file);loadDrivePlayback(file);"
     "reportDiagnostic('selected',view,'selected');invalidatePreview();syncRequest(false);}"
     "function pickerCallback(data){"
     "if(data.action===google.picker.Action.LOADED){reportDiagnostic('loaded','drive','unknown');}"
     "if(data.action===google.picker.Action.PICKED){"
     "const d=data.docs&&data.docs[0];"
     "const file=d?{id:d.id,name:d.name,mimeType:d.mimeType}:null;"
     "if(!file||typeof file.id!=='string'||!file.id||typeof file.mimeType!=='string'||!pickerMimeTypeSet.has(file.mimeType)){"
     "selection.textContent='Choose a video file';reportDiagnostic('error','drive','unknown');return;}"
     "acceptDriveSource(file);picker.setVisible(false);}"
     "if(data.action===google.picker.Action.CANCEL){picker.setVisible(false);reportDiagnostic('cancelled','drive','unknown');}}"
     "let picker=null,pickerRequested=false,pickerLoading=false,pickerLoadTimer=null,pickerLoadAttempt=0;"
     "function failPickerInitialization(attempt){"
     "if(!pickerLoading||attempt!==pickerLoadAttempt)return;"
     "pickerLoading=false;picker=null;pickerRequested=false;"
     "if(pickerLoadTimer){clearTimeout(pickerLoadTimer);pickerLoadTimer=null;}"
     "selection.textContent='Google Drive Picker failed to load. Try again.';"
     "reportDiagnostic('error','drive','unknown');}"
     "function showPicker(){"
     "if(!picker){pickerRequested=true;selection.textContent='Loading Google Drive Picker…';initializePicker();return;}"
     "pickerRequested=false;picker.setVisible(true);reportDiagnostic('opened','drive','unknown');}"
     "function openPicker(){"
     "if(!pickerConfig){selection.textContent='Google Drive is temporarily unavailable';return;}"
     "showPicker();}"
     "function initializePicker(){"
     "if(!pickerConfig||picker||pickerLoading)return;"
     "pickerLoading=true;const attempt=++pickerLoadAttempt;"
     "const failed=()=>failPickerInitialization(attempt);"
     "pickerLoadTimer=setTimeout(failed,10000);"
     "try{gapi.load('picker',{callback:()=>{"
     "if(!pickerLoading||attempt!==pickerLoadAttempt)return;"
     "try{" (picker-views-script)
     "picker=new google.picker.PickerBuilder()"
     ".addView(driveView).addView(sharedDrivesView)"
     ".setSelectableMimeTypes(pickerMimeTypes)"
     ".setOAuthToken(pickerConfig.accessToken).setDeveloperKey(pickerConfig.apiKey)"
     ".setAppId(pickerConfig.appId).setOrigin(location.origin)"
     ".setCallback(pickerCallback).build();"
     "picker.setVisible(false);pickerLoading=false;"
     "if(pickerLoadTimer){clearTimeout(pickerLoadTimer);pickerLoadTimer=null;}"
     "if(pickerRequested)showPicker();}catch(_error){failed();}},"
     "onerror:failed,timeout:10000,ontimeout:failed});}catch(_error){failed();}}"
     "if(pickerConfig)initializePicker();"
     "document.getElementById('open-picker').addEventListener('click',openPicker);"
     (direct-drive-upload-script)
     "})();")))

(defn- log-value [value]
  (if (vector? value)
    (str/join ", " (map str value))
    (str value)))

(defn- formatted-log [{:keys [createdAt fields]}]
  (let [{:keys [severity component event message]} fields]
    (str "<article class=\"log-entry\"><header><time>"
         (escape-html createdAt) "</time><span class=\"log-level\">"
         (escape-html severity) "</span><code>"
         (escape-html (str component " / " (title-case event)))
         "</code></header>"
         (when message
           (str "<p class=\"log-message\">" (escape-html message) "</p>"))
         "<dl>"
         (apply str
                (for [[key value] (sort-by (comp str key) fields)
                      :when (not= key :message)]
                  (str "<dt>" (escape-html (name key)) "</dt><dd>"
                       (escape-html (log-value value)) "</dd>")))
         "</dl></article>")))

(defn- raw-log [{:keys [raw]}]
  (str "<article class=\"log-entry\"><pre>" (escape-html raw) "</pre></article>"))

(defn logs-page [{:keys [user csrf logs view severity component]}]
  (let [raw? (= "raw" view)
        toggle-view (if raw? "formatted" "raw")
        toggle-label (if raw? "Formatted view" "Raw JSON view")
        severities ["DEBUG" "INFO" "NOTICE" "WARNING" "ERROR"]]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"dark\">" (icon-links)
     "<title>Operational logs · Alpha Compose</title>"
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     ".task-header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}"
     ".muted{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}.session-controls{display:flex;align-items:flex-end;flex-direction:column;gap:.65rem}.session-controls p,.session-controls form{margin:0}"
     ".card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem;margin:1rem 0}"
     ".filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;align-items:end}label{display:block;font-weight:700;font-size:.9rem}input,select{font:inherit;width:100%;border:1px solid #6b8ba5;border-radius:.65rem;background:#06182b;color:var(--color-text);padding:.68rem .75rem;margin-top:.4rem}"
     "button,.button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text);text-decoration:none;display:inline-block}.primary{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}"
     ".actions{display:flex;gap:.7rem;align-items:center;flex-wrap:wrap;margin-top:1rem}.log-entry{border-top:1px solid #e8edf4;padding:1rem 0}.log-entry:first-child{border-top:0;padding-top:0}.log-entry header{display:flex;justify-content:flex-start;align-items:center;gap:.65rem;margin:0 0 .5rem;flex-wrap:wrap}.log-entry time{color:#718097;font-size:.85rem}.log-level{border-radius:999px;background:#e8eef8;padding:.2rem .55rem;font-size:.75rem;font-weight:800}.log-entry code,pre,dt,dd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.log-message{font-weight:700}.log-entry dl{display:grid;grid-template-columns:max-content 1fr;gap:.25rem .9rem;margin:0}.log-entry dt{color:#718097}.log-entry dd{margin:0;overflow-wrap:anywhere}.log-entry pre{white-space:pre-wrap;overflow:auto;background:#f8fafc;border-radius:.65rem;padding:1rem;margin:0;font-size:.82rem}.empty{padding:2rem 0;text-align:center;color:#5c6b82}@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.task-header{display:block}.session-controls{align-items:flex-start;margin-top:1rem}.filters{grid-template-columns:1fr}.log-entry dl{grid-template-columns:1fr}.log-entry dt{margin-top:.5rem}}"
     (theme-style)
     "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
     (product-header)
     "<header class=\"task-header\"><div><div class=\"eyebrow\">Administration</div><h1>Operational logs</h1><p class=\"muted\">Safe structured events retained for 30 days. Showing up to 100 recent entries.</p></div><div class=\"session-controls\"><p class=\"muted\">Signed in as "
     (escape-html (:email user))
     "</p><form method=\"post\" action=\"/v1/auth/logout\"><input type=\"hidden\" name=\"csrf\" value=\""
     (escape-html csrf)
     "\"><button type=\"submit\">Log out</button></form></div></header>"
     "<p><a href=\"/\">← Back to compose</a></p>"
     "<section class=\"card\"><form method=\"get\" action=\"/ui/admin/logs\"><div class=\"filters\">"
     "<label>Severity<select name=\"severity\"><option value=\"\">All severities</option>"
     (apply str (for [option severities]
                  (str "<option value=\"" option "\""
                       (selected-attribute option severity) ">"
                       option "</option>")))
     "</select></label><label>Component<input name=\"component\" maxlength=\"64\" value=\""
     (escape-html component) "\"></label><input type=\"hidden\" name=\"view\" value=\""
     (escape-html view) "\"><button class=\"primary\" type=\"submit\">Apply filters</button></div></form>"
     "<div class=\"actions\"><a class=\"button\" href=\"/ui/admin/logs"
     (logs-query {:view toggle-view :severity severity :component component}) "\">"
     toggle-label "</a><span class=\"muted\">" (count logs) " entries</span></div></section>"
     "<section class=\"card\"><h2>" (if raw? "Raw JSON" "Formatted events") "</h2>"
     (if (seq logs)
       (apply str (map #(if raw? (raw-log %) (formatted-log %)) logs))
       "<p class=\"empty\">No matching logs in the retention window.</p>")
     "</section>"
     "</div></body></html>")))

(defn- tool-page-style []
  (str
   ".shell{max-width:62rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
   ".task-header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
   "h1,h2,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}"
   ".session-controls{display:flex;align-items:flex-end;flex-direction:column;gap:.65rem}.session-controls p,.session-controls form{margin:0}"
   ".card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem;margin:1rem 0}"
   ".panel-surface>section{margin:0}.panel-surface form{display:flex;align-items:end;gap:.75rem;flex-wrap:wrap;margin:1rem 0 0}.panel-surface form.inline{display:inline-flex;align-items:center;gap:.45rem;margin:0 0 0 .45rem}"
   ".panel-surface label{display:block;flex:1 1 18rem;font-weight:700;font-size:.9rem}.panel-surface input{font:inherit;width:100%;border:1px solid #6b8ba5;border-radius:.65rem;background:#06182b;color:var(--color-text);padding:.68rem .75rem;margin-top:.4rem}"
   ".panel-surface button,.button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text);text-decoration:none;display:inline-block}"
   ".panel-surface ul{list-style:none;padding:0;margin:1rem 0 0;display:grid;gap:.75rem}.panel-surface li{padding:.9rem 1rem;border:1px solid var(--color-border);border-radius:.8rem;background:var(--color-surface-soft);overflow-wrap:anywhere}"
   ".panel-surface code{display:block;margin-top:.75rem;padding:.8rem 1rem;border:1px solid var(--color-border-strong);border-radius:.8rem;background:#06182b;overflow:auto;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
   ".notice{border:1px solid var(--color-warning);border-radius:.8rem;background:#2a230f;padding:1rem;margin-top:1rem}"
   "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.task-header{display:block}.session-controls{align-items:flex-start;margin-top:1rem}.panel-surface form{display:block}.panel-surface form.inline{display:inline-flex}.panel-surface button{margin-top:.75rem}}"))

(defn- tool-page
  [{:keys [title eyebrow intro user csrf panel]}]
  (str
   "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
   "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
   "<meta name=\"color-scheme\" content=\"dark\">" (icon-links)
   "<title>" (escape-html title) " · Alpha Compose</title>"
   "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
   "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
   "crossorigin=\"anonymous\"></script>"
   "<style>" (theme-style) (tool-page-style)
   "</style></head><body data-theme=\"telemetry\" hx-headers=\""
   (escape-html (str "{\"X-CSRF-Token\":\"" csrf "\"}"))
   "\"><div class=\"shell\">"
   (product-header)
   "<header class=\"task-header\"><div><div class=\"eyebrow\">"
   (escape-html eyebrow)
   "</div><h1>" (escape-html title) "</h1><p class=\"muted\">"
   (escape-html intro)
   "</p></div><div class=\"session-controls\"><p class=\"muted\">Signed in as "
   (escape-html (:email user))
   "</p><form method=\"post\" action=\"/v1/auth/logout\"><input type=\"hidden\" name=\"csrf\" value=\""
   (escape-html csrf)
   "\"><button type=\"submit\">Log out</button></form></div></header>"
   "<p><a href=\"/\">← Back to compose</a></p>"
   "<section class=\"card panel-surface\">"
   panel
   "</section></div></body></html>"))

(defn token-page [{:keys [user csrf tokens]}]
  (tool-page {:title "Personal API tokens"
              :eyebrow "Account"
              :intro "Create a token for API access, then revoke it when you no longer need it."
              :user user
              :csrf csrf
              :panel (token-panel tokens)}))

(defn member-admin-page [{:keys [user csrf members]}]
  (tool-page {:title "Member admin"
              :eyebrow "Administration"
              :intro "Allowlist members, review their status, and revoke access when needed."
              :user user
              :csrf csrf
              :panel (member-panel members)}))

(defn job-fragment
  [{:keys [id state output]
    :as job}]
  (let [path (str "/ui/jobs/" id)
        polling? (contains? #{"queued" "launching" "running"
                              "cancellation-requested"}
                            state)
        cancellable? (contains? #{"queued" "launching" "running"} state)
        retryable? (jobs/retry-eligible? job)
        drive-link (:driveWebViewLink output)
        content-type (:contentType output)
        h264? (= "video/mp4" content-type)
        prores? (= "video/quicktime" content-type)
        heading (case state
                  "queued" "Finished video queued"
                  "launching" "Starting finished video"
                  "running" "Creating finished video"
                  "cancellation-requested" "Cancellation requested"
                  "cancelled" "Finished video cancelled"
                  "failed" "Finished video did not complete"
                  "succeeded" (if h264?
                                "Finished video ready"
                                "Finished video delivered")
                  (title-case state))
        summary (case state
                  "queued"
                  "Your finished video is queued. This page updates automatically."
                  "launching"
                  "Your finished video is starting now. This page updates automatically."
                  "running"
                  "Your finished video is rendering now. Keep this page open to watch progress."
                  "cancellation-requested"
                  "Alpha Compose is stopping this finished video now."
                  "cancelled"
                  "This finished video was cancelled before delivery completed."
                  "failed"
                  (cond
                    (= "source_duration_too_short" (:reason job))
                    "The selected video ends before the requested section. Shorten the section or choose a longer video, then retry."

                    (false? retryable?)
                    "Check the selected video and current settings, then start another finished video."

                    :else
                    "This finished video stopped before delivery completed. Retry when ready.")
                  "succeeded"
                  (cond
                    h264?
                    "Open the MP4 in Google Drive. Inline playback appears below this card."

                    prores?
                    "Use this finished video in desktop editing software that supports ProRes exports."

                    :else
                    "Open the finished video in Google Drive.")
                  "")
        progress-copy (case state
                        "queued" "Waiting for a render slot."
                        "launching" "Preparing the render worker."
                        "running" "Rendering and delivering the finished video."
                        "cancellation-requested" "Stopping the active render."
                        nil)]
    (str "<article id=\"job-" (escape-html id) "\" class=\"job\" data-job-state=\""
         (escape-html state) "\""
         (when polling?
           (str " hx-get=\"" (escape-html path)
                "\" hx-trigger=\"load delay:2s\" hx-swap=\"outerHTML\""))
         " role=\"status\" aria-live=\"polite\"><h2>" (escape-html heading)
         "</h2><p>" (escape-html summary) "</p>"
         (when progress-copy
           (str "<p class=\"muted\">" (escape-html progress-copy) "</p>"
                "<div class=\"button-with-spinner\"><span class=\"button-spinner\" aria-hidden=\"true\"></span><span>Updating automatically</span></div>"))
         (when (and (= "failed" state) retryable?)
           "<p class=\"muted\">Retry keeps the same request and starts a new finished-video attempt.</p>")
         (when drive-link
           (str "<p><a href=\"" (escape-html drive-link)
                "\" rel=\"noopener noreferrer\">"
                (if (= "succeeded" state)
                  "Open finished video in Google Drive"
                  "Open result in Google Drive")
                "</a></p>"))
         (when (and (= "succeeded" state) h264?)
           (str "<div class=\"job-player-slot\" data-inline-player-job-id=\""
                (escape-html id)
                "\" aria-label=\"Finished video player\">"
                "<p class=\"muted\">Preparing inline playback…</p></div>"))
         (when cancellable?
           (str "<button type=\"button\" hx-post=\"" (escape-html path)
                "/cancel\" hx-target=\"#job-" (escape-html id)
                "\" hx-swap=\"outerHTML\">Cancel</button>"))
         (when retryable?
           (str "<button type=\"button\" hx-post=\"" (escape-html path)
                "/retry\" hx-target=\"#job-" (escape-html id)
                "\" hx-swap=\"outerHTML\">Retry</button>"))
         "</article>")))

(defn preview-failure-fragment
  [{:keys [status failureCode timeoutMs retryable field expectedSchema
           documentationPath]}]
  (let [input-label (case field
                      "telemetry" "Heart-rate data"
                      "spo2.telemetry" "Oxygen-saturation data (SpO2)"
                      "telemetryFormat" "Heart-rate data format"
                      "spo2" "Oxygen-saturation data (SpO2)"
                      "Activity-data input")
        correction
        (case failureCode
          "unsupported_telemetry_columns"
          "Use timestamped Polar CSV or the documented columns for the selected heart-rate data format. Summary-only exports are not supported."
          "malformed_telemetry_row"
          "Correct the malformed row, keeping an absolute timestamp and numeric value in every required column."
          "heart_rate_out_of_range"
          "Heart rate must be between 20 and 260 bpm."
          "telemetry_value_out_of_range"
          "Correct the value so it is inside the documented range."
          "unordered_telemetry"
          "Sort activity-data timestamps in strictly increasing order and remove duplicates."
          "insufficient_telemetry_coverage"
          "Provide at least two samples that cover the full requested section."
          "telemetry_too_large"
          "Reduce the activity-data input to the documented size limit."
          "telemetry_sample_limit_exceeded"
          "Use fewer activity-data samples while preserving coverage of the requested section."
          "unsupported_telemetry_format"
          "Choose Polar CSV, Garmin FIT, or OxiWear heart-rate CSV and provide content matching that format."
          "invalid_telemetry"
          "Provide activity data matching the selected format."
          "Review the activity-data input and API documentation before retrying.")
        expected-columns
        (when (and (map? expectedSchema)
                   (seq (:timestampColumns expectedSchema))
                   (seq (:valueColumns expectedSchema)))
          (str "<p>Accepted timestamp columns: "
               (str/join ", "
                         (map #(str "<code>" (escape-html %) "</code>")
                              (:timestampColumns expectedSchema)))
               ". Accepted value columns: "
               (str/join ", "
                         (map #(str "<code>" (escape-html %) "</code>")
                              (:valueColumns expectedSchema)))
               ".</p>"))
        documentation-link
        (when (and (string? documentationPath)
                   (str/starts-with? documentationPath "/openapi.yaml"))
          (str "<p><a href=\"" (escape-html documentationPath)
               "\">Review the activity-data request contract</a>.</p>"))]
    (str "<article id=\"preview-result\" class=\"preview-error\" role=\"alert\"><h2>Preview failed</h2>"
         (if (or timeoutMs (= 504 status))
           "<p>Preview did not finish.</p>"
           "<p>Preview could not start.</p>")
         (when failureCode
           (str "<section><h3>" (escape-html input-label) "</h3><p>"
                (escape-html correction) "</p>" expected-columns
                documentation-link "</section>"))
         "<p>No durable render was submitted. If a Preview started, its reservation remains counted.</p>"
         (if retryable
           "<p>Retry with the Preview button when ready.</p>"
           "<p>Review the request before starting another preview.</p>")
         "</article>")))

(defn- duration-seconds-copy [seconds]
  (let [value (double seconds)]
    (if (== value (Math/rint value))
      (str (long value))
      (Double/toString value))))

(defn- source-duration-warning-copy
  [{:keys [requestedMomentCount generatedMomentCount omittedMomentCount
           requestedDurationSeconds]}]
  (str "We generated " (long generatedMomentCount) " of "
       (long requestedMomentCount) " preview frames. The selected video ends "
       "before the " (duration-seconds-copy requestedDurationSeconds)
       "-second section, so "
       (long omittedMomentCount) " later preview "
       (if (= 1 omittedMomentCount) "frame is" "frames are")
       " unavailable. Shorten the section or choose a longer video."))

(defn- zero-source-duration-copy
  [{:keys [requestedMomentCount requestedDurationSeconds]}]
  (str "We could not generate any of the " (long requestedMomentCount)
       " preview frames. The selected video ends before the "
       (duration-seconds-copy requestedDurationSeconds)
       "-second section. Shorten the section or choose a longer video."))

(defn preview-operation-fragment
  [{:keys [id state progressPercent error result]} generation]
  (let [path (str "/ui/previews/" id "?generation="
                  (url-value generation))
        source-duration-warning
        (first (filter #(= "source_duration_too_short" (:reason %))
                       (:warnings result)))]
    (cond
      (= "succeeded" state)
      (if (seq (:sections result))
        (let [assets (into {} (map (juxt :id identity)) (:assets result))
              image-button
              (fn [reference title alt checkerboard?]
                (str "<button type=\"button\" class=\"preview-open\" data-full=\""
                     (escape-html (:fullUrl reference)) "\" data-title=\""
                     (escape-html title) "\" data-alt=\""
                     (escape-html alt) "\" aria-label=\"Open larger image: "
                     (escape-html title) "\"><img loading=\"eager\" src=\""
                     (escape-html (:thumbnailUrl reference)) "\" alt=\""
                     (escape-html alt) "\""
                     (when checkerboard? " class=\"checkerboard\"")
                     "></button>"))
              moment-html
              (fn [section moment]
                (let [asset (get assets (:frameRef moment))
                      title (:title moment)
                      subject (str (:name section) ", " title)
                      photo
                      (fn [role reference alt checkerboard? class-name]
                        (str "<div class=\"preview-cell" class-name
                             "\"><h3 class=\"photo-title\"><span class=\"frame-role\">"
                             (escape-html role) "</span> - "
                             (escape-html title) "</h3>"
                             (image-button reference (str role " - " title)
                                           alt checkerboard?)
                             "</div>"))]
                  (str "<article class=\"preview-moment\">"
                       (case (:kind asset)
                         "overlay"
                         (photo "Overlay" (:image asset)
                                (str subject ", transparent overlay")
                                true " overlay-cell")
                         "final"
                         (photo "Final" (:image asset)
                                (str subject ", final composited frame")
                                false " final-cell"))
                       "</article>")))]
          (str "<article id=\"preview-result\" class=\"preview-gallery\" data-preview-operation=\""
               (escape-html id) "\" data-preview-generation=\""
               (escape-html generation) "\" aria-live=\"polite\"><header><h2>Preview ready</h2>"
               "<p class=\"muted\">Key moments are ordered on the exact 25 fps output timeline.</p></header>"
               (when source-duration-warning
                 (str "<aside class=\"preview-warning\" role=\"status\"><h3>Some preview frames are unavailable</h3><p>"
                      (escape-html
                       (source-duration-warning-copy source-duration-warning))
                      "</p></aside>"))
               (apply str
                      (for [section (:sections result)]
                        (str "<section class=\"trace-preview\" aria-labelledby=\"trace-"
                             (escape-html (:id section)) "\"><h2 id=\"trace-"
                             (escape-html (:id section)) "\">"
                             (escape-html (:name section)) "</h2>"
                             "<div class=\"preview-scroll\"><div class=\"preview-moments\">"
                             (apply str (map #(moment-html section %)
                                             (:moments section)))
                             "</div></div></section>")))
               "<dialog id=\"preview-dialog\" tabindex=\"-1\" aria-labelledby=\"preview-dialog-title\" aria-describedby=\"preview-dialog-counter\"><div class=\"dialog-head\"><h2 id=\"preview-dialog-title\">Preview image</h2><button type=\"button\" class=\"preview-dialog-close\" aria-label=\"Close full-image viewer\">Close</button></div><div class=\"dialog-image-frame\"><img alt=\"\"></div><div class=\"dialog-nav\"><button type=\"button\" class=\"preview-previous\" aria-label=\"Previous image\">Previous</button><p id=\"preview-dialog-counter\" class=\"preview-counter\" aria-live=\"polite\"></p><button type=\"button\" class=\"preview-next\" aria-label=\"Next image\">Next</button></div></dialog>"
               "</article>"))
        (str "<article id=\"preview-result\" class=\"preview-empty\" role=\"status\" data-preview-operation=\""
             (escape-html id) "\" data-preview-generation=\""
             (escape-html generation) "\"><h2>No preview moments</h2><p>The normalized traces contain no renderable frames.</p></article>"))

      (contains? #{"failed" "cancelled"} state)
      (str "<article id=\"preview-result\" class=\"preview-error\" role=\"alert\" data-preview-operation=\""
           (escape-html id) "\" data-preview-generation=\""
           (escape-html generation) "\">"
           (cond
             (= "cancelled" state)
             "<h2>Preview cancelled</h2><p>The preview operation was cancelled.</p>"

             (= "source_duration_too_short" (:reason error))
             (str "<h2>The selected video is too short</h2><p>"
                  (escape-html (zero-source-duration-copy error)) "</p>")

             :else
             (str "<h2>Preview did not finish</h2>"
                  "<p>We could not generate this preview. Check the selected "
                  "video and inputs, then retry with the Preview button.</p>"))
           "<p>No durable render was submitted. The Preview reservation remains counted.</p>"
           "</article>")

      :else
      (str "<article id=\"preview-result\" class=\"preview-pending\" role=\"status\" aria-live=\"polite\" data-preview-operation=\""
           (escape-html id) "\" data-preview-generation=\""
           (escape-html generation) "\" hx-get=\"" (escape-html path)
           "\" hx-trigger=\"load delay:1s\" hx-swap=\"outerHTML\" hx-request='{\"timeout\":15000}'><h2>Preparing preview</h2><p>"
           (escape-html (title-case state)) " - " (long progressPercent)
           "%</p><progress max=\"100\" value=\"" (long progressPercent)
           "\">" (long progressPercent) "%</progress></article>"))))

(defn preview-stale-fragment [generation]
  (str "<article id=\"preview-result\" class=\"preview-stale\" role=\"status\" data-preview-generation=\""
       (escape-html generation) "\"><h2>Preview expired</h2><p>Start a new preview to refresh these images.</p></article>"))

(defn- wizard-outcome-step []
  (str
   "<section id=\"wizard-outcome-step\" class=\"card wizard-step\" "
   "data-wizard-panel data-step-id=\"outcome\" "
   "aria-labelledby=\"wizard-current-step-heading\">"
   "<fieldset class=\"wizard-choices\" aria-describedby=\"wizard-outcome-status\">"
   "<legend class=\"visually-hidden\">Choose an output</legend>"
   (apply str
          (for [{:keys [route label description]} wizard/outcome-options]
            (str "<label class=\"wizard-choice\"><input type=\"radio\" "
                 "name=\"wizard-outcome\" value=\"" (name route) "\">"
                 "<span><strong>" (escape-html label) "</strong><small>"
                 (escape-html description) "</small></span></label>")))
   "</fieldset><p id=\"wizard-outcome-status\" class=\"status\" "
   "role=\"status\" aria-live=\"polite\">Choose one option to continue.</p>"
   "</section>"))

(defn page [{:keys [user csrf picker-config tokens members logs-enabled?]}]
  (let [csrf-headers (escape-html
                      (str "{\"X-CSRF-Token\":\"" csrf "\"}"))]
    (str
     "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<meta name=\"color-scheme\" content=\"dark\">" (icon-links)
     "<title>Alpha Compose</title>"
     "<script src=\"https://cdn.jsdelivr.net/npm/htmx.org@2.0.10/dist/htmx.min.js\" "
     "integrity=\"sha384-H5SrcfygHmAuTDZphMHqBJLc3FhssKjG7w/CeCpFReSfwBWDTKpkzPP8c+cLsK+V\" "
     "crossorigin=\"anonymous\"></script>"
     (when picker-config
       "<script src=\"https://apis.google.com/js/api.js\"></script>")
     "<style>"
     ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
     "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
     ".task-header{display:flex;justify-content:space-between;gap:1rem;align-items:end;margin:1rem 0 2rem}"
     "h1,h2,h3,p{margin-top:0}h1{font-size:clamp(2rem,4vw,3.4rem);letter-spacing:-.05em;margin-bottom:.35rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
     ".muted,.hint{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}.session-controls{display:flex;align-items:flex-end;flex-direction:column;gap:.65rem}.session-controls p,.session-controls form{margin:0}"
     ".card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem;margin:1rem 0}"
     ".drive-card{display:flex;align-items:center;justify-content:space-between;gap:1rem;flex-wrap:wrap;background:var(--color-surface-strong);color:var(--color-text);border:1px solid var(--color-border-strong)}.drive-card>.wizard-navigation{flex:0 0 100%}.drive-card .muted{color:#c8ddef}"
     ".drive-card a{color:var(--color-link)}.drive-actions{display:flex;align-items:center;gap:.8rem;flex-wrap:wrap}"
     ".direct-drive-upload{flex:0 0 100%;min-width:0;padding-top:1rem;border-top:1px solid var(--color-border)}.direct-drive-upload progress{display:block;width:min(100%,32rem);margin:.75rem 0}.direct-drive-upload progress[hidden]{display:none}.direct-drive-upload .status{margin:.5rem 0}"
     ".section-head{display:flex;justify-content:space-between;align-items:start;gap:1rem;margin-bottom:1rem}.step{color:var(--color-subtle);font-weight:800;font-size:.8rem}"
     ".visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}#compose-workflow{display:flex;min-width:0;flex-direction:column}#video-player{order:-1}.wizard-step{min-width:0}.wizard-current-step{min-width:0;margin:1rem 0}.wizard-current-step:focus{outline:3px solid var(--color-warning);outline-offset:3px}.wizard-current-step h2{margin-bottom:.35rem}.wizard-current-step p{margin-bottom:0}.wizard-step-overview{min-width:0;margin:1rem 0}.wizard-step-overview ol{display:flex;gap:.45rem;min-width:0;margin:0;padding:0;list-style:none;overflow-x:auto}.wizard-step-overview li{flex:0 0 auto}.wizard-step-overview button{padding:.5rem .7rem;white-space:nowrap}.wizard-step-overview button[aria-current=step]{border-color:var(--color-warning);box-shadow:0 0 0 2px var(--color-warning)}.wizard-error-summary{margin-top:.8rem;padding:.75rem;border:2px solid var(--color-danger);border-radius:.7rem;color:var(--color-danger);background:#2b1020}.wizard-error-summary[hidden],[data-wizard-panel][hidden],#wizard-next[hidden]{display:none!important}.wizard-navigation{display:flex;align-items:center;justify-content:space-between;gap:.75rem;min-width:0;margin-top:1rem;padding-top:1rem;border-top:1px solid var(--color-border)}.wizard-navigation button{min-width:7rem}@keyframes wizard-next-ready{from{opacity:.35;transform:translateY(.25rem) scale(.98);box-shadow:none}to{opacity:1;transform:none}}#wizard-next.wizard-next-ready{animation:wizard-next-ready .2s ease-out both}@media(prefers-reduced-motion:reduce){#wizard-next.wizard-next-ready{animation:none}}.wizard-choices{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.8rem;min-width:0;margin:0;padding:0;border:0}.wizard-choice{display:flex;align-items:flex-start;gap:.7rem;min-width:0;padding:1rem;border:1px solid var(--color-border);border-radius:.8rem;background:var(--color-surface-soft);cursor:pointer}.wizard-choice:focus-within{outline:3px solid var(--color-warning);outline-offset:2px}.wizard-choice input{flex:0 0 auto;width:auto;margin:.25rem 0 0}.wizard-choice span{min-width:0}.wizard-choice strong,.wizard-choice small{display:block;overflow-wrap:anywhere}.wizard-choice small{margin-top:.25rem;color:var(--color-muted);font-weight:400}"
     ".field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem}.full{grid-column:1/-1}label,.field-label{display:block;font-weight:700;font-size:.9rem}"
     ".synchronization-options{min-width:0;margin:0;padding:1rem;border:1px solid var(--color-border);border-radius:.75rem}.synchronization-options legend{padding:0 .35rem;font-weight:800}.synchronization-choice{display:flex;align-items:flex-start;gap:.6rem;min-width:0;padding:.45rem 0;font-weight:700}.synchronization-choice input{flex:0 0 auto;width:auto;margin:.2rem 0 0}.synchronization-choice span{min-width:0;overflow-wrap:anywhere}#manual-synchronization-fields[hidden]{display:none}"
     "label small,.field-label small{display:block;color:var(--color-muted);font-weight:400;margin-top:.2rem}input,select,textarea{font:inherit;width:100%;border:1px solid #6b8ba5;border-radius:.65rem;background:#06182b;color:var(--color-text);padding:.68rem .75rem;margin-top:.4rem}"
     "input:focus,select:focus,textarea:focus{outline:3px solid var(--color-warning);border-color:var(--color-warning)}input[type=file]{padding:.5rem;background:#06182b}"
     "textarea{min-height:8rem;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82rem}"
     ".source-box{background:#06182b;border:1px dashed var(--color-border-strong);border-radius:.8rem;padding:1rem}.source-box textarea{background:#06182b}"
     ".optional{border-top:1px solid var(--color-border);margin-top:1.25rem;padding-top:1.25rem}.toggle{display:flex;align-items:center;gap:.65rem;font-weight:700}.toggle input{width:auto;margin:0}"
     ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,button{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text)}"
     ".button.primary,button.primary{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}.button:hover,button:hover{filter:brightness(1.08)}"
     ".button.primary:disabled,button.primary:disabled,.button.primary:disabled:hover,button.primary:disabled:hover{background:#485e73;color:#d2dce6;box-shadow:none;cursor:not-allowed;filter:none}"
     ".button-with-spinner{display:inline-flex;align-items:center;justify-content:center;gap:.5rem}.button-spinner{display:inline-block;width:1rem;height:1rem;border:.15rem solid currentColor;border-right-color:transparent;border-radius:50%;animation:preview-button-spin .75s linear infinite;flex:0 0 auto}.button-spinner[hidden]{display:none}@keyframes preview-button-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.button-spinner{animation:none}}"
     ".video-player[hidden],.video-chrome[hidden],.video-stage[hidden],.video-transport[hidden]{display:none}.video-stage{position:relative;width:100%;aspect-ratio:16/9;display:flex;align-items:center;justify-content:center;overflow:hidden;border:1px solid var(--color-border-strong);border-radius:.85rem;background:#010813}.video-stage video{display:block;width:100%;height:100%;object-fit:contain;background:#010813}.video-transport{display:flex;align-items:center;justify-content:center;gap:.55rem;flex-wrap:wrap;margin-top:.8rem}.video-transport button{min-width:3.25rem;padding:.55rem .7rem}.video-control{position:relative;display:inline-flex;min-width:0}.video-control button{width:100%}#video-play-pause{display:inline-flex;align-items:center;justify-content:center;width:3.25rem}.video-play-pause-symbol{display:inline-block;width:1.25em;line-height:1;text-align:center}.video-shortcut{position:absolute;left:50%;bottom:calc(100% + .45rem);z-index:4;max-width:calc(100vw - 1rem);transform:translateX(-50%);padding:.22rem .42rem;border:1px solid var(--color-border-strong);border-radius:.35rem;background:#010813;color:var(--color-text);font:700 .72rem ui-monospace,SFMono-Regular,Menlo,monospace;line-height:1.2;pointer-events:none;white-space:nowrap;visibility:hidden;opacity:0}.video-control:hover .video-shortcut,.video-control:focus-within .video-shortcut,.video-control.shortcut-auto .video-shortcut{visibility:visible;opacity:1}.video-time{min-width:14.5rem;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-variant-numeric:tabular-nums;text-align:center}.video-volume{display:flex;align-items:center;gap:.45rem;font-size:.82rem}.video-volume input{width:7rem;margin:0;padding:0}.video-timeline-wrap{margin-top:.8rem;min-width:0}.video-timeline{position:relative;min-width:0;height:4rem;cursor:pointer;touch-action:none;user-select:none}.video-timeline-track{position:absolute;left:0;right:0;top:1.35rem;height:.8rem;overflow:visible;border:1px solid var(--color-border-strong);border-radius:999px;background:#06182b}.video-buffered-ranges,.video-buffered-ranges span{position:absolute;inset:0;border-radius:999px}.video-buffered-ranges{z-index:4;top:auto;height:.24rem;overflow:hidden;pointer-events:none}.video-buffered-ranges span{right:auto;background:#5b7892}.video-output-range,.video-unused-range{position:absolute;top:0;bottom:0}.video-output-range{z-index:2;background:#12bfe055}.video-unused-range{z-index:3;background:#010813b8}.video-playhead{position:absolute;z-index:5;top:-.35rem;bottom:-.35rem;width:.2rem;transform:translateX(-50%);border-radius:999px;background:var(--color-warning);box-shadow:0 0 0 .15rem #031225}.video-range-handle{position:absolute;z-index:6;top:-.65rem;width:1.1rem;min-width:1.1rem;height:2rem;margin:0;padding:0;transform:translateX(-50%);border:2px solid #dff9ff;border-radius:.3rem;background:var(--color-accent);box-shadow:0 0 0 .16rem #031225;touch-action:none}.video-range-handle:focus{outline:3px solid var(--color-warning)}.video-timeline-tooltip{position:absolute;top:-.35rem;transform:translate(-50%,-100%);padding:.25rem .45rem;border-radius:.4rem;background:#010813;color:var(--color-text);font:700 .75rem ui-monospace,SFMono-Regular,Menlo,monospace;pointer-events:none;white-space:nowrap}.video-ticks{position:relative;height:1.7rem;margin-top:-.55rem;color:var(--color-muted);font:600 .72rem ui-monospace,SFMono-Regular,Menlo,monospace}.video-ticks span{position:absolute;top:0;white-space:nowrap}.video-player-status{margin:.35rem 0 0}.video-range-status{margin:.2rem 0 0}.video-audio-note{margin:.25rem 0 0}.range-receiver{outline:3px solid var(--color-warning);border-color:var(--color-warning)}"
     ".manual-sync-marker{position:absolute;z-index:7;top:-1rem;width:1.35rem;min-width:1.35rem;height:2.7rem;margin:0;padding:0;transform:translateX(-50%);border:2px solid #fff2c7;border-radius:999px;background:#ff9f43;box-shadow:0 0 0 .18rem #031225;touch-action:none}.manual-sync-marker[hidden]{display:none}.manual-sync-marker:focus{outline:3px solid var(--color-warning)}.video-sync-help{margin:.25rem 0 0;color:var(--color-muted);font-size:.8rem}.video-sync-help[hidden]{display:none}.sync-field{min-width:0;padding:.55rem;border:2px solid transparent;border-radius:.75rem}.sync-field-active{border-color:var(--color-warning);background:#ffd27a17}.sync-field-active input{outline:4px solid var(--color-warning);border-color:var(--color-warning)}.sync-field-related{border-color:var(--color-accent);background:#65d6ff14}"
     ".video-timeline{height:5.75rem}.timer-marker{position:absolute;z-index:8;top:1.5rem;width:1.35rem;min-width:1.35rem;height:2rem;margin:0;padding:0;border:2px solid #f4f9ff;border-radius:.4rem;box-shadow:0 0 0 .18rem #031225;color:#031225;font:900 .75rem ui-monospace,SFMono-Regular,Menlo,monospace;touch-action:none}.timer-marker[hidden]{display:none}.timer-marker-start{transform:translateX(0);background:#65e1ad}.timer-marker-end{transform:translateX(-100%);background:#ff8294}.timer-marker:focus{outline:3px solid var(--color-warning)}.timer-field{min-width:0;padding:.55rem;border:2px solid transparent;border-radius:.75rem}.timer-field-active{border-color:var(--color-warning);background:#ffd27a17}.timer-field-active input{outline:4px solid var(--color-warning);border-color:var(--color-warning)}"
     ".source-clock{min-width:0;margin:0 0 1rem;padding:1rem;border:1px solid var(--color-border);border-radius:.85rem;background:#06182b}.source-clock h3{margin:0 0 .35rem}.source-clock-actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:.8rem}.clock-candidates{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,18rem),1fr));gap:.55rem;margin:.75rem 0}.clock-candidates:empty{display:none}.clock-candidate{display:flex;align-items:flex-start;gap:.5rem;min-width:0;padding:.6rem;border:1px solid var(--color-border);border-radius:.6rem;font-weight:500;overflow-wrap:anywhere}.clock-candidate input{flex:0 0 auto;width:auto;margin:.2rem 0 0}.source-summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,12rem),1fr));gap:.55rem;margin:1rem 0 0}.source-summary div{min-width:0}.source-summary dt{color:var(--color-muted);font-size:.75rem;font-weight:800;text-transform:uppercase;letter-spacing:.06em}.source-summary dd{margin:.15rem 0 0;overflow-wrap:anywhere;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82rem}.video-ticks{overflow:hidden}"
     ".advanced-output-settings{min-width:0;margin-top:1rem;padding-top:1rem;border-top:1px solid var(--color-border)}.advanced-output-settings>div{min-width:0;margin-top:.75rem}.review-sections{display:grid;gap:.75rem;min-width:0}.review-section{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:.75rem;align-items:start;min-width:0;padding:.9rem;border:1px solid var(--color-border);border-radius:.75rem;background:var(--color-surface-soft)}.review-section h3,.review-section p{margin:0}.review-section p{margin-top:.25rem;color:var(--color-muted);overflow-wrap:anywhere}.review-section button{align-self:center}"
     ".video-time-context{min-width:0;margin:.65rem 0 0;padding:.5rem .7rem;border:1px solid var(--color-border-strong);border-radius:.65rem;background:#06182b;color:var(--color-text)}.video-time-context-visual{display:flex;align-items:center;justify-content:center;gap:.4rem;min-width:0;overflow-wrap:anywhere;text-align:center}.video-time-context [hidden]{display:none}.video-context-detail{color:var(--color-muted);font-size:.82rem;font-weight:700}.video-dates{position:relative;min-width:0;height:1.35rem;margin-top:-.2rem;overflow:hidden;color:var(--color-muted);font:700 .72rem ui-monospace,SFMono-Regular,Menlo,monospace}.video-date-label{position:absolute;top:0;padding:0 .2rem;white-space:nowrap}.video-date-label::before{content:'';position:absolute;left:50%;top:-.3rem;height:.28rem;border-left:1px solid var(--color-border-strong)}"
     ".video-chrome,.video-controls-dock{min-width:0}.timing-dock{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem;min-width:0;margin-top:.75rem;padding-top:.75rem;border-top:1px solid var(--color-border)}.timing-dock>*{min-width:0}.timing-dock>.full,.timing-dock>.status{grid-column:1/-1}.video-chrome:fullscreen,.video-chrome.is-fullscreen{display:grid;grid-template-rows:minmax(0,1fr) auto auto;width:100%;height:100dvh;max-width:none;min-width:0;overflow:hidden;padding:0;background:#010813;color:var(--color-text)}.video-chrome:fullscreen .video-stage,.video-chrome.is-fullscreen .video-stage{width:100%;height:100%;min-width:0;min-height:0;aspect-ratio:auto;border:0;border-radius:0}.video-chrome:fullscreen .video-time-context,.video-chrome.is-fullscreen .video-time-context{margin:.45rem clamp(.6rem,2vw,1.25rem) 0}.video-chrome:fullscreen .video-controls-dock,.video-chrome.is-fullscreen .video-controls-dock{position:relative;z-index:2;min-width:0;max-height:58dvh;overflow-y:auto;overflow-x:hidden;padding:.8rem clamp(.6rem,2vw,1.25rem) .45rem;border-top:1px solid var(--color-border-strong);background:#031225}.video-chrome:fullscreen .video-transport,.video-chrome.is-fullscreen .video-transport{margin-top:0}.video-chrome:fullscreen .video-timeline-wrap,.video-chrome.is-fullscreen .video-timeline-wrap{margin-top:.7rem}@media(prefers-reduced-motion:reduce){.video-shortcut{transition:none}}"
     ".status{min-height:1.4rem;color:var(--color-muted);font-size:.9rem}.status.error{color:var(--color-danger)}.status.success{color:var(--color-success)}"
     "details summary{cursor:pointer;font-weight:800;color:var(--color-link)}.raw-panel textarea{min-height:18rem}.raw-actions{display:flex;gap:.65rem;flex-wrap:wrap;margin-top:.7rem}.json-errors{white-space:pre-line}.field-reference{margin:.75rem 0 0;padding-left:1.25rem}.field-reference li{margin:.35rem 0}.field-reference code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
     ".management-links-card{margin-top:1.25rem}.management-links{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,15rem),1fr));gap:.8rem}.management-link{display:block;padding:1rem;border:1px solid var(--color-border);border-radius:.85rem;background:var(--color-surface-soft);text-decoration:none}.management-link strong,.management-link span{display:block}.management-link strong{color:var(--color-text)}.management-link span{margin-top:.35rem;color:var(--color-muted);overflow-wrap:anywhere}"
     ".results{display:block;min-width:0}.results img{max-width:100%;border:1px solid #d9e1ed;border-radius:.75rem;background:#eef2f7}.preview-gallery{min-width:0}.preview-warning{background:#fff8e8;border:1px solid #e7c46b;border-radius:.8rem;color:#59400a;padding:.85rem 1rem;margin:1rem 0}.preview-warning h3,.preview-warning p{margin:0}.preview-warning p{margin-top:.35rem}.trace-preview{background:white;border:1px solid #e1e7f0;border-radius:1rem;padding:1rem;margin:1rem 0;min-width:0;max-width:100%}.preview-scroll{max-width:100%;min-width:0;overflow:visible}.preview-moments{display:flex;flex-wrap:wrap;justify-content:center;gap:.8rem;align-items:flex-start;min-width:0;max-width:100%}.preview-moment{display:flex;flex:0 0 8rem;width:8rem;flex-direction:column;gap:.65rem;min-width:0}.photo-title{font-size:.75rem;line-height:1.35;overflow-wrap:anywhere;margin:0 0 .35rem}.preview-cell{min-width:0;width:100%}.preview-cell .preview-open{display:block;width:100%;padding:0;background:#f8fafc}.preview-cell img{display:block;width:100%;height:auto}.frame-role{display:inline;font-weight:800;letter-spacing:.04em}.checkerboard{background-color:#fff;background-image:linear-gradient(45deg,#d9e1ed 25%,transparent 25%),linear-gradient(-45deg,#d9e1ed 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#d9e1ed 75%),linear-gradient(-45deg,transparent 75%,#d9e1ed 75%);background-size:20px 20px;background-position:0 0,0 10px,10px -10px,-10px 0}.preview-pending,.preview-error,.preview-stale,.preview-empty{background:white;border:1px solid #e1e7f0;border-radius:1rem;padding:1rem;margin:1rem 0}.preview-pending progress{width:min(24rem,100%)}#preview-dialog{width:calc(100dvw - 1rem);height:calc(100dvh - 1rem);max-width:none;max-height:none;border:0;border-radius:1rem;padding:1rem;overflow:hidden}#preview-dialog[open]{display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:.75rem}#preview-dialog::backdrop{background:#10213acc}.dialog-image-frame{display:flex;align-items:center;justify-content:center;min-width:0;min-height:0;overflow:hidden}#preview-dialog img{display:block;width:100%;height:100%;min-width:0;min-height:0;object-fit:contain;margin:0}.dialog-head{display:flex;justify-content:space-between;align-items:center;gap:1rem;min-width:0}.dialog-head h2{margin:0;min-width:0;overflow-wrap:anywhere}.dialog-nav{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:.75rem}.preview-counter{margin:0;text-align:center}"
     "#contextual-help-dialog{width:min(42rem,calc(100dvw - 1rem));max-width:none;max-height:calc(100dvh - 1rem);min-width:0;padding:1.25rem;overflow:auto;color:var(--color-text);background:var(--color-surface-strong);border:1px solid var(--color-border-strong);border-radius:1rem;box-shadow:var(--shadow-surface)}#contextual-help-dialog::backdrop{background:#010813e6}.contextual-help-head{display:flex;align-items:flex-start;justify-content:space-between;gap:1rem;min-width:0}.contextual-help-head h2{min-width:0;margin:.5rem 0;overflow-wrap:anywhere}.contextual-help-close{flex:0 0 auto}.contextual-help-answer{min-width:0;overflow-wrap:anywhere}.contextual-help-actions{margin:1.25rem 0 0}"
     ".job{margin:0}.notice{border:2px solid #d8a94d;padding:1rem;overflow-wrap:anywhere}.notice code{display:block;margin-top:.6rem;white-space:pre-wrap}"
     ".inline{display:inline}"
     "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.task-header,.drive-card,.section-head{display:block}.session-controls{align-items:flex-start;margin-top:1rem}.wizard-choices,.field-grid,.timing-dock{grid-template-columns:1fr}.wizard-navigation{align-items:stretch}.wizard-navigation button{min-width:0;flex:1 1 0}.drive-actions{margin-top:1rem}.video-time-context-visual{justify-content:flex-start;flex-wrap:wrap;text-align:left}.video-transport{justify-content:flex-start}.video-time{width:100%;min-width:0;text-align:left}.video-volume{flex:1 1 10rem}.video-volume input{min-width:0;width:100%}.preview-moment{flex:0 1 24rem;width:min(100%,24rem);border-top:1px solid #e1e7f0;padding:1rem 0}.preview-moment:first-child{border-top:0;padding-top:0}.preview-cell{width:100%;margin-top:.75rem}}"
     (theme-style)
     "</style>"
     "</head><body data-theme=\"telemetry\" hx-headers=\"" csrf-headers "\">"
     "<div class=\"shell\">" (product-header)
     "<header class=\"task-header\"><div><div class=\"eyebrow\">Activity data for video</div><h1>Create your video</h1><p class=\"muted\">Configure the activity data and optional source video, preview key moments, then send the finished output to Drive. Full-length outputs use durable jobs.</p></div>"
     "<div class=\"session-controls\"><p class=\"muted\">Signed in as "
     (escape-html (:email user))
     "</p><form method=\"post\" action=\"/v1/auth/logout\"><input type=\"hidden\" name=\"csrf\" value=\""
     (escape-html csrf)
     "\"><button type=\"submit\">Log out</button></form></div></header>"
     "<nav id=\"wizard-step-overview\" class=\"wizard-step-overview\" "
     "aria-label=\"Wizard steps\" hidden><ol id=\"wizard-step-list\"></ol></nav>"
     "<section id=\"wizard-current-step-header\" class=\"wizard-current-step\" "
     "tabindex=\"-1\" aria-current=\"step\" aria-labelledby=\"wizard-current-step-heading\">"
     "<div id=\"wizard-progress\" class=\"step\" aria-live=\"polite\">Step 1 of 1</div>"
     "<h2 id=\"wizard-current-step-heading\">What would you like to make?</h2>"
     "<p id=\"wizard-current-step-description\" class=\"muted\">"
     "Choose the kind of video workflow you want to complete.</p>"
     "<div id=\"wizard-error-summary\" class=\"wizard-error-summary\" "
     "role=\"alert\" tabindex=\"-1\" hidden></div></section>"
     (wizard-outcome-step)
     "<div id=\"compose-workflow\">"
     "<section id=\"drive-source-step\" class=\"card drive-card\" "
     "data-wizard-panel data-step-id=\"source-video\" hidden><div><div class=\"help-heading\"><h2>Google Drive</h2>"
     (contextual-help-link "google-drive-access"
                           "Learn about Google Drive access")
     "</div><p class=\"muted\">Pick a supported video from My Drive, files shared with you, or a Shared Drive. Video results are filtered; folders are only for navigation. Selection grants Alpha Compose access to that file only. Every finished render still goes to your Alpha Compose folder in My Drive.</p></div>"
     "<div class=\"drive-actions\"><button id=\"open-picker\" type=\"button\">Pick one video from Drive</button><span>Selected: <output id=\"picker-selection\">None</output></span></div>"
     "<div class=\"direct-drive-upload\"><label for=\"source-video-upload\">Upload a video from this device</label>"
     "<input id=\"source-video-upload\" type=\"file\" accept=\"video/mp4,video/quicktime,video/webm,video/mpeg,video/ogg,video/x-msvideo,video/x-matroska,.mp4,.mov,.webm,.mpeg,.mpg,.ogg,.avi,.mkv\">"
     "<p class=\"muted\">The browser uploads directly and resumably to your Google Drive. Source bytes never pass through Alpha Compose, and Alpha Compose applies no whole-file upload size limit.</p>"
     "<progress id=\"source-video-upload-progress\" max=\"100\" value=\"0\" aria-label=\"Google Drive upload progress\" hidden>0%</progress>"
     "<p id=\"source-video-upload-status\" class=\"status\" role=\"status\" aria-live=\"polite\">Choose a local video to upload directly to Google Drive.</p>"
     "<div class=\"drive-actions\"><button id=\"cancel-source-video-upload\" type=\"button\" hidden disabled>Cancel upload</button>"
     "<button id=\"retry-source-video-upload\" type=\"button\" hidden disabled>Resume upload</button>"
     "<a href=\"https://drive.google.com\" target=\"_blank\" rel=\"noopener\">Upload at drive.google.com instead</a></div></div></section>"
     "<div id=\"output-settings-step\" data-wizard-panel data-step-id=\"output-settings\" hidden>"
     "<section id=\"no-source-output-summary\" class=\"card output-summary\" hidden><h2>Output format</h2><p><strong>Transparent ProRes 4444 overlay</strong></p><p class=\"muted\">No source video is selected. Alpha Compose will create a transparent overlay for a separate editing workflow.</p><label id=\"transparent-alpha-choice\">Alpha precision<select id=\"transparent-alpha-bits\"><option value=\"16\">ProRes 4444, 16-bit alpha</option><option value=\"8\">ProRes 4444, 8-bit alpha</option></select><small>16-bit is the maximum-quality default. 8-bit is smaller and suits normal 1080p graph, text, and overlay compositing.</small></label></section>"
     "<section id=\"source-output-controls\" class=\"card\" hidden><div class=\"section-head\"><div><h2>Output format</h2><p class=\"muted\">Choose the composite format and how the selected source fits the frame. The server verifies download access, decodability, and duration. Completed H.264 MP4 outputs support private browser playback when ready; ProRes outputs are for download-first editor workflows.</p></div></div><input id=\"source-video-file-id\" type=\"hidden\"><div class=\"field-grid\"><label>Output format<select id=\"output-format\"><option value=\"h264-mp4\">H.264 MP4</option><option value=\"prores-422-mov\">ProRes 422 MOV</option></select></label><label>Fit<select id=\"fit-mode\"><option value=\"letterbox\">Letterbox / pillarbox</option><option value=\"crop\">Crop to fill</option></select></label><div><div class=\"help-label\"><label for=\"audio-mode\">Audio</label>"
     (contextual-help-link "audio-options"
                           "Learn about heartbeat audio options")
     "</div><select id=\"audio-mode\"><option value=\"source+heartbeat\">Source + heartbeat</option><option value=\"source-only\">Source only</option><option value=\"heartbeat-only\">Heartbeat only</option></select></div></div></section>"
     "<section class=\"card\"><div class=\"field-grid\"><label>Render preset<select id=\"preset\" required><option value=\"1080p25\">1080p · 25 fps · up to 8 minutes</option><option value=\"2.7k25\">2.7K · 25 fps · up to 4 minutes</option></select></label></div><details class=\"advanced-output-settings\"><summary>Advanced output tuning</summary><div><label>Future trace opacity (%)<input id=\"future-trace-opacity-percent\" type=\"number\" min=\"0\" max=\"100\" step=\"any\" value=\"25\" required><small>Opacity of the not-yet-reached heart-rate trace. Default: 25%.</small></label></div></details></section></div>"
     "<section id=\"synchronization-decision-step\" class=\"card\" data-wizard-panel data-step-id=\"synchronization-decision\" hidden><div class=\"section-head\"><div><h2>How do the camera and activity data relate?</h2><p class=\"muted\">Choose whether the source video already follows the activity-device clock or whether you need to match one shared moment.</p></div></div><fieldset id=\"synchronization-options\" class=\"synchronization-options\" data-step-id=\"synchronization\"><legend><span class=\"help-label\"><strong>Camera and activity clock relationship</strong>"
     (contextual-help-link "how-should-i-synchronize-devices-before-recording"
                           "Learn how to synchronize devices before recording")
     "</span></legend><label class=\"synchronization-choice\"><input type=\"radio\" name=\"synchronization-mode\" value=\"shared-clock\"><span>Yes - the camera and activity device clocks matched</span></label><label class=\"synchronization-choice\"><input type=\"radio\" name=\"synchronization-mode\" value=\"manual-anchor\"><span>No - the camera and activity device clocks were different</span></label></fieldset></section>"
     "<section id=\"confirm-video-clock-step\" class=\"card\" data-wizard-panel data-step-id=\"confirm-video-clock\" hidden><section id=\"video-clock-confirmation\" class=\"source-clock\" data-confirmed=\"false\" hidden><h3>Confirm the video recording clock</h3><p class=\"muted\">Container metadata is only a suggestion. Check or correct the start, choose the IANA Video timezone, then confirm. A numeric offset alone is not a timezone.</p><p id=\"video-clock-inspection-status\" class=\"status\" role=\"status\">Choose a source video to inspect its recording clock.</p><dl id=\"video-source-summary\" class=\"source-summary\"><div><dt>Filename</dt> <dd id=\"video-source-filename\">Unavailable</dd></div> <div><dt>Detected date</dt> <dd id=\"video-source-date\">Not detected</dd></div> <div><dt>Detected timezone</dt> <dd id=\"video-source-timezone\">Not detected</dd></div> <div><dt>Source begin</dt> <dd id=\"video-source-begin\">Unavailable</dd></div> <div><dt>Source end</dt> <dd id=\"video-source-end\">Unavailable</dd></div></dl><div id=\"video-clock-candidates\" class=\"clock-candidates\" role=\"radiogroup\" aria-label=\"Detected recording-clock candidates\"></div><div class=\"field-grid\"><label>Video recording start<input id=\"video-recording-start\" type=\"datetime-local\" step=\".001\"></label><div><div class=\"help-label\"><label for=\"video-timezone\">Video timezone</label>"
     (contextual-help-link "video-timezone"
                           "Learn which video timezone to choose")
     "</div><input id=\"video-timezone\" type=\"text\" autocomplete=\"off\" placeholder=\"Europe/Warsaw\" aria-describedby=\"video-timezone-help\"><small id=\"video-timezone-help\">Enter a valid IANA timezone such as Europe/Warsaw or UTC.</small></div></div><div class=\"source-clock-actions\"><button id=\"confirm-video-clock\" type=\"button\">Confirm video clock</button><span id=\"video-clock-status\" class=\"status\" role=\"status\">Not confirmed.</span></div></section></section>"
     "<section id=\"video-player\" class=\"card video-player\" data-timing-workspace hidden><div class=\"section-head\"><div><h2>Timing workspace</h2><p class=\"muted\">Set the clock range, synchronization, and optional timer on one frame-accurate timeline.</p></div></div>"
     "<div id=\"video-chrome\" class=\"video-chrome\" hidden><div id=\"video-stage\" class=\"video-stage\" hidden><video id=\"source-video-player\" playsinline preload=\"metadata\"></video></div>"
     "<div id=\"video-time-context\" class=\"video-time-context\" aria-label=\"Elapsed time\"><div id=\"timeline-mode\"><span id=\"video-time-context-visual\" class=\"video-time-context-visual\"><strong id=\"timeline-mode-label\">Elapsed time</strong><span id=\"video-context-date-separator\" class=\"video-context-detail\" aria-hidden=\"true\" hidden>·</span><span id=\"video-context-date\" class=\"video-context-detail\" aria-hidden=\"true\" hidden></span><span id=\"video-context-zone-separator\" class=\"video-context-detail\" aria-hidden=\"true\" hidden>·</span><span id=\"video-context-zone\" class=\"video-context-detail\" aria-hidden=\"true\" hidden></span></span><p id=\"timeline-mode-status\" class=\"visually-hidden\" role=\"status\" aria-live=\"polite\">Timeline labels show elapsed source time.</p></div></div>"
     "<div id=\"video-controls-dock\" class=\"video-controls-dock\"><div class=\"video-transport\" aria-label=\"Video playback controls\" hidden><output id=\"video-time\" class=\"video-time\">00:00:00.000 / 00:00:00.000</output><span class=\"video-control\"><button type=\"button\" data-seek-seconds=\"-60\" aria-label=\"Jump back 60 seconds\" aria-keyshortcuts=\"Shift+ArrowLeft\">-60</button><span class=\"video-shortcut\" aria-hidden=\"true\">Shift+Left</span></span><span class=\"video-control\"><button type=\"button\" data-seek-seconds=\"-10\" aria-label=\"Jump back 10 seconds\" aria-keyshortcuts=\"ArrowLeft\">-10</button><span class=\"video-shortcut\" aria-hidden=\"true\">Left</span></span><span class=\"video-control\"><button id=\"video-play-pause\" type=\"button\" aria-label=\"Play video\" aria-keyshortcuts=\"Space\" disabled><span class=\"video-play-pause-symbol\" aria-hidden=\"true\">▶</span></button><span class=\"video-shortcut\" aria-hidden=\"true\">Space</span></span><span class=\"video-control\"><button type=\"button\" data-seek-seconds=\"10\" aria-label=\"Jump forward 10 seconds\" aria-keyshortcuts=\"ArrowRight\">+10</button><span class=\"video-shortcut\" aria-hidden=\"true\">Right</span></span><span class=\"video-control\"><button type=\"button\" data-seek-seconds=\"60\" aria-label=\"Jump forward 60 seconds\" aria-keyshortcuts=\"Shift+ArrowRight\">+60</button><span class=\"video-shortcut\" aria-hidden=\"true\">Shift+Right</span></span><label class=\"video-volume\">Volume<input id=\"video-volume\" type=\"range\" min=\"0\" max=\"1\" step=\"0.05\" value=\"1\" aria-label=\"Video volume\"></label><span id=\"video-fullscreen-control\" class=\"video-control\"><button id=\"video-fullscreen\" type=\"button\" aria-keyshortcuts=\"F\" aria-pressed=\"false\">Fullscreen</button><span id=\"video-fullscreen-shortcut\" class=\"video-shortcut\" aria-hidden=\"true\">F</span></span></div>"
     "<div id=\"video-timeline-wrap\" class=\"video-timeline-wrap\"><div id=\"video-timeline\" class=\"video-timeline\" role=\"slider\" tabindex=\"0\" aria-label=\"Output clock timeline\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" aria-disabled=\"true\"><div class=\"video-timeline-track\"><div id=\"video-buffered-ranges\" class=\"video-buffered-ranges\"></div><div id=\"video-output-range\" class=\"video-output-range\"></div><div id=\"video-unused-before\" class=\"video-unused-range\"></div><div id=\"video-unused-after\" class=\"video-unused-range\"></div><div id=\"video-playhead\" class=\"video-playhead\" style=\"left:0%\" hidden></div><button id=\"manual-sync-marker\" class=\"manual-sync-marker\" type=\"button\" role=\"slider\" aria-label=\"Selected source-video frame\" aria-controls=\"manual-sync-source-seconds telemetry-sync-at\" aria-describedby=\"manual-sync-marker-help video-range-status\" aria-keyshortcuts=\"ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight Home End\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" hidden></button><button id=\"timer-start-marker\" class=\"timer-marker timer-marker-start\" type=\"button\" role=\"slider\" aria-label=\"Timer start\" aria-controls=\"timer-start-at\" aria-describedby=\"timer-marker-help video-range-status\" aria-keyshortcuts=\"ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight Home End\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" hidden>S</button><button id=\"timer-end-marker\" class=\"timer-marker timer-marker-end\" type=\"button\" role=\"slider\" aria-label=\"Timer end\" aria-controls=\"timer-end-at\" aria-describedby=\"timer-marker-help video-range-status\" aria-keyshortcuts=\"ArrowLeft ArrowRight Shift+ArrowLeft Shift+ArrowRight Home End\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" hidden>E</button><button id=\"output-start-handle\" class=\"video-range-handle\" type=\"button\" role=\"slider\" aria-label=\"Output start\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" aria-describedby=\"video-range-status\" hidden></button><button id=\"output-end-handle\" class=\"video-range-handle\" type=\"button\" role=\"slider\" aria-label=\"Output end\" aria-valuemin=\"0\" aria-valuemax=\"0\" aria-valuenow=\"0\" aria-valuetext=\"Elapsed time, 00:00:00.000\" aria-describedby=\"video-range-status\" hidden></button></div><output id=\"video-timeline-tooltip\" class=\"video-timeline-tooltip\" aria-hidden=\"true\" hidden>00:00:00.000</output></div><div id=\"video-ticks\" class=\"video-ticks\" aria-hidden=\"true\"></div><div id=\"video-dates\" class=\"video-dates\" aria-hidden=\"true\" hidden></div><p id=\"manual-sync-marker-help\" class=\"video-sync-help\" hidden>Manual synchronization marker. Left or Right moves 1 frame. Shift+Left or Shift+Right moves 10 frames. Home or End moves to the source bounds.</p><p id=\"timer-marker-help\" class=\"video-sync-help\" hidden>Timer markers. Left or Right moves 1 frame. Shift+Left or Shift+Right moves 10 frames. Home or End moves within the output range.</p></div><div id=\"timing-dock\" class=\"timing-dock\" aria-label=\"Timing fields\"></div></div></div>"
     "<p id=\"video-player-status\" class=\"status video-player-status\" role=\"status\" hidden></p><p id=\"video-range-status\" class=\"status video-range-status\" role=\"status\">Output handles snap to 25 fps frames.</p><p id=\"video-audio-note\" class=\"hint video-audio-note\" hidden>Player audio is the original source. Output audio settings apply only during rendering.</p></section>"
     "<form id=\"render-form\" hx-post=\"/ui/jobs\" hx-target=\"#job-result\" hx-swap=\"innerHTML\">"
     "<input id=\"render-request\" type=\"hidden\" name=\"request\" value=\"{}\">"
     "<section id=\"matching-moment-step\" class=\"card\" data-wizard-panel data-step-id=\"matching-moment\" hidden><div class=\"section-head\"><div><h2>Match one moment</h2><p class=\"muted\">Choose one recognizable source-video frame and enter that same instant from the activity data. Heart-rate timestamps remain the clock authority.</p></div></div></section>"
     "<section id=\"output-timespan-step\" class=\"card\" data-wizard-panel data-step-id=\"output-timespan\" hidden><div class=\"section-head\"><div><h2>Choose the output timespan</h2><p class=\"muted\">Set the displayed timezone plus the start and end instants for the output you want to render.</p></div></div></section>"
     "<div class=\"field-grid\"><label id=\"display-time-zone-field\">Video/output timezone<select id=\"timezone\" required><option value=\"local\">My browser timezone</option><option value=\"UTC\">UTC</option><option value=\"Europe/Warsaw\">Europe/Warsaw</option><option value=\"Europe/London\">Europe/London</option><option value=\"America/New_York\">America/New_York</option><option value=\"America/Los_Angeles\">America/Los_Angeles</option><option value=\"Asia/Tokyo\">Asia/Tokyo</option><option value=\"Australia/Sydney\">Australia/Sydney</option></select><small>Submitted as required <code>displayTimeZone</code>; local timestamps are converted to absolute instants.</small></label>"
     "<div id=\"manual-synchronization-fields\" class=\"field-grid full\" hidden><div id=\"camera-sync-field\" class=\"sync-field\"><span class=\"field-label\">Selected source-video frame</span><output id=\"manual-sync-elapsed\">00:00:00.000</output><input id=\"manual-sync-source-seconds\" type=\"hidden\" value=\"\"><input id=\"camera-sync-at\" type=\"hidden\"><small class=\"field-hint\">Move the orange marker on the full-source timeline. It snaps to 25 fps frames.</small></div>"
     "<label id=\"telemetry-sync-field\" class=\"sync-field\">Activity-data time at the selected frame<input id=\"telemetry-sync-at\" type=\"datetime-local\" step=\"0.04\"><small class=\"field-hint\">Enter the same recognizable instant from the uploaded activity data.</small></label></div>"
     "<label id=\"output-start-field\">Output start<input id=\"section-start-at\" type=\"datetime-local\" step=\".04\" required></label>"
     "<label id=\"output-end-field\">Output end<input id=\"section-end-at\" type=\"datetime-local\" step=\".04\" required></label>"
     "<p id=\"no-source-range-status\" class=\"status full\" role=\"status\">Enter Output start and Output end to reveal the output clock timeline.</p>"
     "</div>"
     "<section id=\"activity-data-step\" class=\"card\" data-wizard-panel data-step-id=\"activity-data\" hidden><div class=\"section-head\"><div><div class=\"help-heading\"><h2>Choose your heart-rate file</h2>"
     (contextual-help-link "supported-activity-data"
                           "Learn about supported activity-data formats")
     "</div><p id=\"telemetry-file-guidance\" class=\"muted\">Upload a FIT or CSV file. Alpha Compose detects Garmin FIT or Polar CSV automatically.</p></div></div><div class=\"source-box\"><input id=\"telemetry-format\" type=\"hidden\"><input id=\"telemetry\" type=\"hidden\"><label for=\"telemetry-file\">Heart-rate file <small>Read locally in this browser and kept in memory only.</small></label><input id=\"telemetry-file\" type=\"file\" accept=\".fit,.csv,application/octet-stream,text/csv\" aria-describedby=\"telemetry-file-guidance telemetry-status\"><p id=\"telemetry-status\" class=\"status\" role=\"status\"></p></div></section>"
     "<section id=\"optional-overlays-step\" class=\"card\" data-wizard-panel data-step-id=\"optional-overlays\" hidden><div class=\"section-head\"><div><h2>Optional overlays</h2><p class=\"muted\">Add supporting data only when it is present in this render.</p></div></div>"
     "<div id=\"timer-option\" class=\"optional\" data-step-id=\"timer-overlay\"><label class=\"toggle\"><input id=\"timer-enabled\" type=\"checkbox\"> Show elapsed timer</label><div id=\"timer-fields\" hidden class=\"field-grid\"><label id=\"timer-start-field\" class=\"timer-field\">Timer start<input id=\"timer-start-at\" type=\"datetime-local\" step=\".04\"></label><label id=\"timer-end-field\" class=\"timer-field\">Timer end<input id=\"timer-end-at\" type=\"datetime-local\" step=\".04\"></label></div></div>"
     "<div id=\"spo2-option\" class=\"optional\" data-step-id=\"spo2-overlay\"><div class=\"toggle-help\"><label class=\"toggle\"><input id=\"spo2-enabled\" type=\"checkbox\"> Include optional OxiWear SpO2 (oxygen saturation)</label>"
     (contextual-help-link "oxygen-saturation-support"
                           "Learn about optional SpO2 data")
     "</div><div id=\"spo2-fields\" hidden class=\"source-box\"><label for=\"spo2-file\">Oxygen-saturation CSV file</label><input id=\"spo2-file\" type=\"file\" accept=\".csv,text/csv\"><label for=\"spo2-telemetry\" style=\"margin-top:1rem\">Or paste oxygen-saturation CSV</label><textarea id=\"spo2-telemetry\" placeholder=\"reading_time,spo2\n2026-07-17T10:00:00Z,97\"></textarea><p id=\"spo2-status\" class=\"status\" role=\"status\"></p></div></div>"
     "<div id=\"watermark-option\" class=\"optional\" data-step-id=\"watermark-overlay\"><label class=\"toggle\"><input id=\"watermark-enabled\" type=\"checkbox\"> Add a PNG watermark</label><div id=\"watermark-fields\" hidden class=\"source-box\"><label for=\"watermark-file\">PNG file <small>It is converted to base64 locally and sent with this request.</small></label><input id=\"watermark-file\" type=\"file\" accept=\"image/png,.png\"><p id=\"watermark-status\" class=\"status\" role=\"status\"></p></div></div></section>"
     "<div id=\"review-step\" data-wizard-panel data-step-id=\"review\" hidden><section class=\"card\"><h2>Your choices</h2><p class=\"muted\">Review each active choice. Edit returns to its populated step.</p><div id=\"review-sections\" class=\"review-sections\"></div></section><section class=\"card raw-panel\"><div class=\"raw-actions\"><button id=\"download-project-json\" type=\"button\">Download Project JSON</button><label class=\"button\" for=\"upload-project-json\">Upload Project JSON</label><input id=\"upload-project-json\" type=\"file\" accept=\".json,application/json\" hidden></div><p class=\"hint\">Project JSON stays private until you explicitly download, copy, upload, or paste it. It can include your activity data and bounded embedded assets, but excludes credentials, CSRF values, signed URLs, preview images, playback state, and job results.</p><details><summary>Advanced: inspect or edit Project JSON</summary><p class=\"hint\">Paste a Project JSON envelope and choose “Apply Project JSON”. Validation is atomic. The current workflow changes only after the whole envelope and any imported Drive reference succeed.</p><p class=\"hint\">The public render API accepts only the nested <code>renderRequest</code>, not this Project JSON envelope.</p><textarea id=\"project-json\" spellcheck=\"false\">{}</textarea><div class=\"raw-actions\"><button id=\"apply-project-json\" type=\"button\">Apply Project JSON</button><button id=\"copy-project-json\" type=\"button\">Copy Project JSON</button></div><p id=\"project-json-status\" class=\"status json-errors\" role=\"status\"></p></details></section><section class=\"card raw-panel\"><details><summary>Advanced: paste or inspect raw RenderRequest JSON</summary><p class=\"hint\">Paste a request and choose “Apply JSON to form”. The JSON is checked for structural errors first; form edits are reflected here before preview or submission.</p><p class=\"hint\">Alpha Compose calls these inputs activity data. The API contract uses the exact field names below.</p><p class=\"hint\"><strong>Accepted fields</strong></p><ul class=\"field-reference\"><li><code>telemetryFormat</code> and <code>telemetry</code> are required. Formats: <code>polar-csv</code>, <code>garmin-fit</code> (base64 FIT), or <code>oxiwear-hr-csv</code>.</li><li><code>preset</code> is required: <code>1080p25</code> (1920×1080, 25 fps, up to 8 minutes) or <code>2.7k25</code> (2704×1520, 25 fps, up to 4 minutes).</li><li><code>displayTimeZone</code> is required: a known IANA timezone such as <code>Europe/Warsaw</code> or <code>UTC</code>; it controls only the rendered local clock.</li><li><code>synchronizationMode</code> is required. Use <code>shared-clock</code> and omit both synchronization anchors when the devices used the same clock. Use <code>manual-anchor</code> and provide both <code>telemetrySyncAt</code> and <code>cameraSyncAt</code> when their clocks differed; <code>cameraSyncAt</code> may be before, within, or after the selected output.</li><li><code>sectionStartAt</code> and <code>sectionEndAt</code> are required ISO-8601 instants with <code>Z</code> or an explicit UTC offset.</li><li><code>futureTraceOpacityPercent</code> is optional: a number from <code>0</code> through <code>100</code>, in percent; default <code>25</code>.</li><li><code>transparentAlphaBits</code> is optional without <code>sourceVideo</code>: <code>8</code> or <code>16</code>; default <code>16</code>.</li><li><code>spo2</code> is optional: <code>{format:\"oxiwear-spo2-csv\", telemetry}</code>.</li><li><code>timer</code> is optional: <code>{startAt, endAt}</code>, within the requested section.</li><li><code>watermark</code> is optional: <code>{contentBase64}</code>, a bounded PNG encoded as base64.</li><li><code>sourceVideo</code> is optional: <code>{fileId, recordingStartAt, timeZone}</code>. The shared-clock confirmed or manual-sync derived start is an instant and <code>timeZone</code> is a valid IANA video timezone. When present, <code>outputFormat</code> (<code>h264-mp4</code> or <code>prores-422-mov</code>), <code>fitMode</code> (<code>letterbox</code>, <code>pillarbox</code>, or <code>crop</code>), and <code>audioMode</code> (<code>source+heartbeat</code>, <code>source-only</code>, or <code>heartbeat-only</code>) configure compositing.</li></ul><textarea id=\"raw-json\" spellcheck=\"false\">{}</textarea><div class=\"raw-actions\"><button id=\"apply-json\" type=\"button\">Apply JSON to form</button><button id=\"copy-json\" type=\"button\">Copy generated JSON</button></div><p id=\"json-status\" class=\"status json-errors\" role=\"status\"></p></details></section>"
     "<section class=\"card\">"
     (preview-admission-disclosure)
     "<div class=\"actions\"><button id=\"preview-button\" class=\"primary button-with-spinner\" type=\"button\" hx-post=\"/ui/preview\" hx-include=\"closest form\" hx-target=\"#preview-result\" hx-swap=\"outerHTML\" hx-request='{\"timeout\":15000}'><span class=\"button-spinner\" aria-hidden=\"true\" hidden></span><span>Preview</span></button><button id=\"submit-button\" class=\"primary\" type=\"submit\">Create finished video</button><span id=\"preview-submit-status\" class=\"status\" role=\"status\">Preview is optional. Create the finished video when ready.</span><span id=\"form-status\" class=\"status\" role=\"status\"></span></div></section></div></form>"
     "<nav id=\"wizard-navigation\" class=\"wizard-navigation\" aria-label=\"Wizard navigation\"><button id=\"wizard-back\" type=\"button\" disabled>Back</button><button id=\"wizard-next\" class=\"primary\" type=\"button\" hidden>Next</button></nav>"
     "<div class=\"results\"><div id=\"preview-result\"></div><div id=\"job-result\"></div></div></div>"
     (management-links-section {:tokens tokens
                                :members members
                                :logs-enabled? logs-enabled?
                                :user user})
     (contextual-help-dialog)
     "<script>(function(){"
     "const form=document.getElementById('render-form'), hidden=document.getElementById('render-request'), raw=document.getElementById('raw-json'), projectJson=document.getElementById('project-json');"
     "const status=document.getElementById('form-status'), jsonStatus=document.getElementById('json-status'), projectStatus=document.getElementById('project-json-status'),submitButton=document.getElementById('submit-button'),submitStatus=document.getElementById('preview-submit-status');"
     "const byId=id=>document.getElementById(id), value=id=>byId(id).value.trim();"
     "function show(node,message,kind){node.textContent=message;node.className='status'+(kind?' '+kind:'');}"
     "const projectSchemaVersion=" (json-script project/schema-version) ";"
     "const wizardState=" (json-script (wizard/browser-initial-state))
     ",wizardStepModel=" (json-script (wizard/browser-step-model))
     ",composeWorkflow=byId('compose-workflow'),wizardOutcomeStatus=byId('wizard-outcome-status'),wizardOutcomeInputs=[...document.querySelectorAll('input[name=\"wizard-outcome\"]')];"
     "const wizardHeader=byId('wizard-current-step-header'),wizardHeading=byId('wizard-current-step-heading'),wizardDescription=byId('wizard-current-step-description'),wizardProgress=byId('wizard-progress'),wizardOverview=byId('wizard-step-overview'),wizardStepList=byId('wizard-step-list'),wizardErrorSummary=byId('wizard-error-summary'),wizardNavigation=byId('wizard-navigation'),wizardBack=byId('wizard-back'),wizardNext=byId('wizard-next'),wizardPanels=[...document.querySelectorAll('[data-wizard-panel]')];"
     "let wizardAdvanceStep=null;const wizardAdvanceReadiness=Object.create(null);"
     (wizard/browser-state-script)
     "wizardOutcomeInputs.forEach(input=>input.addEventListener('change',()=>chooseWizardOutcome(input.value)));"
     "const playbackCsrf=" (json-script csrf) ",videoPlayer=byId('video-player'),videoChrome=byId('video-chrome'),sourceVideo=byId('source-video-player'),videoPlayPause=byId('video-play-pause'),videoPlayPauseSymbol=videoPlayPause.querySelector('.video-play-pause-symbol'),videoTimeContext=byId('video-time-context'),videoTimeContextVisual=byId('video-time-context-visual'),videoContextDate=byId('video-context-date'),videoContextDateSeparator=byId('video-context-date-separator'),videoContextZone=byId('video-context-zone'),videoContextZoneSeparator=byId('video-context-zone-separator'),videoTime=byId('video-time'),videoTimeline=byId('video-timeline'),videoPlayhead=byId('video-playhead'),videoTooltip=byId('video-timeline-tooltip'),videoTicks=byId('video-ticks'),videoDates=byId('video-dates'),videoBuffered=byId('video-buffered-ranges'),videoOutputRange=byId('video-output-range'),videoUnusedBefore=byId('video-unused-before'),videoUnusedAfter=byId('video-unused-after'),outputStartHandle=byId('output-start-handle'),outputEndHandle=byId('output-end-handle'),manualSyncMarker=byId('manual-sync-marker'),manualSyncHelp=byId('manual-sync-marker-help'),manualSyncSourceSeconds=byId('manual-sync-source-seconds'),manualSyncElapsed=byId('manual-sync-elapsed'),timelineModeLabel=byId('timeline-mode-label'),timelineModeStatus=byId('timeline-mode-status'),cameraSyncField=byId('camera-sync-field'),telemetrySyncField=byId('telemetry-sync-field'),timerEnabled=byId('timer-enabled'),timerStartMarker=byId('timer-start-marker'),timerEndMarker=byId('timer-end-marker'),timerMarkerHelp=byId('timer-marker-help'),timerStartField=byId('timer-start-field'),timerEndField=byId('timer-end-field'),videoRangeStatus=byId('video-range-status'),videoStatus=byId('video-player-status'),videoVolume=byId('video-volume'),videoFullscreen=byId('video-fullscreen'),videoFullscreenControl=byId('video-fullscreen-control'),videoFullscreenShortcut=byId('video-fullscreen-shortcut'),videoClockConfirmation=byId('video-clock-confirmation'),videoRecordingStart=byId('video-recording-start'),videoTimeZone=byId('video-timezone'),confirmVideoClockButton=byId('confirm-video-clock'),videoClockCandidates=byId('video-clock-candidates'),videoClockInspectionStatus=byId('video-clock-inspection-status'),videoClockStatus=byId('video-clock-status'),videoSourceFilename=byId('video-source-filename'),videoSourceDate=byId('video-source-date'),videoSourceTimeZone=byId('video-source-timezone'),videoSourceBegin=byId('video-source-begin'),videoSourceEnd=byId('video-source-end'),contextualHelpDialog=byId('contextual-help-dialog'),contextualHelpTitle=byId('contextual-help-title'),contextualHelpAnswer=byId('contextual-help-answer'),contextualHelpFull=byId('contextual-help-full'),contextualHelpClose=contextualHelpDialog.querySelector('.contextual-help-close');let playbackGeneration=0,clockInspectionGeneration=0,videoDuration=0,videoSourceDuration=null,videoScrubbing=false,rangeDragging=null,manualSyncDragging=false,manualSyncSeconds=0,timerDragging=null,timerStartSeconds=0,timerEndSeconds=0,outputStartSeconds=0,outputEndSeconds=0,videoClockConfirmed=false,videoClockSource=null,videoRecordingStartAt=null,videoSourceName=null,timelineModeKey=null,fullscreenHintTimer=null,contextualHelpOpener=null;"
     "const timingDock=byId('timing-dock'),videoTimelineWrap=byId('video-timeline-wrap');byId('timer-fields').classList.add('full');timingDock.append(...['display-time-zone-field','output-start-field','output-end-field','no-source-range-status','manual-synchronization-fields','timer-fields','video-range-status'].map(byId));const detectedBrowserTimeZone=Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC';byId('timezone').options[0].textContent='My browser timezone ('+detectedBrowserTimeZone+')';"
     "const sourceOutputControls=byId('source-output-controls'),noSourceOutputSummary=byId('no-source-output-summary'),transparentAlphaChoice=byId('transparent-alpha-choice'),videoStage=byId('video-stage'),videoTransport=document.querySelector('.video-transport'),videoAudioNote=byId('video-audio-note'),noSourceRangeStatus=byId('no-source-range-status');function hasSourceVideo(){return wizardState.activeRoute!=='transparent-overlay'&&!!value('source-video-file-id');}function setComposeSourceMode(source){sourceOutputControls.hidden=!source;noSourceOutputSummary.hidden=source;transparentAlphaChoice.hidden=source;videoStage.hidden=!source;videoTransport.hidden=!source;videoStatus.hidden=!source;videoAudioNote.hidden=!source;noSourceRangeStatus.hidden=source;outputStartHandle.hidden=!source;outputEndHandle.hidden=!source;videoPlayhead.hidden=!source;videoTimeline.setAttribute('aria-label',source?'Source video timeline':'Output clock timeline');if(source){videoChrome.hidden=false;videoTimelineWrap.hidden=false;}}"
     "function playbackTime(seconds){const total=Math.max(0,Math.round((Number.isFinite(seconds)?seconds:0)*1000)),hours=Math.floor(total/3600000),minutes=Math.floor(total%3600000/60000),wholeSeconds=Math.floor(total%60000/1000),milliseconds=total%1000;return [hours,minutes,wholeSeconds].map(value=>String(value).padStart(2,'0')).join(':')+'.'+String(milliseconds).padStart(3,'0');}"
     "const shortMonthNames=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'],longMonthNames=['January','February','March','April','May','June','July','August','September','October','November','December'];function timelineModeName(){const manual=finishedManualSynchronization();if(manual)return videoClockConfirmed&&videoClockSource==='manual-anchor'?'Synced recording time':'Elapsed time';if(videoClockConfirmed&&videoClockSource==='output-clock')return 'Output clock';return videoClockConfirmed?'Recording time':'Elapsed time';}function absoluteTimelineMode(){return timelineModeName()!=='Elapsed time'&&videoClockConfirmed&&!!videoRecordingStartAt&&validTimeZone(videoTimeZone.value.trim());}function videoClockInstant(seconds){return Date.parse(videoRecordingStartAt)+Math.round((Number(seconds)||0)*1000);}function videoClockParts(seconds){const instant=videoClockInstant(seconds),parts=dateParts(instant,videoTimeZone.value.trim());return {...parts,instant,milliseconds:new Date(instant).getUTCMilliseconds()};}function clockTimeText(parts){return [parts.hour,parts.minute,parts.second].map(value=>String(value).padStart(2,'0')).join(':')+'.'+String(parts.milliseconds).padStart(3,'0');}function compactDateText(parts){return String(parts.day).padStart(2,'0')+' '+shortMonthNames[parts.month-1]+' '+parts.year;}function accessibleDateText(parts){return String(parts.day).padStart(2,'0')+' '+longMonthNames[parts.month-1]+' '+parts.year;}function numericDateText(parts){return [parts.year,String(parts.month).padStart(2,'0'),String(parts.day).padStart(2,'0')].join('-');}function sameLocalDate(first,second){return first.year===second.year&&first.month===second.month&&first.day===second.day;}function compactDateRange(first,last){if(sameLocalDate(first,last))return compactDateText(first);if(first.year===last.year&&first.month===last.month)return String(first.day).padStart(2,'0')+'-'+String(last.day).padStart(2,'0')+' '+shortMonthNames[first.month-1]+' '+first.year;if(first.year===last.year)return String(first.day).padStart(2,'0')+' '+shortMonthNames[first.month-1]+'-'+String(last.day).padStart(2,'0')+' '+shortMonthNames[last.month-1]+' '+first.year;return compactDateText(first)+'-'+compactDateText(last);}function accessibleDateRange(first,last){if(sameLocalDate(first,last))return accessibleDateText(first);if(first.year===last.year&&first.month===last.month)return String(first.day).padStart(2,'0')+' to '+String(last.day).padStart(2,'0')+' '+longMonthNames[first.month-1]+' '+first.year;return accessibleDateText(first)+' to '+accessibleDateText(last);}function zoneOffsetAt(instant,zone){try{const part=new Intl.DateTimeFormat('en-GB',{timeZone:zone,timeZoneName:'longOffset'}).formatToParts(new Date(instant)).find(candidate=>candidate.type==='timeZoneName'),value=part?.value||'';return value&&value!=='GMT'?' ('+value+')':'';}catch(_error){return '';}}function videoClockText(seconds){return absoluteTimelineMode()?clockTimeText(videoClockParts(seconds)):playbackTime(seconds);}function videoClockAccessible(seconds){const mode=timelineModeName();if(!absoluteTimelineMode())return mode+', '+playbackTime(seconds);const parts=videoClockParts(seconds),zone=videoTimeZone.value.trim();return mode+', '+accessibleDateText(parts)+' at '+clockTimeText(parts)+', '+zone+zoneOffsetAt(parts.instant,zone);}function videoClockFullText(seconds,includeZone=true){if(!absoluteTimelineMode())return playbackTime(seconds);const parts=videoClockParts(seconds),zone=videoTimeZone.value.trim();return numericDateText(parts)+' '+clockTimeText(parts)+(includeZone?' '+zone:'');}"
     "function updateVideoSourceSummary(){const duration=knownSourceDuration();videoSourceFilename.textContent=videoSourceName||'Unavailable';videoSourceDate.textContent=videoClockConfirmed?videoClockFullText(0,false):'Not confirmed';videoSourceTimeZone.textContent=videoClockConfirmed?videoTimeZone.value.trim():'Not confirmed';videoSourceBegin.textContent=videoClockConfirmed?videoClockFullText(0):'Not confirmed';videoSourceEnd.textContent=videoClockConfirmed&&duration!==null?videoClockFullText(duration):'Unavailable';videoClockConfirmation.dataset.confirmed=String(videoClockConfirmed);}"
     "const outputFps=25,outputFrameSeconds=1/outputFps;function snapOutputFrame(seconds,mode='round'){const bias=mode==='ceil'?-1e-8:1e-8,frames=(mode==='floor'?Math.floor:mode==='ceil'?Math.ceil:Math.round)((Number(seconds)||0)*outputFps+bias);return Number((frames/outputFps).toFixed(2));}function presetMaximumSeconds(){return value('preset')==='2.7k25'?240:480;}function knownSourceDuration(){return Number.isFinite(videoSourceDuration)&&videoSourceDuration>0?videoSourceDuration:null;}function outputTimelineDuration(){return knownSourceDuration()||Math.max(outputEndSeconds,presetMaximumSeconds());}function sourceSecondsForField(id){if(!videoRecordingStartAt||!value(id))return null;try{return (Date.parse(localToIso(id))-Date.parse(videoRecordingStartAt))/1000;}catch(_error){return null;}}function validConfiguredRange(){const start=sourceSecondsForField('section-start-at'),end=sourceSecondsForField('section-end-at'),known=knownSourceDuration();return Number.isFinite(start)&&Number.isFinite(end)&&start>=0&&end>start&&Math.abs(start-snapOutputFrame(start))<.0001&&Math.abs(end-snapOutputFrame(end))<.0001&&(!known||end<=known+.0001)?{start:snapOutputFrame(start),end:snapOutputFrame(end)}:null;}function setOutputField(id,seconds){byId(id).value=isoToLocal(new Date(Date.parse(videoRecordingStartAt)+Math.round(seconds*1000)).toISOString());}function outputRangeEditable(){return wizardState.currentStep==='output-timespan'&&hasSourceVideo();}function manualSyncEditable(){return wizardState.currentStep==='matching-moment';}function timerEditable(){return wizardState.currentStep==='timer-overlay';}function updateOutputRange(){const duration=Math.max(outputFrameSeconds,outputTimelineDuration()),startRatio=Math.min(1,outputStartSeconds/duration),endRatio=Math.min(1,outputEndSeconds/duration),startPercent=startRatio*100,endPercent=endRatio*100;videoOutputRange.style.left=startPercent+'%';videoOutputRange.style.width=Math.max(0,endPercent-startPercent)+'%';videoUnusedBefore.style.left='0';videoUnusedBefore.style.width=startPercent+'%';videoUnusedAfter.style.left=endPercent+'%';videoUnusedAfter.style.width=Math.max(0,100-endPercent)+'%';[[outputStartHandle,outputStartSeconds],[outputEndHandle,outputEndSeconds]].forEach(([handle,seconds])=>{handle.style.left=(seconds/duration*100)+'%';handle.setAttribute('aria-valuemax',String(snapOutputFrame(duration,'floor')));handle.setAttribute('aria-valuenow',String(seconds));handle.setAttribute('aria-valuetext',videoClockAccessible(seconds));handle.disabled=!videoClockConfirmed||!outputRangeEditable();});}function initializeOutputRange(preserve=true){if(!videoClockConfirmed||!videoRecordingStartAt)return;const configured=preserve&&validConfiguredRange();if(configured){outputStartSeconds=configured.start;outputEndSeconds=configured.end;}else{outputStartSeconds=0;outputEndSeconds=Math.max(outputFrameSeconds,snapOutputFrame(Math.min(knownSourceDuration()||presetMaximumSeconds(),presetMaximumSeconds()),'floor'));setOutputField('section-start-at',outputStartSeconds);setOutputField('section-end-at',outputEndSeconds);}updateOutputRange();}"
     "function validTimerSeconds(){const start=sourceSecondsForField('timer-start-at'),end=sourceSecondsForField('timer-end-at');return Number.isFinite(start)&&Number.isFinite(end)&&start>=outputStartSeconds-.0001&&end<=outputEndSeconds+.0001&&end-start>=outputFrameSeconds-.0001?{start:snapOutputFrame(start),end:snapOutputFrame(end)}:null;}function updateTimerMarkers(){const configured=timerEnabled.checked&&videoClockConfirmed?validTimerSeconds():null,visible=!!configured,duration=Math.max(outputFrameSeconds,outputTimelineDuration());if(configured){timerStartSeconds=configured.start;timerEndSeconds=configured.end;}timerStartMarker.hidden=!visible;timerEndMarker.hidden=!visible;timerMarkerHelp.hidden=!visible;if(!visible){timerStartField.classList.remove('timer-field-active');timerEndField.classList.remove('timer-field-active');}let startTransform=timerStartSeconds<=.0001?'translateX(0)':'translateX(-100%)',endTransform=timerEndSeconds>=duration-.0001?'translateX(-100%)':'translateX(0)';if(timerEndSeconds-timerStartSeconds<=outputFrameSeconds+.0001){if(timerStartSeconds<=outputStartSeconds+.0001){startTransform='translateX(0)';endTransform='translateX(100%)';}else if(timerEndSeconds>=outputEndSeconds-.0001){startTransform='translateX(-200%)';endTransform='translateX(-100%)';}}timerStartMarker.style.transform=startTransform;timerEndMarker.style.transform=endTransform;[[timerStartMarker,timerStartSeconds,outputStartSeconds,timerEndSeconds-outputFrameSeconds],[timerEndMarker,timerEndSeconds,timerStartSeconds+outputFrameSeconds,outputEndSeconds]].forEach(([marker,seconds,minimum,maximum])=>{marker.style.left=(seconds/duration*100)+'%';marker.disabled=!visible||!timerEditable();marker.setAttribute('aria-valuemin',String(minimum));marker.setAttribute('aria-valuemax',String(maximum));marker.setAttribute('aria-valuenow',String(seconds));marker.setAttribute('aria-valuetext',videoClockAccessible(seconds));});}function initializeTimer(){if(!timerEnabled.checked)return;if(videoClockConfirmed&&videoRecordingStartAt){const inRange=sourceVideo.currentTime>=outputStartSeconds-.0001&&sourceVideo.currentTime<=outputEndSeconds+.0001,playhead=inRange?snapOutputFrame(sourceVideo.currentTime):outputStartSeconds;timerEndSeconds=outputEndSeconds;timerStartSeconds=Math.min(timerEndSeconds-outputFrameSeconds,Math.max(outputStartSeconds,playhead));setOutputField('timer-start-at',timerStartSeconds);setOutputField('timer-end-at',timerEndSeconds);}else{try{const start=Date.parse(localToIso('section-start-at')),end=Date.parse(localToIso('section-end-at'));if(Number.isFinite(start)&&Number.isFinite(end)&&end-start>=40){byId('timer-start-at').value=isoToLocal(new Date(start).toISOString());byId('timer-end-at').value=isoToLocal(new Date(end).toISOString());}}catch(_error){}}updateTimerMarkers();}"
     "function timerBoundarySeconds(id){if(!byId('timer-enabled').checked||!value(id))return null;return sourceSecondsForField(id);}function applyOutputBoundary(kind,proposed){const endLimit=snapOutputFrame(outputTimelineDuration(),'floor'),timerStart=timerBoundarySeconds('timer-start-at'),timerEnd=timerBoundarySeconds('timer-end-at');let next=snapOutputFrame(proposed),blocked=false;if(kind==='start'){const maximum=Math.min(outputEndSeconds-outputFrameSeconds,Number.isFinite(timerStart)?snapOutputFrame(timerStart,'floor'):Infinity);if(next>maximum){next=maximum;blocked=Number.isFinite(timerStart);}outputStartSeconds=Math.max(0,next);setOutputField('section-start-at',outputStartSeconds);byId('section-start-at').classList.add('range-receiver');}else{const minimum=Math.max(outputStartSeconds+outputFrameSeconds,Number.isFinite(timerEnd)?snapOutputFrame(timerEnd,'ceil'):0);if(next<minimum){next=minimum;blocked=Number.isFinite(timerEnd);}outputEndSeconds=Math.min(endLimit,next);setOutputField('section-end-at',outputEndSeconds);byId('section-end-at').classList.add('range-receiver');}show(videoRangeStatus,blocked?'Move or disable the timer before excluding it from the output.':'Output handles snap to 25 fps frames.',blocked?'error':'success');updateOutputRange();invalidatePreview();syncRequest(false);}"
     "function playableDuration(){return Number.isFinite(videoDuration)&&videoDuration>0;}function clampVideoTime(seconds){return playableDuration()?Math.min(videoDuration,Math.max(0,Number(seconds)||0)):0;}"
     "function localDateKey(parts){return [parts.year,String(parts.month).padStart(2,'0'),String(parts.day).padStart(2,'0')].join('-');}function firstLocalDateChange(low,high,key,zone){while(high-low>1){const middle=Math.floor((low+high)/2);if(localDateKey(dateParts(middle,zone))===key)low=middle;else high=middle;}return high;}function timelineDaySegments(duration){if(!absoluteTimelineMode()||!(duration>=0))return [];const zone=videoTimeZone.value.trim(),start=Date.parse(videoRecordingStartAt),end=start+Math.round(duration*1000),firstParts=dateParts(start,zone),segments=[{start:0,end:duration,key:localDateKey(firstParts),parts:firstParts}];let cursor=start;while(cursor<end){const probe=Math.min(end,cursor+3600000),current=segments.at(-1),probeParts=dateParts(probe,zone),probeKey=localDateKey(probeParts);if(probeKey===current.key){cursor=probe;continue;}const boundary=firstLocalDateChange(cursor,probe,current.key,zone),seconds=(boundary-start)/1000;current.end=seconds;const parts=dateParts(boundary,zone);segments.push({start:seconds,end:duration,key:localDateKey(parts),parts});cursor=boundary;}return segments;}function layoutVideoDateLabels(){const width=videoDates.clientWidth||videoTimeline.clientWidth||innerWidth;if(!(width>0))return;const rowEnds=[-Infinity,-Infinity];let maximumRow=0;for(const label of videoDates.children){const ratio=Number(label.dataset.centerRatio),labelWidth=Math.min(width,label.offsetWidth||96),ideal=ratio*width-labelWidth/2,left=Math.max(0,Math.min(width-labelWidth,ideal));let row=rowEnds.findIndex(right=>left>=right+8);if(row<0)row=rowEnds[0]<=rowEnds[1]?0:1;label.style.left=left+'px';label.style.top=(row*1.05)+'rem';rowEnds[row]=left+labelWidth;maximumRow=Math.max(maximumRow,row);}videoDates.style.height=(1.35+maximumRow*1.05)+'rem';}function updateTimelineDates(duration){const segments=timelineDaySegments(duration),key=segments.map(segment=>segment.key+':'+segment.start+':'+segment.end).join('|');if(videoDates.dataset.key===key){layoutVideoDateLabels();return;}videoDates.dataset.key=key;videoDates.replaceChildren();videoDates.hidden=!segments.length;if(!segments.length)return;for(const segment of segments){const label=document.createElement('span'),center=duration>0?(segment.start+segment.end)/2/duration:0;label.className='video-date-label';label.textContent=compactDateText(segment.parts);label.dataset.date=segment.key;label.dataset.startRatio=String(duration>0?segment.start/duration:0);label.dataset.endRatio=String(duration>0?segment.end/duration:1);label.dataset.centerRatio=String(center);videoDates.append(label);}layoutVideoDateLabels();}function updateTimelineContext(){const mode=timelineModeName(),absolute=absoluteTimelineMode(),duration=Math.max(0,outputTimelineDuration()),first=absolute?videoClockParts(0):null,last=absolute?videoClockParts(duration):null,dateText=absolute?compactDateRange(first,last):'',accessibleDate=absolute?accessibleDateRange(first,last):'',zone=absolute?videoTimeZone.value.trim():'';timelineModeLabel.textContent=mode;videoContextDate.textContent=dateText;videoContextZone.textContent=zone;videoContextDate.hidden=!absolute;videoContextDateSeparator.hidden=!absolute;videoContextZone.hidden=!absolute;videoContextZoneSeparator.hidden=!absolute;videoTimeContext.setAttribute('aria-label',absolute?mode+', '+accessibleDate+', '+zone:mode);updateTimelineDates(duration);}"
     "function updateVideoTransport(){const current=clampVideoTime(sourceVideo.currentTime),ratio=playableDuration()?current/videoDuration:0,paused=sourceVideo.paused,mode=timelineModeName();updateTimelineMode(false);videoTime.textContent=videoClockText(current)+' / '+videoClockText(videoDuration);videoTime.setAttribute('aria-label',mode+', current '+videoClockAccessible(current).slice(mode.length+2)+'; source end '+videoClockAccessible(videoDuration).slice(mode.length+2));videoPlayhead.style.left=(ratio*100)+'%';videoTimeline.setAttribute('aria-valuenow',String(current));videoTimeline.setAttribute('aria-valuetext',videoClockAccessible(current));videoPlayPause.setAttribute('aria-label',paused?'Play video':'Pause video');videoPlayPauseSymbol.textContent=paused?'▶':'⏸';}"
     "function updateVideoTicks(){videoTicks.replaceChildren();updateTimelineMode(false);const duration=outputTimelineDuration();if(!(duration>0))return;const width=videoTimeline.clientWidth||innerWidth,tickIntervals=width<560?2:4;for(let index=0;index<=tickIntervals;index++){const tick=document.createElement('span'),ratio=index/tickIntervals;tick.textContent=videoClockText(duration*ratio);tick.style.left=(ratio*100)+'%';tick.style.transform=index===0?'none':index===tickIntervals?'translateX(-100%)':'translateX(-50%)';videoTicks.append(tick);}updateTimelineDates(duration);updateOutputRange();}"
     "function updateBufferedRanges(){videoBuffered.replaceChildren();if(!playableDuration())return;for(let index=0;index<sourceVideo.buffered.length;index++){const start=clampVideoTime(sourceVideo.buffered.start(index)),end=clampVideoTime(sourceVideo.buffered.end(index)),range=document.createElement('span');range.style.left=(start/videoDuration*100)+'%';range.style.width=(Math.max(0,end-start)/videoDuration*100)+'%';videoBuffered.append(range);}}"
     "function updateOutputFraming(){sourceVideo.style.objectFit=value('fit-mode')==='crop'?'cover':'contain';}"
     "function setVideoControls(enabled){videoPlayPause.disabled=!enabled;document.querySelectorAll('[data-seek-seconds]').forEach(button=>button.disabled=!enabled);videoFullscreen.disabled=!enabled&&videoPlayer.hidden;videoVolume.disabled=!enabled;videoTimeline.setAttribute('aria-disabled',String(!enabled));}"
     "function seekVideo(seconds){if(!playableDuration())return;sourceVideo.currentTime=clampVideoTime(seconds);updateVideoTransport();}"
     "function toggleVideoPlayback(){if(sourceVideo.paused)sourceVideo.play().catch(()=>show(videoStatus,'Playback could not start. Try again.','error'));else sourceVideo.pause();}"
     "function clearFullscreenHint(){if(fullscreenHintTimer!==null){clearTimeout(fullscreenHintTimer);fullscreenHintTimer=null;}videoFullscreenControl.classList.remove('shortcut-auto');}function showFullscreenHint(){clearFullscreenHint();videoFullscreenControl.classList.add('shortcut-auto');fullscreenHintTimer=setTimeout(()=>{videoFullscreenControl.classList.remove('shortcut-auto');fullscreenHintTimer=null;},4000);}function syncVideoFullscreen(){const active=document.fullscreenElement===videoChrome;videoChrome.classList.toggle('is-fullscreen',active);videoFullscreen.textContent=active?'Exit fullscreen':'Fullscreen';videoFullscreen.setAttribute('aria-pressed',String(active));videoFullscreenShortcut.textContent=active?'F or Esc':'F';if(active)showFullscreenHint();else clearFullscreenHint();layoutVideoDateLabels();}function toggleVideoFullscreen(){if(document.fullscreenElement)document.exitFullscreen?.();else videoChrome.requestFullscreen?.();}"
     "function editableShortcutTarget(target){return target instanceof Element&&(target.isContentEditable||!!target.closest('input,select,textarea,[contenteditable]:not([contenteditable=false]),[role=textbox]'));}function handleVideoShortcut(event){if(event.defaultPrevented||event.ctrlKey||event.metaKey||event.altKey||contextualHelpDialog.open||videoPlayer.hidden||videoPlayPause.disabled||!playableDuration()||editableShortcutTarget(event.target))return;let handled=true;if(event.key==='ArrowLeft')seekVideo(sourceVideo.currentTime-(event.shiftKey?60:10));else if(event.key==='ArrowRight')seekVideo(sourceVideo.currentTime+(event.shiftKey?60:10));else if((event.key===' '||event.code==='Space')&&!event.shiftKey){if(event.target instanceof Element&&event.target.closest('button,[role=button]'))return;toggleVideoPlayback();}else if((event.key==='f'||event.key==='F')&&!event.shiftKey)toggleVideoFullscreen();else handled=false;if(handled)event.preventDefault();}"
     "function timelineSeconds(clientX){const rect=videoTimeline.getBoundingClientRect();return clampVideoTime((clientX-rect.left)/Math.max(1,rect.width)*videoDuration);}function showTimelineHover(clientX){if(!playableDuration())return;const rect=videoTimeline.getBoundingClientRect(),left=Math.min(rect.width,Math.max(0,clientX-rect.left)),seconds=timelineSeconds(clientX);videoTooltip.hidden=false;videoTooltip.style.left=left+'px';videoTooltip.textContent=videoClockText(seconds);}"
     "function resetVideoPlayback(){sourceVideo.pause();sourceVideo.removeAttribute('src');sourceVideo.load();videoDuration=0;sourceVideo.currentTime=0;videoTimeline.setAttribute('aria-valuemax','0');videoTicks.replaceChildren();videoBuffered.replaceChildren();videoTooltip.hidden=true;setVideoControls(false);updateVideoTransport();}"
     "function markVideoClockUnconfirmed(message='Confirm the video clock before preview or creation.'){videoClockConfirmed=false;videoClockSource=null;show(videoClockStatus,message);updateVideoSourceSummary();updateVideoTicks();updateVideoTransport();}"
     "function candidateWallTime(candidate){const value=String(candidate?.value||'').replace(' ','T');return candidate?.kind==='explicit-offset'?value.replace(/(?:Z|[+-]\\d{2}:?\\d{2})$/,''):value;}"
     "function applyClockCandidate(candidate){const wallTime=candidateWallTime(candidate);if(wallTime)videoRecordingStart.value=wallTime;markVideoClockUnconfirmed('Detected value loaded. Check it, choose the Video timezone, then confirm.');invalidatePreview();syncRequest(false);}"
     "function renderClockCandidates(candidates,recommendedIndex,ambiguous){videoClockCandidates.replaceChildren();candidates.forEach((candidate,index)=>{const label=document.createElement('label'),input=document.createElement('input'),copy=document.createElement('span');label.className='clock-candidate';input.type='radio';input.name='video-clock-candidate';input.value=String(index);input.checked=!ambiguous&&index===recommendedIndex;copy.textContent=(candidate.source==='track'?'Track':candidate.source==='movie'?'Movie':'Container')+': '+candidate.value+' ('+(candidate.kind==='explicit-offset'?'explicit offset':'timezone not stored')+')';input.addEventListener('change',()=>applyClockCandidate(candidate));label.append(input,copy);videoClockCandidates.append(label);});if(!ambiguous&&Number.isInteger(recommendedIndex)&&candidates[recommendedIndex])applyClockCandidate(candidates[recommendedIndex]);}"
     "async function inspectRecordingClock(file){const generation=++clockInspectionGeneration;videoClockConfirmation.hidden=false;videoSourceName=file.name||null;videoSourceDuration=null;videoRecordingStartAt=null;videoRecordingStart.value='';const browserZone=Intl.DateTimeFormat().resolvedOptions().timeZone;videoTimeZone.value=validTimeZone(browserZone)?browserZone:'';videoClockCandidates.replaceChildren();markVideoClockUnconfirmed('Not confirmed.');show(videoClockInspectionStatus,'Inspecting a bounded part of the original container…');try{const response=await fetch('/v1/drive/recording-clock-inspections',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':playbackCsrf},body:JSON.stringify({fileId:file.id})});if(generation!==clockInspectionGeneration)return;if(!response.ok)throw new Error('Inspection unavailable');const inspection=await response.json();if(generation!==clockInspectionGeneration)return;videoSourceName=typeof inspection.fileName==='string'&&inspection.fileName?inspection.fileName:videoSourceName;const inspectedDuration=Number(inspection.durationSeconds);videoSourceDuration=inspection.durationSeconds!==null&&inspection.durationSeconds!==undefined&&Number.isFinite(inspectedDuration)&&inspectedDuration>0?inspectedDuration:null;const candidates=Array.isArray(inspection.candidates)?inspection.candidates:[];renderClockCandidates(candidates,inspection.recommendedIndex,inspection.ambiguous===true);if(inspection.ambiguous)show(videoClockInspectionStatus,'Conflicting credible movie or track timestamps were found. Choose one or enter the start manually.','error');else if(candidates.length)show(videoClockInspectionStatus,'A container timestamp was found. It is advisory and still requires your confirmation.');else show(videoClockInspectionStatus,'No trustworthy container timestamp was found. Enter the start manually.');updateVideoSourceSummary();updateVideoTicks();}catch(_error){if(generation===clockInspectionGeneration){show(videoClockInspectionStatus,'Recording-clock inspection was unavailable within its limits. Enter the start manually.','error');updateVideoSourceSummary();updateVideoTicks();}}}"
     "function shiftInstantField(id,deltaMillis){if(!value(id))return;try{byId(id).value=isoToLocal(new Date(Date.parse(localToIso(id))+deltaMillis).toISOString());}catch(_error){}}function confirmVideoClock(){try{if(!value('source-video-file-id'))throw new Error('Choose a source video first.');const zone=videoTimeZone.value.trim();if(!validTimeZone(zone))throw new Error('Video timezone must be a valid IANA timezone identifier.');const instant=localTextToIso(videoRecordingStart.value.trim(),zone,'Video recording start'),previous=videoRecordingStartAt,delta=previous?Date.parse(instant)-Date.parse(previous):0;if(previous&&delta!==0){['camera-sync-at','section-start-at','section-end-at','timer-start-at','timer-end-at'].forEach(id=>shiftInstantField(id,delta));}videoRecordingStartAt=instant;videoClockConfirmed=true;videoClockSource='shared-clock';initializeOutputRange(true);show(videoClockStatus,'Video clock confirmed.','success');updateVideoSourceSummary();updateVideoTicks();updateVideoTransport();invalidatePreview();syncRequest(false);}catch(error){markVideoClockUnconfirmed(error.message);show(videoClockStatus,error.message,'error');}}"
     "function playbackMimeType(evidence){return evidence?.container?.format==='mov'?'video/quicktime':evidence?.container?.format==='mp4'?'video/mp4':'';}"
     "function playbackEvidenceText(evidence){const parts=[evidence?.container?.format&&evidence.container.format.toUpperCase(),evidence?.video?.codec&&evidence.video.codec.toUpperCase(),evidence?.video?.codecTag,evidence?.audio?.codec&&evidence.audio.codec.toUpperCase()].filter(Boolean);return parts.length?' ('+parts.join(', ')+')':'';}"
     "function sentence(text){const value=String(text||'').trim();return value?/^[^]*[.!?]$/.test(value)?value:value+'.':'';}"
     "async function playbackFailureDetail(response,label){const details=[];let body=null,raw='';try{if(typeof response?.json==='function')body=await response.json();}catch(_error){}if(!body)try{if(typeof response?.text==='function')raw=String(await response.text()||'').trim();}catch(_error){}const code=typeof body?.error==='string'&&body.error.trim()?body.error.trim():'';const status=Number(response?.status);const suffix=[Number.isFinite(status)&&status>0?String(status):'',code].filter(Boolean);if(typeof body?.message==='string'&&body.message.trim())details.push(body.message.trim());else if(typeof body?.guidance==='string'&&body.guidance.trim())details.push(body.guidance.trim());else if(typeof body?.field==='string'&&body.field.trim())details.push('Field: '+body.field.trim());else if(typeof body?.reason==='string'&&body.reason.trim())details.push('Reason: '+body.reason.trim());if(!details.length&&raw)details.push(raw.slice(0,240));return sentence(label+(suffix.length?' ('+suffix.join(', ')+')':'')+(details.length?': '+details.join(' '):''));}"
     "async function showPlaybackPreparationFailure(detail){setVideoControls(false);videoStage.hidden=true;show(videoStatus,'The selected video remains selected for rendering, but playback could not be prepared. '+sentence(detail||'Try again or choose another video.'),'error');}"
     "async function browserPlaybackSupport(evidence){const mimeType=playbackMimeType(evidence),codec=evidence?.video?.codecTag;if(!mimeType||!codec)return {supported:false,reason:'the source container or video codec could not be identified'};const canPlay=sourceVideo.canPlayType(mimeType+'; codecs=\"'+codec+'\"')!=='';if(!globalThis.VideoDecoder?.isConfigSupported)return {supported:canPlay,reason:canPlay?'':'this browser does not support the selected container and codec'};try{const webCodecs=await globalThis.VideoDecoder.isConfigSupported({codec});return {supported:!!webCodecs?.supported&&canPlay,reason:!webCodecs?.supported?'WebCodecs cannot decode the selected codec':!canPlay?'the media element cannot play the selected container and codec':''};}catch(_error){return {supported:false,reason:'WebCodecs could not verify the selected codec'};}}"
     "function showUnsupportedPlayback(evidence,reason){setVideoControls(false);videoStage.hidden=true;show(videoStatus,'This video cannot play in this browser because '+reason+playbackEvidenceText(evidence)+'. It remains selected for rendering.','error');}"
     "async function loadDrivePlayback(file){const generation=++playbackGeneration;setComposeSourceMode(true);resetVideoPlayback();updateOutputFraming();show(videoStatus,'Checking selected video playback…');try{const analysisResponse=await fetch('/v1/drive/playback-analyses',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':playbackCsrf},body:JSON.stringify({fileId:file.id})});if(generation!==playbackGeneration)return;if(!analysisResponse.ok){await showPlaybackPreparationFailure(await playbackFailureDetail(analysisResponse,'Playback analysis failed'));return;}const analysis=await analysisResponse.json(),support=await browserPlaybackSupport(analysis?.evidence);if(generation!==playbackGeneration)return;if(!support.supported){showUnsupportedPlayback(analysis?.evidence,support.reason);return;}const response=await fetch('/v1/drive/playback-sessions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':playbackCsrf},body:JSON.stringify({fileId:file.id})});if(generation!==playbackGeneration)return;if(!response.ok){await showPlaybackPreparationFailure(await playbackFailureDetail(response,'Playback session failed'));return;}const session=await response.json();if(generation!==playbackGeneration)return;if(!session||typeof session.playbackUrl!=='string'||!session.playbackUrl.startsWith('/v1/drive/playback/')){await showPlaybackPreparationFailure('Playback session returned an invalid browser playback URL');return;}videoStage.hidden=false;sourceVideo.src=session.playbackUrl;sourceVideo.load();}catch(error){if(generation===playbackGeneration)await showPlaybackPreparationFailure(error?.message);}}"
     "sourceVideo.addEventListener('loadedmetadata',()=>{videoDuration=Number(sourceVideo.duration);if(!playableDuration()){show(videoStatus,'This video remains selected for rendering, but its duration is unavailable for browser playback.','error');setVideoControls(false);initializeOutputRange(true);updateVideoSourceSummary();updateVideoTicks();return;}videoSourceDuration=videoDuration;sourceVideo.currentTime=0;videoTimeline.setAttribute('aria-valuemax',String(videoDuration));setVideoControls(true);initializeOutputRange(true);updateVideoTicks();updateBufferedRanges();updateVideoTransport();updateVideoSourceSummary();show(videoStatus,'Ready. Click or drag the timeline to seek.','success');});sourceVideo.addEventListener('timeupdate',updateVideoTransport);sourceVideo.addEventListener('progress',updateBufferedRanges);sourceVideo.addEventListener('play',updateVideoTransport);sourceVideo.addEventListener('pause',updateVideoTransport);sourceVideo.addEventListener('error',()=>{setVideoControls(false);initializeOutputRange(true);show(videoStatus,'This video remains selected for rendering, but its original format or codecs cannot play in this browser. Choose another video for playback.','error');});"
     "confirmVideoClockButton.addEventListener('click',confirmVideoClock);[videoRecordingStart,videoTimeZone].forEach(input=>input.addEventListener('input',()=>{if(videoClockConfirmed)markVideoClockUnconfirmed('Video clock changed. Confirm it again.');invalidatePreview();syncRequest(false);}));"
     "videoPlayPause.addEventListener('click',toggleVideoPlayback);document.querySelectorAll('[data-seek-seconds]').forEach(button=>button.addEventListener('click',()=>seekVideo(sourceVideo.currentTime+Number(button.dataset.seekSeconds))));videoVolume.addEventListener('input',()=>{sourceVideo.volume=Number(videoVolume.value);});videoFullscreen.addEventListener('click',toggleVideoFullscreen);document.addEventListener('fullscreenchange',syncVideoFullscreen);"
     "function outputSecondsAt(clientX){const rect=videoTimeline.getBoundingClientRect();return (clientX-rect.left)/Math.max(1,rect.width)*outputTimelineDuration();}function beginRangeDrag(kind,event){if(!videoClockConfirmed||!outputRangeEditable())return;event.preventDefault();event.stopPropagation();rangeDragging=kind;try{event.currentTarget.setPointerCapture(event.pointerId);}catch(_error){}applyOutputBoundary(kind,outputSecondsAt(event.clientX));}function continueRangeDrag(kind,event){if(rangeDragging!==kind)return;event.preventDefault();event.stopPropagation();applyOutputBoundary(kind,outputSecondsAt(event.clientX));}function endRangeDrag(event){if(!rangeDragging)return;event.preventDefault();event.stopPropagation();rangeDragging=null;try{event.currentTarget.releasePointerCapture(event.pointerId);}catch(_error){}}function handleOutputKey(kind,event){if(!outputRangeEditable())return;const current=kind==='start'?outputStartSeconds:outputEndSeconds,amount=(event.shiftKey?10:1)*outputFrameSeconds;if(event.key==='ArrowLeft')applyOutputBoundary(kind,current-amount);else if(event.key==='ArrowRight')applyOutputBoundary(kind,current+amount);else if(event.key==='Home')applyOutputBoundary(kind,0);else if(event.key==='End')applyOutputBoundary(kind,outputTimelineDuration());else return;event.preventDefault();event.stopPropagation();}[[outputStartHandle,'start'],[outputEndHandle,'end']].forEach(([handle,kind])=>{handle.addEventListener('pointerdown',event=>beginRangeDrag(kind,event));handle.addEventListener('pointermove',event=>continueRangeDrag(kind,event));handle.addEventListener('pointerup',endRangeDrag);handle.addEventListener('pointercancel',endRangeDrag);handle.addEventListener('keydown',event=>handleOutputKey(kind,event));});"
     "function finishedManualSynchronization(){return hasSourceVideo()&&wizardState.activeRoute==='finished-video'&&activeSynchronizationMode()==='manual-anchor';}"
     "function manualSyncLimit(){return Math.max(0,snapOutputFrame(outputTimelineDuration(),'floor'));}"
     "function setManualSyncHighlights(active){cameraSyncField.classList.toggle('sync-field-active',active);telemetrySyncField.classList.toggle('sync-field-related',active);}"
     "function updateManualSyncMarker(){const manual=activeSynchronizationMode()==='manual-anchor';manualSyncMarker.hidden=!manual;manualSyncHelp.hidden=!manual;if(!manual){setManualSyncHighlights(false);return;}const limit=manualSyncLimit(),finished=finishedManualSynchronization(),fieldValue=finished?value('manual-sync-source-seconds'):value('camera-sync-at'),fieldSeconds=finished&&fieldValue===''?null:finished?Number(fieldValue):sourceSecondsForField('camera-sync-at'),timelineAvailable=limit>0&&(finished?knownSourceDuration()!==null:videoClockConfirmed);if(Number.isFinite(fieldSeconds)&&fieldSeconds>=0&&fieldSeconds<=limit+.0001){manualSyncSeconds=snapOutputFrame(fieldSeconds);if(finished)manualSyncSourceSeconds.value=String(manualSyncSeconds);}else manualSyncSeconds=Math.min(limit,Math.max(0,manualSyncSeconds));manualSyncElapsed.textContent=playbackTime(manualSyncSeconds);manualSyncMarker.disabled=!timelineAvailable||!manualSyncEditable();manualSyncMarker.style.left=(outputTimelineDuration()>0?manualSyncSeconds/outputTimelineDuration()*100:0)+'%';manualSyncMarker.setAttribute('aria-label',finished?'Selected source-video frame':'Video time at matching moment');manualSyncMarker.setAttribute('aria-controls',finished?'manual-sync-source-seconds telemetry-sync-at':'camera-sync-at telemetry-sync-at');manualSyncMarker.setAttribute('aria-valuemax',String(limit));manualSyncMarker.setAttribute('aria-valuenow',String(manualSyncSeconds));manualSyncMarker.setAttribute('aria-valuetext',videoClockAccessible(manualSyncSeconds));}"
     "const updateOutputRangeFields=updateOutputRange;updateOutputRange=function(){const result=updateOutputRangeFields();updateManualSyncMarker();updateTimerMarkers();return result;};"
     "function applyManualSync(proposed){const limit=manualSyncLimit();manualSyncSeconds=Math.min(limit,Math.max(0,snapOutputFrame(proposed)));manualSyncSourceSeconds.value=String(manualSyncSeconds);manualSyncElapsed.textContent=playbackTime(manualSyncSeconds);if(finishedManualSynchronization())deriveManualSynchronization(true);else setOutputField('camera-sync-at',manualSyncSeconds);seekVideo(manualSyncSeconds);updateManualSyncMarker();show(videoRangeStatus,'Manual synchronization marker snaps to 25 fps frames.','success');invalidatePreview();syncRequest(false);}"
     "function beginManualSyncDrag(event){if(manualSyncMarker.hidden||manualSyncMarker.disabled)return;event.preventDefault();event.stopPropagation();manualSyncDragging=true;setManualSyncHighlights(true);try{manualSyncMarker.setPointerCapture(event.pointerId);}catch(_error){}applyManualSync(outputSecondsAt(event.clientX));}"
     "function continueManualSyncDrag(event){if(!manualSyncDragging)return;event.preventDefault();event.stopPropagation();applyManualSync(outputSecondsAt(event.clientX));}"
     "function endManualSyncDrag(event){if(!manualSyncDragging)return;event.preventDefault();event.stopPropagation();manualSyncDragging=false;try{manualSyncMarker.releasePointerCapture(event.pointerId);}catch(_error){}setManualSyncHighlights(document.activeElement===manualSyncMarker);}"
     "function handleManualSyncKey(event){const amount=(event.shiftKey?10:1)*outputFrameSeconds;if(event.key==='ArrowLeft')applyManualSync(manualSyncSeconds-amount);else if(event.key==='ArrowRight')applyManualSync(manualSyncSeconds+amount);else if(event.key==='Home')applyManualSync(0);else if(event.key==='End')applyManualSync(manualSyncLimit());else return;event.preventDefault();event.stopPropagation();}"
     "manualSyncMarker.addEventListener('pointerdown',beginManualSyncDrag);manualSyncMarker.addEventListener('pointermove',continueManualSyncDrag);manualSyncMarker.addEventListener('pointerup',endManualSyncDrag);manualSyncMarker.addEventListener('pointercancel',endManualSyncDrag);manualSyncMarker.addEventListener('keydown',handleManualSyncKey);manualSyncMarker.addEventListener('focus',()=>setManualSyncHighlights(true));manualSyncMarker.addEventListener('blur',()=>{if(!manualSyncDragging)setManualSyncHighlights(false);});byId('camera-sync-at').addEventListener('input',updateManualSyncMarker);"
     "function setTimerHighlights(kind,active){timerStartField.classList.toggle('timer-field-active',active&&kind==='start');timerEndField.classList.toggle('timer-field-active',active&&kind==='end');}function applyTimerBoundary(kind,proposed){if(!timerEnabled.checked)return;const next=snapOutputFrame(proposed);if(kind==='start'){timerStartSeconds=Math.max(outputStartSeconds,Math.min(timerEndSeconds-outputFrameSeconds,next));setOutputField('timer-start-at',timerStartSeconds);}else{timerEndSeconds=Math.min(outputEndSeconds,Math.max(timerStartSeconds+outputFrameSeconds,next));setOutputField('timer-end-at',timerEndSeconds);}updateTimerMarkers();show(videoRangeStatus,'Timer markers snap to 25 fps frames and stay inside the output range.','success');invalidatePreview();syncRequest(false);}function beginTimerDrag(kind,event){const marker=kind==='start'?timerStartMarker:timerEndMarker;if(marker.hidden||marker.disabled)return;event.preventDefault();event.stopPropagation();timerDragging=kind;setTimerHighlights(kind,true);try{marker.setPointerCapture(event.pointerId);}catch(_error){}applyTimerBoundary(kind,outputSecondsAt(event.clientX));}function continueTimerDrag(kind,event){if(timerDragging!==kind)return;event.preventDefault();event.stopPropagation();applyTimerBoundary(kind,outputSecondsAt(event.clientX));}function endTimerDrag(kind,event){if(timerDragging!==kind)return;const marker=kind==='start'?timerStartMarker:timerEndMarker;event.preventDefault();event.stopPropagation();timerDragging=null;try{marker.releasePointerCapture(event.pointerId);}catch(_error){}setTimerHighlights(kind,document.activeElement===marker);}function handleTimerKey(kind,event){const current=kind==='start'?timerStartSeconds:timerEndSeconds,amount=(event.shiftKey?10:1)*outputFrameSeconds;if(event.key==='ArrowLeft')applyTimerBoundary(kind,current-amount);else if(event.key==='ArrowRight')applyTimerBoundary(kind,current+amount);else if(event.key==='Home')applyTimerBoundary(kind,kind==='start'?outputStartSeconds:timerStartSeconds+outputFrameSeconds);else if(event.key==='End')applyTimerBoundary(kind,kind==='start'?timerEndSeconds-outputFrameSeconds:outputEndSeconds);else return;event.preventDefault();event.stopPropagation();}[[timerStartMarker,'start'],[timerEndMarker,'end']].forEach(([marker,kind])=>{marker.addEventListener('pointerdown',event=>beginTimerDrag(kind,event));marker.addEventListener('pointermove',event=>continueTimerDrag(kind,event));marker.addEventListener('pointerup',event=>endTimerDrag(kind,event));marker.addEventListener('pointercancel',event=>endTimerDrag(kind,event));marker.addEventListener('keydown',event=>handleTimerKey(kind,event));marker.addEventListener('focus',()=>setTimerHighlights(kind,true));marker.addEventListener('blur',()=>{if(timerDragging!==kind)setTimerHighlights(kind,false);});});[['timer-start-at','start'],['timer-end-at','end']].forEach(([id,kind])=>{byId(id).addEventListener('input',updateTimerMarkers);byId(id).addEventListener('change',()=>{const seconds=sourceSecondsForField(id);if(Number.isFinite(seconds))applyTimerBoundary(kind,seconds);else updateTimerMarkers();});});"
     "videoTimeline.addEventListener('pointerdown',event=>{if(event.target.closest?.('.video-range-handle')||!playableDuration())return;videoScrubbing=true;try{videoTimeline.setPointerCapture(event.pointerId);}catch(_error){}seekVideo(timelineSeconds(event.clientX));showTimelineHover(event.clientX);});videoTimeline.addEventListener('pointermove',event=>{if(event.target.closest?.('.video-range-handle'))return;showTimelineHover(event.clientX);if(videoScrubbing)seekVideo(timelineSeconds(event.clientX));});videoTimeline.addEventListener('pointerup',event=>{videoScrubbing=false;try{videoTimeline.releasePointerCapture(event.pointerId);}catch(_error){}});videoTimeline.addEventListener('pointercancel',()=>{videoScrubbing=false;});videoTimeline.addEventListener('pointerleave',()=>{if(!videoScrubbing)videoTooltip.hidden=true;});videoTimeline.addEventListener('keydown',event=>{if(event.target.closest?.('.video-range-handle'))return;const amount=event.shiftKey?10:1;if(event.key==='ArrowLeft'){event.preventDefault();seekVideo(sourceVideo.currentTime-amount);}else if(event.key==='ArrowRight'){event.preventDefault();seekVideo(sourceVideo.currentTime+amount);}else if(event.key==='Home'){event.preventDefault();seekVideo(0);}else if(event.key==='End'){event.preventDefault();seekVideo(videoDuration);}});"
     "document.addEventListener('keydown',handleVideoShortcut);window.addEventListener('resize',()=>updateVideoTicks());"
     "function contextualHelpTemplate(fragment){return [...contextualHelpDialog.querySelectorAll('template[data-contextual-help-fragment]')].find(template=>template.dataset.contextualHelpFragment===fragment);}function contextualHelpLink(fragment){return [...document.querySelectorAll('a.contextual-help')].find(link=>{try{return new URL(link.href,location.href).hash.slice(1)===fragment;}catch(_error){return false;}});}function openContextualHelp(fragment,opener,pushHistory){const template=contextualHelpTemplate(fragment),copy=template?.content.firstElementChild?.cloneNode(true);if(!template||!copy)return false;contextualHelpTitle.textContent=template.dataset.contextualHelpQuestion;contextualHelpAnswer.replaceChildren(...copy.childNodes);contextualHelpFull.setAttribute('href','/faq#'+fragment);contextualHelpOpener=opener||contextualHelpLink(fragment);if(!sourceVideo.paused)sourceVideo.pause();if(!contextualHelpDialog.open)contextualHelpDialog.showModal();contextualHelpClose.focus();if(pushHistory){const current=history.state&&typeof history.state==='object'?history.state:{};history.pushState({...current,contextualHelp:fragment},'',location.href);}return true;}function closeContextualHelp(){if(contextualHelpDialog.open)contextualHelpDialog.close();}function unwindContextualHelp(){if(history.state?.contextualHelp)history.back();else closeContextualHelp();}document.body.addEventListener('click',event=>{const link=event.target.closest?.('a.contextual-help');if(!link)return;let fragment;try{fragment=new URL(link.href,location.href).hash.slice(1);}catch(_error){return;}if(!contextualHelpTemplate(fragment))return;event.preventDefault();openContextualHelp(fragment,link,true);});contextualHelpClose.addEventListener('click',unwindContextualHelp);contextualHelpDialog.addEventListener('cancel',event=>{event.preventDefault();unwindContextualHelp();});contextualHelpDialog.addEventListener('close',()=>{const opener=contextualHelpOpener;contextualHelpOpener=null;if(opener?.isConnected)opener.focus();});window.addEventListener('popstate',event=>{const fragment=event.state?.contextualHelp;if(fragment)openContextualHelp(fragment,contextualHelpLink(fragment),false);else closeContextualHelp();});"
     "['output-format','fit-mode','audio-mode','transparent-alpha-bits','preset','future-trace-opacity-percent'].forEach(id=>byId(id).addEventListener('input',()=>{if(id==='fit-mode')updateOutputFraming();if(!hasSourceVideo()&&id==='preset')refreshNoSourceTimeline();invalidatePreview();syncRequest(false);renderWizardStep(false);}));updateOutputFraming();setVideoControls(false);syncVideoFullscreen();updateVideoSourceSummary();"
     "function activeZone(){const selected=value('timezone');return selected==='local'?Intl.DateTimeFormat().resolvedOptions().timeZone:selected;}function validTimeZone(zone){if(typeof zone!=='string'||!zone.trim()||/^(?:Z|[+-]\\d{2}(?::?\\d{2})?)$/.test(zone))return false;try{new Intl.DateTimeFormat('en-US',{timeZone:zone}).format(0);return true;}catch(_error){return false;}}function setDisplayTimeZone(zone){const selector=byId('timezone'),custom=[...selector.options].find(option=>option.dataset.customTimeZone==='true');if(custom&&custom.value!==zone)custom.remove();const exact=[...selector.options].find(option=>option.value===zone);if(exact){selector.value=zone;return;}if(zone===Intl.DateTimeFormat().resolvedOptions().timeZone){selector.value='local';return;}const option=document.createElement('option');option.value=zone;option.textContent=zone;option.dataset.customTimeZone='true';selector.append(option);selector.value=zone;}"
     "function dateParts(instant,zone){const parts=new Intl.DateTimeFormat('en-US',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(new Date(instant));return Object.fromEntries(parts.filter(part=>part.type!=='literal').map(part=>[part.type,Number(part.value)]));}"
     "function localTextToIso(text,zone,label){if(!text)throw new Error(label+' is required');const [date,time]=text.split('T'),dateValues=date.split('-').map(Number),timeValues=time.split(':');if(dateValues.length!==3||timeValues.length<2)throw new Error('Enter a valid date and time');const seconds=Number(timeValues[2]||0),wholeSeconds=Math.trunc(seconds),milliseconds=Math.round((seconds-wholeSeconds)*1000),target=Date.UTC(dateValues[0],dateValues[1]-1,dateValues[2],Number(timeValues[0]),Number(timeValues[1]),wholeSeconds)+milliseconds;let guess=target;for(let i=0;i<4;i++){const parts=dateParts(guess,zone),shown=Date.UTC(parts.year,parts.month-1,parts.day,parts.hour,parts.minute,parts.second)+milliseconds;guess+=target-shown;}const result=new Date(guess);if(Number.isNaN(result.getTime()))throw new Error('Enter a valid date and time');return result.toISOString();}function localToIso(input){return localTextToIso(value(input),activeZone(),input);}"
     "function isoToZoneLocal(instant,zone){const date=new Date(instant);if(Number.isNaN(date.getTime()))throw new Error('Invalid ISO-8601 timestamp: '+instant);const parts=dateParts(date.getTime(),zone),milliseconds=date.getUTCMilliseconds(),fraction=milliseconds?'.'+String(milliseconds).padStart(3,'0'):'';return [parts.year,String(parts.month).padStart(2,'0'),String(parts.day).padStart(2,'0')].join('-')+'T'+[String(parts.hour).padStart(2,'0'),String(parts.minute).padStart(2,'0'),String(parts.second).padStart(2,'0')].join(':')+fraction;}function isoToLocal(instant){return isoToZoneLocal(instant,activeZone());}"
     "function manualActivityInstant(){if(!value('telemetry-sync-at'))return null;try{return localTextToIso(value('telemetry-sync-at'),activeZone(),'Activity-data time at the selected frame');}catch(_error){return null;}}function updateTimelineMode(announce=false){const mode=timelineModeName(),zone=absoluteTimelineMode()?videoTimeZone.value.trim():'',key=mode+':'+zone,previous=timelineModeKey;timelineModeKey=key;updateTimelineContext();if(mode==='Synced recording time'){if(announce&&previous!==key)show(timelineModeStatus,'Timeline labels now show synced recording time ('+zone+').','success');else if(!timelineModeStatus.textContent||previous!==key)show(timelineModeStatus,'Timeline labels show synced recording time ('+zone+').');}else if(mode==='Elapsed time'){if(announce&&previous!==key)show(timelineModeStatus,'Timeline labels now show elapsed time.','');else if(!timelineModeStatus.textContent||previous!==key)show(timelineModeStatus,'Timeline labels show elapsed source time.','');}else if(previous!==key)show(timelineModeStatus,mode==='Output clock'?'Timeline labels show the output clock ('+zone+').':'Timeline labels show recording time ('+zone+').','');}"
     "function deriveManualSynchronization(announce=false){if(!finishedManualSynchronization())return false;const activity=manualActivityInstant(),zone=activeZone(),seconds=Number(value('manual-sync-source-seconds'));if(!activity||!validTimeZone(zone)||!Number.isFinite(seconds)||seconds<0){if(videoClockSource==='manual-anchor'){videoClockConfirmed=false;videoClockSource=null;videoRecordingStartAt=null;byId('camera-sync-at').value='';updateTimelineMode(announce);updateOutputRange();updateVideoTicks();updateVideoTransport();}else updateTimelineMode(announce);return false;}const previousStart=videoRecordingStartAt?Date.parse(videoRecordingStartAt):NaN,offsets={};if(Number.isFinite(previousStart)){for(const id of ['section-start-at','section-end-at','timer-start-at','timer-end-at']){if(!value(id))continue;try{offsets[id]=Date.parse(localTextToIso(value(id),zone,id))-previousStart;}catch(_error){}}}manualSyncSeconds=snapOutputFrame(seconds);manualSyncSourceSeconds.value=String(manualSyncSeconds);manualSyncElapsed.textContent=playbackTime(manualSyncSeconds);videoRecordingStartAt=new Date(Date.parse(activity)-Math.round(manualSyncSeconds*1000)).toISOString();videoTimeZone.value=zone;videoRecordingStart.value='';videoClockConfirmed=true;videoClockSource='manual-anchor';byId('camera-sync-at').value=isoToZoneLocal(activity,zone);updateTimelineMode(announce);if(Number.isFinite(offsets['section-start-at'])&&Number.isFinite(offsets['section-end-at'])){for(const [id,offset] of Object.entries(offsets))byId(id).value=isoToZoneLocal(new Date(Date.parse(videoRecordingStartAt)+offset).toISOString(),zone);initializeOutputRange(true);}else initializeOutputRange(false);updateVideoSourceSummary();updateVideoTicks();updateVideoTransport();updateManualSyncMarker();return true;}"
     "function noSourceRange(){if(hasSourceVideo())return null;const zone=activeZone();if(!validTimeZone(zone))throw new Error('Video/output timezone must be a valid IANA timezone identifier.');if(!value('section-start-at'))throw new Error('Output start is required.');if(!value('section-end-at'))throw new Error('Output end is required.');const startAt=localTextToIso(value('section-start-at'),zone,'Output start'),endAt=localTextToIso(value('section-end-at'),zone,'Output end'),durationMillis=Date.parse(endAt)-Date.parse(startAt);if(!(durationMillis>0))throw new Error('Output end must be after Output start.');if(Math.abs(durationMillis/40-Math.round(durationMillis/40))>.0001)throw new Error('Output range must contain whole 25 fps frames in 40 ms steps.');if(durationMillis>presetMaximumSeconds()*1000)throw new Error('Output range exceeds the selected preset maximum.');return {zone,startAt,durationSeconds:Number((durationMillis/1000).toFixed(2))};}function hideNoSourceTimeline(message){if(hasSourceVideo())return;videoChrome.hidden=false;videoTimelineWrap.hidden=true;videoClockConfirmed=false;videoClockSource=null;videoRecordingStartAt=null;videoSourceDuration=null;videoTimeline.setAttribute('aria-valuemax','0');videoTimeline.setAttribute('aria-valuenow','0');videoTimeline.setAttribute('aria-valuetext','Elapsed time, 00:00:00.000');videoTimeline.setAttribute('aria-disabled','true');videoTicks.replaceChildren();videoDates.replaceChildren();videoDates.hidden=true;videoDates.dataset.key='';updateTimelineMode(false);manualSyncMarker.hidden=true;manualSyncHelp.hidden=true;timerStartMarker.hidden=true;timerEndMarker.hidden=true;timerMarkerHelp.hidden=true;show(noSourceRangeStatus,message,'error');}function refreshNoSourceTimeline(){if(hasSourceVideo())return;setComposeSourceMode(false);try{const range=noSourceRange();videoRecordingStartAt=range.startAt;videoTimeZone.value=range.zone;videoClockConfirmed=true;videoClockSource='output-clock';videoSourceDuration=range.durationSeconds;outputStartSeconds=0;outputEndSeconds=range.durationSeconds;videoDuration=0;videoChrome.hidden=false;videoTimelineWrap.hidden=false;videoTimeline.setAttribute('aria-valuemax',String(range.durationSeconds));videoTimeline.setAttribute('aria-disabled','false');updateVideoTransport();updateVideoTicks();updateOutputRange();updateManualSyncMarker();updateTimerMarkers();show(noSourceRangeStatus,'Output timeline ready.','success');}catch(error){hideNoSourceTimeline(error.message);}}"
     "const fileBackedValues=Object.create(null);function contentValue(id){return Object.prototype.hasOwnProperty.call(fileBackedValues,id)?String(fileBackedValues[id]).trim():value(id);}function setFileBackedValue(id,content){fileBackedValues[id]=content;byId(id).value=content;invalidatePreview();}function clearFileBackedValue(id){delete fileBackedValues[id];}"
     "function required(id,label){const result=contentValue(id);if(!result)throw new Error(label+' is required');return result;}"
     "function boundedNumber(id,label,minimum,maximum){const text=value(id),result=Number(text);if(!text||!Number.isFinite(result)||result<minimum||result>maximum)throw new Error(label+' must be a number from '+minimum+' through '+maximum);return result;}"
     "const synchronizationModeInputs=[...document.querySelectorAll('input[name=\"synchronization-mode\"]')],manualSynchronizationFields=byId('manual-synchronization-fields');function selectedSynchronizationMode(){return synchronizationModeInputs.find(input=>input.checked)?.value||'';}function synchronizationReady(){const mode=activeSynchronizationMode();if(!mode)return false;if(!hasSourceVideo())return true;return mode==='manual-anchor'?videoClockConfirmed&&videoClockSource==='manual-anchor':videoClockConfirmed&&videoClockSource==='shared-clock';}function updateSynchronizationMode(){const manual=selectedSynchronizationMode()==='manual-anchor',finished=hasSourceVideo()&&wizardState.activeRoute==='finished-video';byId('camera-sync-at').type=finished?'hidden':'datetime-local';manualSyncElapsed.hidden=!finished;if(finished&&manual&&videoClockSource!=='manual-anchor'){videoClockConfirmed=false;videoClockSource=null;videoRecordingStartAt=null;byId('camera-sync-at').value='';}if(finished&&manual)deriveManualSynchronization(false);updateTimelineMode(false);updateVideoTicks();updateVideoTransport();updateManualSyncMarker();setSubmitDisabled();setPreviewActive(previewActive);captureWizardState(wizardState.renderRequest);renderWizardStep(false);}synchronizationModeInputs.forEach(input=>input.addEventListener('change',updateSynchronizationMode));byId('telemetry-sync-at').addEventListener('input',()=>{if(finishedManualSynchronization())deriveManualSynchronization(true);setSubmitDisabled();setPreviewActive(previewActive);});"
     (wizard/browser-request-script)
     "const requestFields=['telemetryFormat','telemetry','preset','displayTimeZone','futureTraceOpacityPercent','transparentAlphaBits','synchronizationMode','telemetrySyncAt','cameraSyncAt','sectionStartAt','sectionEndAt','spo2','timer','watermark','sourceVideo','outputFormat','fitMode','audioMode'],requiredRequestFields=['telemetryFormat','telemetry','preset','displayTimeZone','synchronizationMode','sectionStartAt','sectionEndAt'],isoPattern=/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$/;"
     "function isObject(candidate){return candidate!==null&&typeof candidate==='object'&&!Array.isArray(candidate);}function has(candidate,key){return Object.prototype.hasOwnProperty.call(candidate,key);}function utf8Length(text){return new TextEncoder().encode(text).length;}function instantValue(value){return typeof value==='string'&&isoPattern.test(value)&&!Number.isNaN(Date.parse(value));}function unknownFields(candidate,allowed,path,errors){Object.keys(candidate).filter(key=>!allowed.includes(key)).forEach(key=>errors.push(path+' contains unknown field '+key+'.'));}function requiredString(candidate,key,path,errors){if(!has(candidate,key)||candidate[key]===''){errors.push(path+'.'+key+' is required.');return false;}if(typeof candidate[key]!=='string'){errors.push(path+'.'+key+' must be a string.');return false;}return true;}function validateInstant(candidate,key,path,errors){if(!requiredString(candidate,key,path,errors))return false;if(!instantValue(candidate[key])){errors.push(path+'.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');return false;}return true;}"
     "const validateRequestStructure=validateRequest;validateRequest=function(request){const errors=validateRequestStructure(request);if(isObject(request)){if(has(request,'displayTimeZone')&&typeof request.displayTimeZone==='string'&&request.displayTimeZone&&!validTimeZone(request.displayTimeZone))errors.push('Request.displayTimeZone must be a valid IANA timezone identifier.');const mode=request.synchronizationMode;if(typeof mode==='string'&&mode&&!['shared-clock','manual-anchor'].includes(mode))errors.push('Request.synchronizationMode must be shared-clock or manual-anchor.');if(mode==='shared-clock'){['telemetrySyncAt','cameraSyncAt'].forEach(key=>{if(has(request,key))errors.push('Request.'+key+' must be omitted when Request.synchronizationMode is shared-clock.');});const start=Date.parse(request.sectionStartAt),end=Date.parse(request.sectionEndAt);if(!Number.isNaN(start)&&!Number.isNaN(end)&&!(start<end))errors.push('Request timestamps must satisfy sectionStartAt < sectionEndAt.');}else if(mode==='manual-anchor'){['telemetrySyncAt','cameraSyncAt'].forEach(key=>{if(!has(request,key))errors.push('Request.'+key+' is required when Request.synchronizationMode is manual-anchor.');else if(typeof request[key]!=='string'||!request[key])errors.push('Request.'+key+' must be an ISO-8601 instant with Z or an explicit UTC offset.');});}const sectionStart=Date.parse(request.sectionStartAt),sectionEnd=Date.parse(request.sectionEndAt),durationMillis=sectionEnd-sectionStart;if(Number.isFinite(durationMillis)&&durationMillis>0){if(Math.abs(durationMillis/40-Math.round(durationMillis/40))>.0001)errors.push('Request section duration must be a whole 25 fps frames in 40 ms steps.');const maximumMillis=request.preset==='2.7k25'?240000:480000;if(durationMillis>maximumMillis)errors.push('Request section duration exceeds the preset maximum.');}if(isObject(request?.sourceVideo)){if(typeof request.sourceVideo.timeZone==='string'&&request.sourceVideo.timeZone&&!validTimeZone(request.sourceVideo.timeZone))errors.push('Request.sourceVideo.timeZone must be a valid IANA timezone identifier.');const recordingStart=Date.parse(request.sourceVideo.recordingStartAt),trimMillis=sectionStart-recordingStart;if(Number.isFinite(trimMillis)){if(trimMillis<0)errors.push('Request.sectionStartAt cannot precede sourceVideo.recordingStartAt.');else if(Math.abs(trimMillis/40-Math.round(trimMillis/40))>.0001)errors.push('Request.sectionStartAt must select a whole 25 fps source frame.');}}}return errors;};"
     "const validateRequestWithoutLegacyCameraOrder=validateRequest;validateRequest=function(request){const errors=validateRequestWithoutLegacyCameraOrder(request).filter(error=>error!=='Request timestamps must satisfy cameraSyncAt <= sectionStartAt < sectionEndAt.');if(isObject(request?.timer)){const start=Date.parse(request.timer.startAt),end=Date.parse(request.timer.endAt);if(Number.isFinite(start)&&Number.isFinite(end)&&end-start<40)errors.push('Request.timer must span at least one 25 fps frame (40 ms).');}return errors;};"
     "function writeRequest(request){const text=JSON.stringify(request,null,2);hidden.value=text;raw.value=text;return text;}"
     "function syncRequest(showError){try{const request=buildRequest(),text=writeRequest(request);captureWizardState(request);renderWizardAdvance(wizardState.currentStep);show(status,'Ready to preview or create the finished video.','success');return text;}catch(error){captureWizardState(null,error.message);renderWizardAdvance(wizardState.currentStep);if(showError!==false)show(status,error.message,'error');return null;}}"
     "function refreshOptional(toggleId,fieldsId){byId(fieldsId).hidden=!byId(toggleId).checked;}"
     "[['spo2-enabled','spo2-fields'],['watermark-enabled','watermark-fields']].forEach(([toggle,fields])=>byId(toggle).addEventListener('change',()=>{refreshOptional(toggle,fields);syncRequest(false);renderWizardStep(false);}));timerEnabled.addEventListener('change',()=>{refreshOptional('timer-enabled','timer-fields');if(timerEnabled.checked&&!value('timer-start-at')&&!value('timer-end-at'))initializeTimer();else updateTimerMarkers();syncRequest(false);renderWizardStep(false);});"
     "function bytesToBase64(buffer){const bytes=new Uint8Array(buffer);let binary='';for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));return btoa(binary);}"
     "function readTextFile(inputId,targetId,statusId){const input=byId(inputId);input.addEventListener('change',()=>{const file=input.files&&input.files[0];if(!file)return;setFileBackedValue(targetId,'');show(byId(statusId),'Reading '+file.name+'…');syncRequest(false);const reader=new FileReader();reader.onload=()=>{setFileBackedValue(targetId,String(reader.result));show(byId(statusId),'Loaded '+file.name+'.','success');syncRequest(false);};reader.onerror=()=>show(byId(statusId),'Could not read '+file.name+'.','error');reader.readAsText(file);});}"
     "function readBinaryFile(inputId,targetId,statusId){const input=byId(inputId);input.addEventListener('change',()=>{const file=input.files&&input.files[0];if(!file)return;setFileBackedValue(targetId,'');show(byId(statusId),'Reading '+file.name+'…');syncRequest(false);const reader=new FileReader();reader.onload=()=>{setFileBackedValue(targetId,bytesToBase64(reader.result));show(byId(statusId),'Loaded '+file.name+'.','success');syncRequest(false);};reader.onerror=()=>show(byId(statusId),'Could not read '+file.name+'.','error');reader.readAsArrayBuffer(file);});}"
     "const telemetryFormat=byId('telemetry-format'),telemetryFile=byId('telemetry-file'),telemetryContent=byId('telemetry'),telemetryStatus=byId('telemetry-status'),maxActivityFileBytes=10*1024*1024;let telemetryReadGeneration=0;"
     "function clearTelemetryValue(){clearFileBackedValue('telemetry');telemetryContent.value='';telemetryFormat.value='';}function activityFileExtension(file){const match=String(file.name||'').toLowerCase().match(/\\.[^.]+$/);return match?match[0]:'';}function activityCsvCell(text){const value=String(text||'').trim().replace(/^\\ufeff/,'');return value.length>=2&&value[0]==='\"'&&value[value.length-1]==='\"'?value.slice(1,-1).trim():value;}function activityCsvColumns(line,delimiter){return String(line||'').split(delimiter).map(activityCsvCell);}"
     "function inspectActivityCsv(text){const lines=String(text).replace(/^\\ufeff/,'').split(/\\r?\\n/),headerLine=lines.shift()||'',delimiter=headerLine.includes(';')?';':',',headers=activityCsvColumns(headerLine,delimiter).map(header=>header.toLowerCase()),indexOfAny=accepted=>headers.findIndex(header=>accepted.includes(header)),polarTime=indexOfAny(['timestamp','date/time','datetime']),polarRate=indexOfAny(['heart_rate','heart rate','heart rate (bpm)','hr','hr (bpm)']),oxiwearTime=headers.indexOf('reading_time'),oxiwearRate=headers.indexOf('pulse_rate'),polar=polarTime>=0&&polarRate>=0,oxiwear=oxiwearTime>=0&&oxiwearRate>=0;if(polar&&oxiwear)throw new Error('This CSV matches more than one supported heart-rate format. Export one original activity file and try again.');if(!polar&&!oxiwear){if(headers.includes('activity type')&&headers.includes('date'))throw new Error('This is an activity-list summary, not timestamped heart-rate data. In Garmin Connect, open the individual activity and choose Export Original.');throw new Error('This CSV does not contain compatible Polar timestamp and heart-rate columns. Export one Polar training session as CSV.');}const rows=lines.map((line,index)=>({line,index:index+2})).filter(row=>row.line.trim()),instantPattern=/^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$/;if(!rows.length)throw new Error('This heart-rate CSV has no sample rows. Export the individual activity again.');const timeIndex=polar?polarTime:oxiwearTime,rateIndex=polar?polarRate:oxiwearRate;for(const row of rows){const values=activityCsvColumns(row.line,delimiter),timestamp=values[timeIndex],heartRate=Number(values[rateIndex]);if(!instantPattern.test(timestamp||'')||!Number.isFinite(Date.parse(timestamp))||!Number.isFinite(heartRate)||heartRate<20||heartRate>260)throw new Error('This heart-rate CSV has invalid timestamp or heart-rate data at row '+row.index+'. Export the individual activity again.');}return polar?'polar-csv':'oxiwear-hr-csv';}"
     "function compatibleGarminFit(buffer){const bytes=new Uint8Array(buffer);if(bytes.length<14)return false;const headerSize=bytes[0],signature=String.fromCharCode(...bytes.subarray(8,12)),dataSize=new DataView(buffer).getUint32(4,true);return headerSize>=12&&headerSize<=bytes.length&&signature==='.FIT'&&bytes.length>=headerSize+dataSize+2;}"
     "function beginActivityFileSelection(input,statusNode){const generation=++telemetryReadGeneration;clearTelemetryValue();invalidatePreview();show(telemetryStatus,'');const file=input.files&&input.files[0];if(!file){syncRequest(false);return null;}if(file.size>maxActivityFileBytes){show(statusNode,'Heart-rate files must be 10 MiB or smaller. Export a smaller individual activity file.','error');syncRequest(false);return null;}show(statusNode,'Reading the heart-rate file…');syncRequest(false);return {generation,file,statusNode};}function rejectActivityFile(selection,message){if(selection.generation!==telemetryReadGeneration)return;clearTelemetryValue();show(selection.statusNode,message,'error');syncRequest(false);}function acceptActivityFile(selection,format,content,label){if(selection.generation!==telemetryReadGeneration)return;telemetryFormat.value=format;setFileBackedValue('telemetry',content);show(selection.statusNode,label,'success');syncRequest(false);}"
     "function readActivityFile(selection,binary,onload){const reader=new FileReader();reader.onload=()=>{if(selection.generation!==telemetryReadGeneration)return;try{onload(reader.result);}catch(error){rejectActivityFile(selection,error.message);}};reader.onerror=()=>rejectActivityFile(selection,'Could not read this heart-rate file. Choose it again or export a fresh copy.');if(binary)reader.readAsArrayBuffer(selection.file);else reader.readAsText(selection.file);}"
     "telemetryFile.addEventListener('change',()=>{const selection=beginActivityFileSelection(telemetryFile,telemetryStatus);if(!selection)return;const extension=activityFileExtension(selection.file);if(!['.fit','.csv'].includes(extension)){rejectActivityFile(selection,'Choose a .fit or .csv file exported from one individual activity.');return;}if(extension==='.fit'){readActivityFile(selection,true,result=>{if(!compatibleGarminFit(result))throw new Error('This .fit file is not a compatible Garmin FIT activity. In Garmin Connect, choose Export Original for one activity.');acceptActivityFile(selection,'garmin-fit',bytesToBase64(result),'Detected Garmin FIT.');});return;}readActivityFile(selection,false,result=>{const format=inspectActivityCsv(String(result));if(format==='oxiwear-hr-csv'){rejectActivityFile(selection,'OxiWear heart-rate CSV detected. Upload Garmin FIT or Polar CSV here. Add OxiWear SpO2 later under optional overlays.');return;}acceptActivityFile(selection,'polar-csv',String(result),'Detected Polar CSV.');});});"
     "readTextFile('spo2-file','spo2-telemetry','spo2-status');"
     "byId('watermark-content')||(()=>{const input=document.createElement('input');input.id='watermark-content';input.type='hidden';form.appendChild(input);})();readBinaryFile('watermark-file','watermark-content','watermark-status');"
     "function applyRequest(request){if(!request||typeof request!=='object'||Array.isArray(request))throw new Error('JSON must be an object');request={...request,futureTraceOpacityPercent:has(request,'futureTraceOpacityPercent')?request.futureTraceOpacityPercent:25};if(!request.sourceVideo&&!has(request,'transparentAlphaBits'))request.transparentAlphaBits=16;const sourceRequest=request.sourceVideo;chooseWizardOutcome(sourceRequest?'finished-video':'transparent-overlay',false);['telemetryFormat','preset','futureTraceOpacityPercent','transparentAlphaBits','outputFormat','fitMode','audioMode'].forEach(key=>{if(request[key]!==undefined&&byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())))byId(key.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())).value=request[key];});byId('source-video-file-id').value=sourceRequest?.fileId||'';if(sourceRequest){setComposeSourceMode(true);clockInspectionGeneration++;videoClockCandidates.replaceChildren();videoSourceName=null;videoTimeZone.value=sourceRequest.timeZone;videoRecordingStart.value=request.synchronizationMode==='shared-clock'?isoToZoneLocal(sourceRequest.recordingStartAt,sourceRequest.timeZone):'';videoRecordingStartAt=sourceRequest.recordingStartAt;videoClockConfirmed=true;videoClockSource=request.synchronizationMode==='manual-anchor'?'manual-anchor':'shared-clock';show(videoClockInspectionStatus,request.synchronizationMode==='manual-anchor'?'Synchronization restored from raw JSON. Re-select the Drive file to refresh its duration.':'Clock restored from raw JSON. Re-select the Drive file to refresh authoritative filename and duration.');show(videoClockStatus,request.synchronizationMode==='manual-anchor'?'Recording time derived from the activity-data match.':'Video clock confirmed.','success');}else{setComposeSourceMode(false);clockInspectionGeneration++;videoClockCandidates.replaceChildren();videoSourceName=null;videoSourceDuration=null;videoTimeZone.value='';videoRecordingStart.value='';videoRecordingStartAt=null;videoClockConfirmed=false;videoClockSource=null;show(videoClockStatus,'Not confirmed.');}updateVideoSourceSummary();updateVideoTicks();updateVideoTransport();updateOutputFraming();setDisplayTimeZone(request.displayTimeZone);[['telemetrySyncAt','telemetry-sync-at'],['cameraSyncAt','camera-sync-at'],['sectionStartAt','section-start-at'],['sectionEndAt','section-end-at']].forEach(([key,id])=>{byId(id).value=request[key]?isoToLocal(request[key]):'';});clearFileBackedValue('telemetry');byId('telemetry').value=request.telemetry||'';byId('telemetry-file').value='';show(byId('telemetry-status'),request.telemetry?'Loaded from JSON.':'');const hasSpo2=!!request.spo2;byId('spo2-enabled').checked=hasSpo2;refreshOptional('spo2-enabled','spo2-fields');clearFileBackedValue('spo2-telemetry');byId('spo2-telemetry').value=hasSpo2?(request.spo2.telemetry||''):'';show(byId('spo2-status'),hasSpo2?'Loaded from JSON.':'');const hasTimer=!!request.timer;byId('timer-enabled').checked=hasTimer;refreshOptional('timer-enabled','timer-fields');byId('timer-start-at').value=hasTimer?isoToLocal(request.timer.startAt):'';byId('timer-end-at').value=hasTimer?isoToLocal(request.timer.endAt):'';updateTimerMarkers();const hasWatermark=!!request.watermark;byId('watermark-enabled').checked=hasWatermark;refreshOptional('watermark-enabled','watermark-fields');byId('watermark-file').value='';clearFileBackedValue('watermark-content');byId('watermark-content').value=hasWatermark?(request.watermark.contentBase64||''):'';show(byId('watermark-status'),hasWatermark?'Loaded from JSON.':'');writeRequest(request);captureWizardState(request);invalidatePreview();show(jsonStatus,'JSON applied to the form.','success');show(status,'Ready to preview or create the finished video.','success');}"
     "const applyRequestFields=applyRequest;applyRequest=function(request){synchronizationModeInputs.forEach(input=>{input.checked=input.value===request.synchronizationMode;});updateSynchronizationMode();const result=applyRequestFields(request);if(request.sourceVideo){videoSourceDuration=null;if(request.synchronizationMode==='manual-anchor'){manualSyncSeconds=snapOutputFrame((Date.parse(request.cameraSyncAt)-Date.parse(request.sourceVideo.recordingStartAt))/1000);manualSyncSourceSeconds.value=String(manualSyncSeconds);manualSyncElapsed.textContent=playbackTime(manualSyncSeconds);videoClockSource='manual-anchor';deriveManualSynchronization(false);}else{videoClockSource='shared-clock';initializeOutputRange(true);}updateVideoSourceSummary();updateVideoTicks();syncRequest(false);}else refreshNoSourceTimeline();renderWizardStep(false);return result;};"
     "byId('apply-json').addEventListener('click',()=>{try{const request=JSON.parse(raw.value),errors=validateRequest(request);if(errors.length){show(jsonStatus,errors.map((error,index)=>(index+1)+'. '+error).join('\\n'),'error');return;}applyRequest(request);}catch(error){show(jsonStatus,error.message,'error');}});byId('copy-json').addEventListener('click',()=>{const text=syncRequest(false)||raw.value;navigator.clipboard?navigator.clipboard.writeText(text).then(()=>show(jsonStatus,'Generated JSON copied.','success')):show(jsonStatus,'Copy is unavailable in this browser.');});"
     (project/browser-runtime-script)
     "const previewButton=byId('preview-button'),previewSpinner=previewButton.querySelector('.button-spinner');function newPreviewGeneration(){return crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random();}function newSubmitIdempotencyKey(){return 'ui-submit-'+(crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+Math.random());}let previewGeneration=newPreviewGeneration(),previewActive=false,submitState='idle',submittedJobId=null,submitIdempotencyKey=newSubmitIdempotencyKey();function setSubmitDisabled(){submitButton.disabled=submitState!=='idle'||!synchronizationReady();submitButton.setAttribute('aria-disabled',String(submitButton.disabled));}function setPreviewActive(active){previewActive=active;previewButton.disabled=active||!synchronizationReady();previewButton.setAttribute('aria-disabled',String(previewButton.disabled));previewSpinner.hidden=!active;}function setPreviewStatus(state){setSubmitDisabled();if(submitState!=='idle')return;const messages={required:'Preview is optional.',pending:'Preview is running. Create finished video remains available.',succeeded:'Preview succeeded.',failed:'Preview failed. Create finished video remains available.',stale:'Preview no longer matches current settings. Create finished video remains available.',expired:'Preview expired. Create finished video remains available.'};show(submitStatus,messages[state]||messages.required,state==='succeeded'?'success':(['failed','stale','expired'].includes(state)?'error':''));}function localPreviewState(className,title,message){const result=byId('preview-result');result.className=className;result.setAttribute('role',className==='preview-pending'?'status':'alert');result.dataset.previewGeneration=previewGeneration;delete result.dataset.previewOperation;result.replaceChildren();const heading=document.createElement('h2'),copy=document.createElement('p');heading.textContent=title;copy.textContent=message;result.append(heading,copy);return result;}function beginPreview(){previewGeneration=newPreviewGeneration();setPreviewStatus('pending');setPreviewActive(true);localPreviewState('preview-pending','Preparing preview','Waiting for the preview service.');show(status,'Preparing preview…');}function finishPreview(){setPreviewActive(false);}function invalidatePreview(){const hadPreview=previewActive||!!byId('preview-result')?.dataset?.previewOperation;previewGeneration=newPreviewGeneration();submitState='idle';submittedJobId=null;submitIdempotencyKey=newSubmitIdempotencyKey();setPreviewStatus(hadPreview?'stale':'required');finishPreview();if(hadPreview){localPreviewState('preview-stale','Preview settings changed','Start a new preview for the current settings.');show(status,'Preview settings changed.','');}}updateSynchronizationMode();"
     "function previewTrigger(event){const trigger=event.detail?.elt||event.target;return !!trigger?.matches?.('[hx-post=\"/ui/preview\"]');}function previewTarget(event){const target=event.detail?.elt?.id==='preview-result'?event.detail.elt:event.target?.id==='preview-result'?event.target:event.detail?.target;return target?.id==='preview-result'?target:null;}function previewRequestEvent(event){const trigger=event.detail?.elt||event.target;return previewTrigger(event)||trigger?.id==='preview-result'||!!trigger?.closest?.('#preview-result')||!!previewTarget(event);}function requestPreviewGeneration(event){const trigger=event.detail?.elt||event.target;return event.detail?.requestConfig?.headers?.['X-Preview-Generation']||trigger?.dataset?.previewGeneration;}function previewEventGeneration(event){return event.detail?.xhr?.aggPreviewGeneration||event.detail?.requestConfig?.headers?.['X-Preview-Generation']||event.detail?.xhr?.getResponseHeader?.('X-Preview-Generation')||requestPreviewGeneration(event)||previewTarget(event)?.dataset?.previewGeneration||previewGeneration;}function currentPreviewEvent(event){return previewEventGeneration(event)===previewGeneration;}function transportFailure(event,kind){if(!previewRequestEvent(event)||!previewActive||!currentPreviewEvent(event))return;finishPreview();setPreviewStatus('failed');const admission=' No finished video was submitted. If a Preview started, its reservation remains counted.';if(kind==='platform'){localPreviewState('preview-error','Preview did not finish','The preview service did not finish this preview.'+admission+' Retry with the Preview button.');show(status,'Preview failed. See details below.','error');}else if(kind==='connection'){localPreviewState('preview-error','Preview connection lost','The preview connection was lost.'+admission+' Check the connection, then retry.');show(status,'Preview connection lost.','error');}else if(kind==='cancelled'){localPreviewState('preview-error','Preview request cancelled','The browser cancelled this preview request.'+admission+' Retry when ready.');show(status,'Preview request cancelled.','error');}else{localPreviewState('preview-error','Preview did not finish','The browser stopped waiting for the preview service.'+admission+' Retry with the Preview button.');show(status,'Preview timed out.','error');}}function submitRequestEvent(event){const trigger=event.detail?.elt||event.target;return submitState==='submitting'&&!previewRequestEvent(event)&&(trigger===form||trigger===submitButton||trigger?.closest?.('#render-form')===form);}function submitTransportFailure(event,kind){if(!submitRequestEvent(event))return;submitState='idle';setSubmitDisabled();const presentations={platform:['Creation failed. Review the error below, then retry Create finished video.','Finished video was not created. Retry when ready.'],connection:['Connection lost. Retry Create finished video. Repeating is safe.','Connection lost. Retry when ready.'],timeout:['Creation timed out. Retry Create finished video. Repeating is safe.','Creation timed out. Retry when ready.'],cancelled:['Creation cancelled. Retry Create finished video. Repeating is safe.','Creation cancelled. Retry when ready.']},presentation=presentations[kind]||presentations.connection;show(submitStatus,presentation[0],'error');show(status,presentation[1],'error');}function requestFailure(event,kind){transportFailure(event,kind);submitTransportFailure(event,kind);}"
     "function jobSwap(event){const submissionResponse=(event.detail?.target||event.target)?.id==='job-result';for(const node of [event.detail?.target,event.detail?.elt,event.target]){if(!node?.matches)continue;const result=node.id==='job-result'?node:node.closest?.('#job-result');if(!result)continue;const job=node.matches('.job[data-job-state]')?node:result.querySelector('.job[data-job-state]');if(job)return {id:job.id,state:job.dataset.jobState,submissionResponse};}return null;}function acceptSubmission(job){if(submitState==='submitting'){if(!job.submissionResponse)return;submittedJobId=job.id;}else if(submitState!=='submitted'||job.id!==submittedJobId)return;submitState='submitted';setSubmitDisabled();const presentations={succeeded:['Finished video created. Change any setting to start another.','Finished video is ready. Open it below.','success'],failed:['Creation failed. Review the result below, then change any setting to retry.','Finished video was not created. Review the result below.','error'],cancelled:['Creation cancelled. Change any setting to start another finished video.','Finished video creation was cancelled.','error'],'cancellation-requested':['Cancellation requested. Change any setting to start another finished video.','Finished video cancellation requested.','']},presentation=presentations[job.state]||['Creation started. Change any setting to start another finished video.','Finished video creation started. Track its progress below.','success'];show(submitStatus,presentation[0],presentation[2]);show(status,presentation[1],presentation[2]);}"
     "function hydrateInlinePlayers(root=document){for(const slot of root.querySelectorAll?.('[data-inline-player-job-id]')||[]){if(slot.dataset.playerState==='loading'||slot.dataset.playerState==='ready')continue;slot.dataset.playerState='loading';const jobId=slot.dataset.inlinePlayerJobId;fetch('/v1/jobs/'+encodeURIComponent(jobId)+'/playback-sessions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-CSRF-Token':playbackCsrf},body:'{}'}).then(async response=>{if(!response.ok)throw new Error('Playback unavailable');const session=await response.json();if(!session||typeof session.playbackUrl!=='string'||!session.playbackUrl.startsWith('/v1/jobs/'+jobId+'/playback/'))throw new Error('Playback unavailable');const video=document.createElement('video');video.controls=true;video.preload='metadata';video.src=session.playbackUrl;video.setAttribute('playsinline','');video.setAttribute('aria-label','Finished video playback');slot.replaceChildren(video);slot.dataset.playerState='ready';}).catch(()=>{slot.dataset.playerState='failed';slot.replaceChildren();const copy=document.createElement('p');copy.className='muted';copy.textContent='Inline playback is unavailable right now. Use the Google Drive link above.';slot.append(copy);});}}"
     "composeWorkflow.addEventListener('input',event=>{if(event.target.type==='file')return;if(event.target.id==='raw-json'){invalidatePreview();return;}clearFileBackedValue(event.target.id);if(!hasSourceVideo()&&['timezone','preset','section-start-at','section-end-at'].includes(event.target.id))refreshNoSourceTimeline();invalidatePreview();syncRequest(false);renderWizardStep(false);});document.body.addEventListener('htmx:configRequest',event=>{if(!form.contains(event.target))return;const preview=previewTrigger(event);if(preview&&previewActive){event.preventDefault();return;}if(!preview&&submitState!=='idle'){event.preventDefault();return;}const text=syncRequest(true);if(!text){event.preventDefault();return;}event.detail.parameters.request=text;if(preview){beginPreview();event.detail.headers['X-Preview-Generation']=previewGeneration;}else{submitState='submitting';setSubmitDisabled();event.detail.headers['Idempotency-Key']=submitIdempotencyKey;show(submitStatus,'Creating finished video…');show(status,'Creating finished video…');}});document.body.addEventListener('htmx:beforeSend',event=>{if(previewRequestEvent(event)&&event.detail?.xhr)event.detail.xhr.aggPreviewGeneration=requestPreviewGeneration(event)||previewGeneration;});document.body.addEventListener('htmx:beforeSwap',event=>{const target=previewTarget(event);if(!target)return;const generation=previewEventGeneration(event);if(generation&&generation!==previewGeneration)event.detail.shouldSwap=false;});document.body.addEventListener('htmx:afterSwap',event=>{const job=jobSwap(event);if(job)acceptSubmission(job);hydrateInlinePlayers(event.detail?.target||event.target||document);});document.body.addEventListener('htmx:afterSettle',event=>{const target=previewTarget(event);if(!target)return;if(!currentPreviewEvent(event))return;if(target.matches('.preview-pending')){setPreviewStatus('pending');setPreviewActive(true);show(status,'Preparing preview…');}else{finishPreview();if(target.matches('.preview-error')){setPreviewStatus('failed');show(status,'Preview failed. See details below.','error');}else if(target.matches('.preview-gallery,.preview-empty')){setPreviewStatus('succeeded');show(status,target.matches('.preview-gallery')?'Preview ready.':'Preview completed with no moments.','success');}else if(target.matches('.preview-stale')){setPreviewStatus('expired');show(status,'Preview expired.','error');}}});document.body.addEventListener('htmx:responseError',event=>requestFailure(event,'platform'));document.body.addEventListener('htmx:sendError',event=>requestFailure(event,'connection'));document.body.addEventListener('htmx:timeout',event=>requestFailure(event,'timeout'));document.body.addEventListener('htmx:sendAbort',event=>requestFailure(event,'cancelled'));document.body.addEventListener('htmx:xhr:abort',event=>requestFailure(event,'cancelled'));hydrateInlinePlayers(document);"
     (wizard/browser-navigation-script)
     "wizardNext.addEventListener('click',completeCurrentWizardStep);wizardBack.addEventListener('click',()=>{if(activeWizardSteps().indexOf(wizardState.currentStep)>0)history.back();});window.addEventListener('popstate',event=>{if(event.state?.contextualHelp)return;const step=event.state?.wizardStep;if(step&&step!==wizardState.currentStep&&activeWizardSteps().includes(step)){wizardState.currentStep=step;if(!wizardState.visitedStepIds.includes(step))wizardState.visitedStepIds.push(step);clearWizardError();renderWizardStep(true);}});history.replaceState({...((history.state&&typeof history.state==='object')?history.state:{}),wizardStep:'outcome'},'',location.href);renderWizardStep(false);"
     "let previewOpener=null,previewIndex=0;function previewImages(){return [...(byId('preview-result')?.querySelectorAll('.preview-open')||[])];}function showPreviewImage(index){const dialog=byId('preview-dialog'),images=previewImages(),open=images[index],image=dialog?.querySelector('img'),title=byId('preview-dialog-title'),counter=byId('preview-dialog-counter'),previous=dialog?.querySelector('.preview-previous'),next=dialog?.querySelector('.preview-next');if(!dialog||!open||!image||!title||!counter||!previous||!next)return false;previewIndex=index;image.src=open.dataset.full;image.alt=open.dataset.alt;title.textContent=open.dataset.title;counter.textContent='Image '+(index+1)+' of '+images.length;previous.disabled=index===0;next.disabled=index===images.length-1;return true;}function movePreviewImage(delta){showPreviewImage(previewIndex+delta);}document.body.addEventListener('click',event=>{const open=event.target.closest?.('.preview-open');if(open){const dialog=byId('preview-dialog'),index=previewImages().indexOf(open);if(!dialog||index<0||!showPreviewImage(index))return;previewOpener=open;dialog.showModal();dialog.focus();return;}if(event.target.closest?.('.preview-dialog-close'))byId('preview-dialog')?.close();else if(event.target.closest?.('.preview-previous'))movePreviewImage(-1);else if(event.target.closest?.('.preview-next'))movePreviewImage(1);});document.body.addEventListener('keydown',event=>{const dialog=byId('preview-dialog');if(!dialog?.open||event.target!==dialog)return;if(event.key==='ArrowLeft'){event.preventDefault();movePreviewImage(-1);}else if(event.key==='ArrowRight'){event.preventDefault();movePreviewImage(1);}});document.body.addEventListener('close',event=>{if(event.target.id==='preview-dialog'&&previewOpener){previewOpener.focus();previewOpener=null;}},true);"
     (picker-script picker-config)
     "})();</script></div></body></html>")))

(defn- public-page
  ([title body]
   (public-page title body nil))
  ([title body active-nav]
   (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        (icon-links)
        "<title>" title " · Alpha Compose</title>"
        "<meta name=\"color-scheme\" content=\"dark\">"
        "<style>"
        ":root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;line-height:1.45}"
        "*{box-sizing:border-box}body{margin:0}.shell{max-width:78rem;margin:0 auto;padding:2rem 1.25rem 4rem}"
        "footer a{color:var(--color-link)}"
        "h1,h2,p{margin-top:0}h1{font-size:clamp(2.5rem,6vw,5rem);line-height:1.02;letter-spacing:-.06em;max-width:11ch;margin-bottom:1rem}h2{font-size:1.25rem;margin-bottom:.35rem}"
        ".muted{color:var(--color-muted)}.eyebrow{color:var(--color-accent);font-size:.75rem;font-weight:800;letter-spacing:.12em;text-transform:uppercase}"
        ".hero{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(18rem,.8fr);gap:1rem;align-items:stretch;margin:2.5rem 0 1rem}.hero-copy{padding:2rem 0 1rem}"
        ".hero-card,.card{background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface);padding:1.35rem}.hero-card{background:var(--color-surface-strong);color:var(--color-text);border-color:var(--color-border-strong);display:flex;flex-direction:column;justify-content:space-between}.hero-card .muted{color:#c8ddef}"
        ".step{color:var(--color-subtle);font-weight:800;font-size:.8rem}.hero-card .step{color:var(--color-accent)}.hero-card-note{color:#c8ddef;margin:2rem 0 0}"
        ".actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap;margin-top:1.25rem}.button,.cta{border:1px solid var(--color-border);border-radius:.65rem;padding:.7rem 1rem;font-weight:800;cursor:pointer;background:var(--color-surface-soft);color:var(--color-text);text-decoration:none;display:inline-block}.button.primary,.cta{background:var(--color-accent);color:var(--color-accent-ink);box-shadow:0 .35rem .8rem #0fc3ff2e}"
        "form.card{display:grid;gap:.55rem;margin-top:1rem}input,textarea{width:100%;min-width:0;border:1px solid #6b8ba5;border-radius:.55rem;padding:.7rem;font:inherit;color:var(--color-text);background:#06182b}input[readonly]{background:#10263c;color:#c1d3e4}textarea{resize:vertical}.card:focus{outline:3px solid var(--color-warning);outline-offset:3px}"
        ".feature-grid,.value-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1rem;margin:1rem 0}.feature-grid .card,.value-grid .card{margin:0;min-width:0}"
        ".value-section{min-width:0;margin:1.75rem 0 1rem}.value-section>h2{font-size:clamp(1.5rem,3vw,2.25rem);max-width:22ch;margin:.3rem 0 1rem}.support-note{margin:1rem 0 0}.trust-card{margin-top:1rem}.trust-card p:last-child{margin-bottom:0}"
        ".faq-intro{max-width:46rem}.faq-sections{display:grid;gap:1.25rem;min-width:0;margin-top:2rem}.faq-category{min-width:0}.faq-category>h2{font-size:clamp(1.4rem,3vw,2rem);margin:0 0 .75rem}.faq-question{min-width:0;background:var(--color-surface);border:1px solid var(--color-border);border-radius:.8rem;box-shadow:var(--shadow-surface);margin:.65rem 0;scroll-margin-top:1rem;overflow-wrap:anywhere}.faq-question summary{cursor:pointer;padding:1rem 1.1rem;color:var(--color-link)}.faq-question summary h3{display:inline;font-size:1.05rem}.faq-answer{max-width:72ch;padding:0 1.1rem 1rem}.faq-answer p:last-child{margin-bottom:0}.faq-permalink{font-size:.9rem}.faq-question:target{outline:3px solid var(--color-warning);outline-offset:3px}"
        "footer{margin-top:2rem;color:var(--color-muted)}footer a{margin-right:.75rem}"
        "@media(max-width:680px){.shell{padding:1rem .8rem 3rem}.hero{grid-template-columns:1fr;margin-top:1.5rem}.hero-copy{padding:1rem 0}.feature-grid,.value-grid{grid-template-columns:1fr}.faq-question summary,.faq-answer{padding-left:.85rem;padding-right:.85rem}}"
        (theme-style)
        ".hero-copy{padding:clamp(1.25rem,3vw,2rem);background:var(--color-surface);border:1px solid var(--color-border);border-radius:1.1rem;box-shadow:var(--shadow-surface)}"
        "</style></head><body data-theme=\"telemetry\"><div class=\"shell\">"
        (product-header active-nav)
        "<main>" body "</main>"
        "<footer><small>© 2026 Alpha Compose · <a href=\"mailto:me@jamiep.org\">Contact</a></small></footer>"
        "</div></body></html>")))

(def anonymous-page
  (public-page
   "Finished activity videos"
   (str "<section class=\"hero\"><div class=\"hero-copy\">"
        "<div class=\"eyebrow\">Workout data, built into your video</div>"
        "<h1>Turn your activity into a video worth sharing.</h1>"
        "<p class=\"muted\">Choose a video and your activity data. Alpha Compose adds "
        "heart rate and other supported stats, lets you preview the result, and "
        "delivers a finished video to Google Drive. No video editing required.</p>"
        "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start\">Continue with Google</a></div>"
        "<p class=\"muted\">Continue with Google verifies your account and lets you "
        "choose the files Alpha Compose needs.</p>"
        "</div><aside class=\"hero-card\"><div><div class=\"step\">How it works</div>"
        "<h2>Your video, finished for you.</h2>"
        "<p class=\"muted\">Select a video, add your activity data, and preview how it looks. "
        "Alpha Compose combines everything and saves the finished video to Google Drive.</p>"
        "</div><p class=\"hero-card-note\">A ready-to-share video, without a separate editing step.</p>"
        "</aside></section>"
        "<section class=\"value-section\" aria-labelledby=\"activity-value\">"
        "<div class=\"step\">Why it matters</div>"
        "<h2 id=\"activity-value\">See, hear, and relive more of the moment.</h2>"
        "<div class=\"value-grid\"><article class=\"card\">"
        "<h2>See what was happening inside you</h2>"
        "<p class=\"muted\">Heart-rate data gives you a visible window into exertion "
        "and your body's response as the recorded moment unfolds.</p>"
        "</article><article class=\"card\">"
        "<div class=\"help-heading\"><h2>Relive how it felt</h2>"
        (contextual-help-link "generated-heartbeat-sound"
                              "Learn about generated heartbeat audio")
        "</div>"
        "<p class=\"muted\">A generated heartbeat paced to your recorded heart-rate data "
        "can make a remembered effort feel more immediate.</p>"
        "</article><article class=\"card\">"
        "<h2>Share and notice change</h2>"
        "<p class=\"muted\">A ready-to-share video can say more than the image alone. "
        "Over time, saved videos can help you notice changes for yourself.</p>"
        "</article></div>"
        "<p class=\"muted support-note\"><strong>Current graph support:</strong> "
        "Heart rate is the main supported graph. Compatible OxiWear data can add optional "
        "SpO2. More activity-data graphs may be supported later.</p></section>"
        "<section class=\"feature-grid\"><article class=\"card\"><div class=\"step\">01</div>"
        "<h2>Choose your video and data</h2><p class=\"muted\">Pick the video and supported activity data you want to use.</p>"
        "</article><article class=\"card\"><div class=\"step\">02</div>"
        "<h2>Make it yours</h2><p class=\"muted\">Line up the timing, choose how the stats look, and preview key moments.</p>"
        "</article><article class=\"card\"><div class=\"step\">03</div>"
        "<h2>Get the finished video</h2><p class=\"muted\">Alpha Compose combines everything and saves a ready-to-share video to Google Drive.</p>"
        "</article></section>"
        "<section class=\"card trust-card\"><div class=\"step\">Access &amp; privacy</div>"
        "<h2>Your Google Drive stays under your control.</h2>"
        "<p class=\"muted\">Alpha Compose can only use files you choose and the finished "
        "videos it creates. It cannot access the rest of your Google Drive.</p></section>")))

(def faq-page
  (public-page
   "FAQ"
   (str
    "<header class=\"faq-intro\"><div class=\"eyebrow\">Product guidance</div>"
    "<h1>Frequently asked questions</h1>"
    "<p class=\"muted\">Learn what Alpha Compose makes, which activity data it supports, "
    "how files move through the service, and where its limits are.</p></header>"
    "<div class=\"faq-sections\">"
    (apply str (map faq-category faq-categories))
    "</div>"
    "<script>(function(){"
    "function openFaqTarget(){let fragment;try{fragment=decodeURIComponent(location.hash.slice(1));}catch(_){return;}const target=document.getElementById(fragment);if(!(target instanceof HTMLDetailsElement)||!target.classList.contains('faq-question'))return;target.open=true;target.scrollIntoView({block:'start'});}"
    "openFaqTarget();window.addEventListener('hashchange',openFaqTarget);"
    "})();</script>")
   :faq))

(def drive-recovery-fragment
  (str "<section class=\"notice\" role=\"alert\"><h2>Google Drive access needs renewal</h2>"
       "<p>Your session was cleared because Alpha Compose could no longer use its restricted "
       "<code>drive.file</code> grant. No render was submitted.</p>"
       "<p><a class=\"button primary\" href=\"/v1/auth/login/start?recovery=true\">"
       "Continue with Google</a></p></section>"))

(def drive-recovery-page
  (public-page
   "Google Drive access needs renewal"
   (str "<section class=\"hero\"><div class=\"hero-copy\"><div class=\"eyebrow\">Authorization required</div>"
        "<h1>Reconnect Google to continue.</h1>"
        "<p class=\"muted\">Alpha Compose cleared your browser session because its restricted "
        "<code>drive.file</code> grant is missing, expired, or revoked. Continue explicitly to "
        "restore identity, file selection, and Drive delivery access. No render was submitted.</p>"
        "<div class=\"actions\"><a class=\"cta\" href=\"/v1/auth/login/start?recovery=true\">"
        "Continue with Google</a></div></div></section>")))

(defn early-access-page
  [{:keys [email proof instagram message feedback request-id]}]
  (public-page
   "Early access"
   (str
    "<section class=\"hero\"><div class=\"hero-copy\">"
    "<div class=\"eyebrow\">Verified Google account</div>"
    "<h1>Alpha Compose is in early access</h1>"
    "<p class=\"muted\">Access is currently limited to approved testers. "
    "If you would like to test Alpha Compose, leave your details below.</p>"
    "<p><strong>No session, Drive grant, membership binding, or render was created.</strong></p>"
    (when feedback
      (str "<div class=\"card\" role=\"" (if (= :success (:kind feedback))
                                           "status" "alert")
           "\" tabindex=\"-1\" id=\"early-access-feedback\"><h2>"
           (escape-html (:title feedback)) "</h2><p>"
           (escape-html (:message feedback)) "</p></div>"))
    (when (and email proof)
      (str
       "<form class=\"card\" method=\"post\" action=\"/v1/early-access/request\" "
       "aria-describedby=\"early-access-privacy form-status\">"
       "<input type=\"hidden\" name=\"proof\" value=\"" (escape-html proof) "\">"
       "<label for=\"early-access-email\"><strong>Email</strong></label>"
       "<input id=\"early-access-email\" type=\"email\" name=\"email\" value=\""
       (escape-html email) "\" readonly required>"
       "<p class=\"muted\">This is the Google email address just verified.</p>"
       "<label for=\"early-access-instagram\"><strong>Instagram handle (optional)</strong></label>"
       "<input id=\"early-access-instagram\" name=\"instagram\" maxlength=\"64\" value=\""
       (escape-html instagram) "\">"
       "<label for=\"early-access-message\"><strong>Message (optional)</strong></label>"
       "<textarea id=\"early-access-message\" name=\"message\" maxlength=\"2000\" rows=\"6\">"
       (escape-html message) "</textarea>"
       "<p id=\"early-access-privacy\" class=\"muted\">Your details are used only to email "
       "the Alpha Compose operator about this request. Alpha Compose does not retain them.</p>"
       "<p id=\"form-status\" aria-live=\"polite\"></p>"
       "<button class=\"button primary\" type=\"submit\">Ask to test Alpha Compose</button>"
       "</form>"))
    "<div class=\"actions\"><a href=\"mailto:me@jamiep.org\">Email me@jamiep.org directly</a>"
    "<a href=\"/v1/auth/login/start\">Try another Google account</a></div>"
    (when request-id
      (str "<p class=\"muted\"><small>Request ID: "
           (escape-html request-id) "</small></p>"))
    "</div></section>"
    (when feedback
      "<script>document.getElementById('early-access-feedback')?.focus();</script>"))))

(def privacy-page
  (public-page
   "Privacy policy"
   (str "<h1>Privacy policy</h1><p><strong>Effective 22 July 2026.</strong></p>"
        "<p>Questions or deletion "
        "requests may be sent to <a href=\"mailto:me@jamiep.org\">me@jamiep.org</a>.</p>"
        "<h2>Information used</h2><p>We use your Google account identifier and "
        "email address to authenticate you and enforce the administrator-managed access list. "
        "As part of the same authorization, Alpha Compose receives only the "
        "<code>drive.file</code> permission, allowing access to files you select or "
        "that Alpha Compose creates. We process activity data and optional watermark "
        "content solely to create the requested output. For an early-access request, "
        "we collect the verified Google email address, an optional Instagram handle, "
        "and an optional message solely so the operator can respond about testing.</p>"
        "<h2>Project JSON and browser state</h2><p>Alpha Compose does not automatically "
        "persist Project JSON. Project JSON exists only when you explicitly download, "
        "copy, upload, or paste it yourself. It may include private activity data, a "
        "selected Drive reference, and bounded embedded assets so you can resume the "
        "same browser workflow later.</p><p>Credentials, CSRF values, signed URLs, "
        "recording-clock candidates, preview images, playback state, and job results "
        "are excluded from Project JSON. Normal browsing, previewing, clock inspection, "
        "and completed-output playback do not create an automatic saved project.</p>"
        "<h2>Storage and retention</h2><p>Encrypted Google Drive authorization, "
        "membership, and job records are stored in Google Cloud. Temporary request "
        "and output objects are deleted after 24 hours; job metadata is scheduled for "
        "deletion after 90 days. Completed outputs are delivered to your Google Drive "
        "and remain there until you delete them. Alpha Compose does not retain early-access requests "
        "in Firestore, application logs, analytics, or another application data store. "
        "Those details exist only during bounded request processing and in the configured "
        "email processor and recipient mailbox.</p>"
        "<h2>Sharing and security</h2><p>We use Google Cloud and Google Drive to "
        "operate the service, and Resend processes the plain-text early-access notification. "
        "We do not sell personal information or use activity data for "
        "advertising. Access is limited to approved accounts; credentials are encrypted, "
        "and application logs exclude activity-data values, filenames, email addresses, and tokens. "
        "Use and transfer of information received from Google APIs follows the "
        "<a href=\"https://developers.google.com/terms/api-services-user-data-policy\">Google API Services User Data Policy</a>, "
        "including its Limited Use requirements.</p>"
        "<h2>Your choices</h2><p>You may disconnect Alpha Compose in your Google Account, "
        "delete delivered files from Drive, or email me@jamiep.org with a contact or deletion request "
        "covering service records or an early-access notification. "
        "Revoking Drive access may prevent pending renders from completing.</p>")
   :privacy))

(def terms-page
  (public-page
   "Terms of service"
   (str "<h1>Terms of service</h1><p><strong>Effective 17 July 2026.</strong></p>"
        "<p>By using Alpha Compose you agree to these terms. If you do not agree, do not use the service.</p>"
        "<h2>Permitted use</h2><p>You may use Alpha Compose only with content and activity data "
        "you are entitled to process. Do not misuse the service, attempt to bypass its access "
        "controls or limits, or use it unlawfully.</p>"
        "<h2>Your content</h2><p>You retain ownership of your inputs and outputs. You grant "
        "the operator only the limited permission needed to process them and deliver your output.</p>"
        "<h2>Availability</h2><p>The service is provided as available, without a guarantee of "
        "uninterrupted operation or fitness for a particular purpose. Verify every output before "
        "publication or reliance on it. Alpha Compose outputs are not medical advice.</p>"
        "<h2>Liability and termination</h2><p>To the extent permitted by law, the operator is "
        "not liable for indirect or consequential loss. Access may be suspended for misuse, "
        "security, maintenance, or cost control. You may stop using the service at any time.</p>"
        "<h2>Contact</h2><p>Questions may be sent to "
        "<a href=\"mailto:me@jamiep.org\">me@jamiep.org</a>.</p>")
   :terms))
