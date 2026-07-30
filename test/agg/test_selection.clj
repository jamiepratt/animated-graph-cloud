(ns agg.test-selection
  (:require [agg.test-catalogue :as catalogue]
            [agg.test-runner :as runner]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- ns-form [source]
  (when (seq source)
    (binding [*read-eval* false]
      (let [form (read (java.io.PushbackReader.
                        (java.io.StringReader. source)))]
        (when (= 'ns (first form))
          form)))))

(defn- dependency-symbol [spec]
  (cond
    (symbol? spec) spec
    (vector? spec) (first spec)
    (seq? spec) (first spec)))

(defn- source-info [source]
  (when-let [form (ns-form source)]
    {:namespace (second form)
     :dependencies
     (->> (drop 2 form)
          (filter seq?)
          (filter #(#{:require :require-macros} (first %)))
          (mapcat rest)
          (keep dependency-symbol)
          set)}))

(defn- dependency-graph [sources]
  (->> sources
       vals
       (keep source-info)
       (map (juxt :namespace :dependencies))
       (into {})))

(defn- dependency-distance [graph start target]
  (loop [frontier #{start}
         visited #{}
         distance 0]
    (cond
      (contains? frontier target) distance
      (empty? frontier) nil
      :else
      (let [next-frontier (->> frontier
                               (mapcat #(get graph % #{}))
                               set
                               (remove visited)
                               set)]
        (recur next-frontier
               (set/union visited frontier)
               (inc distance))))))

(defn- impact-rule [path]
  (cond
    (= path "docs/openapi.yaml")
    {:tests #{'agg.contracts-test
              'agg.derivative-contract-test
              'agg.release-config-test}
     :reason "reads docs/openapi.yaml"}

    (str/starts-with? path ".github/workflows/")
    {:tests #{'agg.deploy-workflow-test
              'agg.proto-release-test
              'agg.release-config-test}
     :reason (str "workflow contract for " path)}

    (str/starts-with? path "infra/")
    {:tests #{'agg.deploy-workflow-test 'agg.release-config-test}
     :reason (str "infrastructure contract for " path)}

    (= path "Dockerfile")
    {:tests #{'agg.deploy-workflow-test 'agg.release-config-test}
     :reason "container contract for Dockerfile"}

    (str/starts-with? path "script/")
    {:tests #{'agg.deploy-workflow-test 'agg.release-config-test}
     :reason (str "script contract for " path)}

    (or (= path "CHANGELOG.md")
        (= path "resources/agg/main-version.edn"))
    {:tests #{'agg.release-test 'agg.release-config-test}
     :reason (str "release identity contract for " path)}

    (or (= path "README.md")
        (= path "docs/production-runbook.md")
        (str/starts-with? path "docs/adr/"))
    {:tests #{'agg.deploy-workflow-test 'agg.release-config-test}
     :reason (str "documentation contract for " path)}

    (or (= path "deps.edn")
        (= path "build.clj")
        (str/starts-with? path "test/agg/test_catalogue.")
        (str/starts-with? path "test/agg/test_selection.")
        (str/ends-with? path "_test_runner.clj")
        (= path "test/agg/test_runner.clj")
        (and (str/starts-with? path "test/")
             (not (str/ends-with? path "_test.clj"))))
    {:fallback (str "shared test or build configuration changed: " path)}

    (or (str/starts-with? path "resources/")
        (str/starts-with? path "docs/")
        (str/starts-with? path "src/")
        (str/starts-with? path "test/"))
    {:fallback (str "unknown production or test impact: " path)}

    :else
    {:fallback (str "unclassified repository path changed: " path)}))

(defn- changed-source-infos [changes sources]
  (mapcat
   (fn [{:keys [path source old-source old-path]}]
     (keep identity
           [(when (str/ends-with? (or old-path "") ".clj")
              (source-info old-source))
            (when (str/ends-with? path ".clj")
              (source-info (or source (get sources path))))]))
   changes))

(defn select-affected [{:keys [changes sources test-namespaces]}]
  (let [test-namespaces (vec test-namespaces)
        known-tests (set test-namespaces)
        graph (dependency-graph sources)
        clj-changes (filter #(or (str/ends-with? (:path %) ".clj")
                                 (str/ends-with? (or (:old-path %) "") ".clj"))
                            changes)
        rule-changes (remove (set clj-changes) changes)
        changed-infos (changed-source-infos clj-changes sources)
        unparsed-clj (and (seq clj-changes) (empty? changed-infos))
        fallbacks (cond-> (keep (comp :fallback impact-rule :path)
                                rule-changes)
                    unparsed-clj
                    (conj "changed Clojure source has no readable namespace"))
        rule-selections
        (reduce
         (fn [selected {:keys [path]}]
           (let [{:keys [tests reason]} (impact-rule path)]
             (reduce #(update %1 %2 (fnil conj #{}) reason)
                     selected
                     (set/intersection known-tests tests))))
         {}
         rule-changes)
        namespace-selections
        (reduce
         (fn [selected {:keys [namespace]}]
           (reduce
            (fn [result test-namespace]
              (cond
                (= namespace test-namespace)
                (update result test-namespace
                        (fnil conj #{}) "changed test namespace")

                :else
                (if-let [distance
                         (dependency-distance graph test-namespace namespace)]
                  (update result test-namespace
                          (fnil conj #{})
                          (str (if (= 1 distance) "direct" "transitive")
                               " dependency on " namespace))
                  result)))
            selected
            test-namespaces))
         {}
         changed-infos)
        reasons (merge-with set/union rule-selections namespace-selections)
        unexplained? (and (seq changes) (empty? reasons))
        fallback-reasons (cond-> (vec fallbacks)
                           (empty? changes)
                           (conj "no changed files were reported")
                           unexplained?
                           (conj "changed files selected no known tests"))]
    (if (seq fallback-reasons)
      {:tests test-namespaces
       :reasons (into {}
                      (map (fn [test-namespace]
                             [test-namespace (set fallback-reasons)]))
                      test-namespaces)
       :fallback? true}
      {:tests (filterv (set (keys reasons)) test-namespaces)
       :reasons reasons
       :fallback? false})))

(defn- git-result! [root & args]
  (let [{:keys [exit out err]} (apply shell/sh "git" "-C" root args)]
    (when-not (zero? exit)
      (throw (ex-info (str/trim err) {:args args :exit exit})))
    out))

(defn- parse-name-status [output]
  (if (str/blank? output)
    []
    (loop [tokens (seq (str/split output #"\u0000"))
           changes []]
      (if-not (seq tokens)
        changes
        (let [[status-token path & more] tokens
              status (first status-token)]
          (if (= \R status)
            (let [[new-path & remaining] more]
              (recur remaining
                     (conj changes {:status :renamed
                                    :old-path path
                                    :path new-path})))
            (recur more
                   (conj changes
                         {:status ({\A :added \D :deleted \M :modified
                                    \C :copied \T :modified \U :modified}
                                   status
                                   :modified)
                          :path path}))))))))

(defn git-changes [{:keys [root base head] :or {root "."}}]
  (let [tracked-output
        (if base
          (git-result! root "diff" "--name-status" "-z" "--find-renames"
                       base (or head "HEAD"))
          (git-result! root "diff" "--name-status" "-z" "--find-renames"
                       "HEAD"))
        tracked (parse-name-status tracked-output)
        untracked
        (if base
          []
          (->> (git-result! root "ls-files" "--others"
                            "--exclude-standard" "-z")
               (#(str/split % #"\u0000"))
               (remove str/blank?)
               (mapv (fn [path] {:status :added :path path}))))]
    (->> (concat tracked untracked)
         (sort-by (juxt :path :old-path))
         vec)))

(defn- repository-sources [root]
  (->> ["src" "test"]
       (map #(io/file root %))
       (filter #(.exists %))
       (mapcat file-seq)
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))
       (map (fn [file]
              (let [root-path (.toPath (io/file root))
                    path (str (.relativize root-path (.toPath file)))]
                [path (slurp file)])))
       (into {})))

(defn- historical-source [root ref path]
  (when (and ref path)
    (let [{:keys [exit out]}
          (shell/sh "git" "-C" root "show" (str ref ":" path))]
      (when (zero? exit) out))))

(defn repository-selection [{:keys [root base head]
                             :or {root "."}}]
  (let [changes (git-changes {:root root :base base :head head})
        historical-ref (or base "HEAD")
        sources (repository-sources root)
        enriched
        (mapv
         (fn [{:keys [status path old-path] :as change}]
           (cond-> change
             (and (= :deleted status) (str/ends-with? path ".clj"))
             (assoc :source (historical-source root historical-ref path))

             (and (= :renamed status) (str/ends-with? old-path ".clj"))
             (assoc :old-source
                    (historical-source root historical-ref old-path))))
         changes)]
    (assoc (select-affected
            {:changes enriched
             :sources sources
             :test-namespaces (catalogue/all-namespaces)})
           :changes changes)))

(defn- parse-cli [args]
  (loop [remaining args
         options {:root "."}]
    (if (empty? remaining)
      options
      (let [[flag value & more] remaining
            key ({"--root" :root "--base" :base "--head" :head} flag)]
        (when-not (and key value)
          (throw (ex-info (str "Usage: clojure -M:test-changed "
                               "[--base REF --head REF] [--root PATH]")
                          {:args args})))
        (recur more (assoc options key value))))))

(defn- print-selection [{:keys [changes tests reasons fallback?]}]
  (println "Changed files:")
  (doseq [{:keys [status path old-path]} changes]
    (println "-" (name status)
             (if old-path (str old-path " -> " path) path)))
  (println "Selected tests:" (if fallback? "(complete-suite fallback)" ""))
  (doseq [test-namespace tests]
    (println "-" test-namespace "-" (str/join "; " (sort (reasons test-namespace))))))

(defn -main [& args]
  (let [selection (repository-selection (parse-cli args))
        _ (print-selection selection)
        {:keys [error fail]} (runner/run-selected (:tests selection))]
    (System/exit (if (pos? (+ error fail)) 1 0))))
