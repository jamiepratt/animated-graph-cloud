(ns agg.release
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private semantic-version-pattern
  #"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)")

(def ^:private exact-build-pattern #"[0-9a-f]{40}")

(defn parse-version-resource [text]
  (try
    (let [value (edn/read-string text)
          version (:version value)]
      (when-not (and (= #{:version} (set (keys value)))
                     (string? version)
                     (re-matches semantic-version-pattern version))
        (throw (IllegalArgumentException.
                "Release version resource must contain one semantic version.")))
      version)
    (catch IllegalArgumentException error
      (throw error))
    (catch Throwable error
      (throw (IllegalArgumentException.
              "Release version resource is invalid."
              error)))))

(defn parse-build-identity [build-commit production?]
  (let [build-commit (or build-commit "dev")]
    (cond
      (re-matches exact-build-pattern build-commit)
      {:full build-commit :short (subs build-commit 0 7)}

      (and (not production?) (= "dev" build-commit))
      {:full "dev" :short "dev"}

      :else
      (throw (IllegalArgumentException.
              "Production build identity must be an exact lowercase Git commit.")))))

(defn release-identity [{:keys [version build-commit production?]}]
  (when-not (and (string? version)
                 (re-matches semantic-version-pattern version))
    (throw (IllegalArgumentException. "Release version is invalid.")))
  (let [{:keys [full short]} (parse-build-identity build-commit production?)]
    {:version version
     :build-commit full
     :short-build short
     :label (str "v" version " · build " short)}))

(defn newest-released-version [markdown]
  (some->> (re-find
            #"(?m)^## \[?((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))\]? - \d{4}-\d{2}-\d{2}\s*$"
            markdown)
           second))

(defn released-markdown [markdown]
  (->> (str/split-lines markdown)
       (reduce
        (fn [{:keys [skipping?] :as state} line]
          (cond
            (re-matches #"^## \[?Unreleased\]?\s*$" line)
            (assoc state :skipping? true)

            (and skipping? (str/starts-with? line "## "))
            (-> state
                (assoc :skipping? false)
                (update :lines conj line))

            skipping?
            state

            :else
            (update state :lines conj line)))
        {:skipping? false :lines []})
       :lines
       (str/join "\n")))

(defn- escape-html [text]
  (-> text
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

(defn- inline-html [text]
  (-> (escape-html text)
      (str/replace #"`([^`]+)`" "<code>$1</code>")
      (str/replace #"\*\*([^*]+)\*\*" "<strong>$1</strong>")))

(defn markdown->safe-html [markdown]
  (letfn [(flush-paragraph [out paragraph]
            (if (seq paragraph)
              (conj out (str "<p>"
                             (inline-html (str/join " " paragraph))
                             "</p>"))
              out))
          (flush-list [out items]
            (if (seq items)
              (conj out
                    (str "<ul>"
                         (apply str
                                (map #(str "<li>" (inline-html %) "</li>")
                                     items))
                         "</ul>"))
              out))]
    (let [{:keys [out paragraph items]}
          (reduce
           (fn [{:keys [out paragraph items]} line]
             (cond
               (str/blank? line)
               {:out (-> out
                         (flush-paragraph paragraph)
                         (flush-list items))
                :paragraph []
                :items []}

               (re-matches #"^(#{1,3}) (.+)$" line)
               (let [[_ marks title] (re-matches #"^(#{1,3}) (.+)$" line)
                     level (count marks)]
                 {:out (-> out
                           (flush-paragraph paragraph)
                           (flush-list items)
                           (conj (str "<h" level ">"
                                      (inline-html title)
                                      "</h" level ">")))
                  :paragraph []
                  :items []})

               (str/starts-with? line "- ")
               {:out (flush-paragraph out paragraph)
                :paragraph []
                :items (conj items (subs line 2))}

               :else
               {:out (flush-list out items)
                :paragraph (conj paragraph line)
                :items []}))
           {:out [] :paragraph [] :items []}
           (str/split-lines markdown))]
      (-> out
          (flush-paragraph paragraph)
          (flush-list items)
          (->> (apply str))))))

(defn public-changelog-html [markdown]
  (markdown->safe-html (released-markdown markdown)))
