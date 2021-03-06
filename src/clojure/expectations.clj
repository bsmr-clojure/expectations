(ns expectations
  (:use clojure.set)
  (:require expectations.clojure.walk clojure.template clojure.string clojure.pprint clojure.data))

(def nothing "no arg given")

(defn a-fn1 [& _])
(defn a-fn2 [& _])
(defn a-fn3 [& _])

(defn no-op [& _])

(defn in [n] {::in n ::in-flag true})

;;; GLOBALS
(def run-tests-on-shutdown (atom true))
(def warn-on-iref-updates-boolean (atom false))
(def lib-namespaces (set (all-ns)))

(def ^{:dynamic true} *test-name* nil)
(def ^{:dynamic true} *test-meta* {})
(def ^{:dynamic true} *test-var* nil)
(def ^{:dynamic true} *prune-stacktrace* true)

(def ^{:dynamic true} *report-counters* nil) ; bound to a ref of a map in test-ns

(def ^{:dynamic true} *initial-report-counters* ; used to initialize *report-counters*
  {:test 0, :pass 0, :fail 0, :error 0 :run-time 0})

(def ^{:dynamic true} reminder nil)

;;; UTILITIES FOR REPORTING FUNCTIONS
(defn getenv [var]
  (System/getenv var))

(defn on-windows? []
  (re-find #"[Ww]in" (System/getProperty "os.name")))

(defn show-raw-choice []
  (if-let [choice (getenv "EXPECTATIONS_SHOW_RAW")]
    (= "TRUE" (clojure.string/upper-case choice))
    true))

(defn colorize-choice []
  (clojure.string/upper-case (or (getenv "EXPECTATIONS_COLORIZE")
                                 (str (not (on-windows?))))))

(def ansi-colors {:reset "[0m"
                  :red     "[31m"
                  :blue    "[34m"
                  :yellow    "[33m"
                  :cyan    "[36m"
                  :green   "[32m"
                  :magenta "[35m"})

(defn ansi [code]
  (str \u001b (get ansi-colors code (:reset ansi-colors))))

(defn color [code & s]
  (str (ansi code) (apply str s) (ansi :reset)))

(defn colorize-filename [s]
  (condp = (colorize-choice)
    "TRUE" (color :magenta s)
    s))

(defn colorize-raw [s]
  (condp = (colorize-choice)
    "TRUE" (color :cyan s)
    s))

(defn colorize-results [pred s]
  (condp = (colorize-choice)
    "TRUE" (if (pred)
             (color :green s)
             (color :red s))
    s))

(defn colorize-warn [s]
  (condp = (colorize-choice)
    "TRUE" (color :yellow s)
    s))

(defn string-join [s coll]
  (clojure.string/join s (remove nil? coll)))

(defn stack->file&line [ex index]
  (let [s (nth (.getStackTrace ex) index)]
    (str (.getFileName s) ":" (.getLineNumber s))))

(defn inc-report-counter [name]
  (when *report-counters*
    (dosync (commute *report-counters* assoc name
                     (inc (or (*report-counters* name) 0))))))

;;; TEST RESULT REPORTING
(defn test-name [{:keys [line ns]}]
  (str ns ":" line))

(defn test-file [{:keys [file line]}]
  (colorize-filename (str (last (re-seq #"[A-Za-z_\.]+" file)) ":" line)))

(defn raw-str [[e a]]
  (with-out-str (clojure.pprint/pprint `(~'expect ~e ~a))))

(defn pp-str [e]
  (clojure.string/trim (with-out-str (clojure.pprint/pprint e))))

(defn ^{:dynamic true} fail [test-name test-meta msg]
  (println (str "\nfailure in (" (test-file test-meta) ") : " (:ns test-meta))) (println msg))

(defn ^{:dynamic true} summary [msg] (println msg))
(defn ^{:dynamic true} started [test-name test-meta])
(defn ^{:dynamic true} finished [test-name test-meta])
(defn ^{:dynamic true} ns-finished [a-ns])
(defn ^{:dynamic true} expectation-finished [a-var])

(defn ^{:dynamic true} ignored-fns [{:keys [className fileName]}]
  (when *prune-stacktrace*
    (or (= fileName "expectations.clj")
        (= fileName "expectations_options.clj")
        (= fileName "NO_SOURCE_FILE")
        (= fileName "interruptible_eval.clj")
        (re-seq #"clojure\.lang" className)
        (re-seq #"clojure\.core" className)
        (re-seq #"clojure\.main" className)
        (re-seq #"java\.lang" className)
        (re-seq #"java\.util\.concurrent\.ThreadPoolExecutor\$Worker" className))))

(defn pruned-stack-trace [t]
  (string-join "\n"
               (distinct (map (fn [{:keys [className methodName fileName lineNumber] :as m}]
                                (if (= methodName "invoke")
                                  (str "           on (" fileName ":" lineNumber ")")
                                  (str "           " className "$" methodName " (" fileName ":" lineNumber ")")))
                              (remove ignored-fns (map bean (.getStackTrace t)))))))

(defn ->failure-message [{:keys [raw ref-data result expected-message actual-message message list show-raw]}]
  (string-join "\n"
               [(when reminder
                  (colorize-warn (str "     ***** "
                                      (clojure.string/upper-case reminder)
                                      " *****")))
                (when raw (when (or show-raw (show-raw-choice)) (colorize-raw (raw-str raw))))
                (when-let [[n1 v1 & _] ref-data]
                  (format "             locals %s: %s" n1 (pr-str v1)))
                (when-let [[_ _ & the-rest] ref-data]
                  (when the-rest
                    (->> the-rest
                         (partition 2)
                         (map #(format "                    %s: %s" (first %) (pr-str (second %))))
                         (string-join "\n"))))
                (when result (str "           " (string-join " " result)))
                (when (and result (or expected-message actual-message message)) "")
                (when expected-message (str "           " expected-message))
                (when actual-message (str "           " actual-message))
                (when message (str "           " message))
                (when list
                  (str "\n" (string-join "\n\n"
                                         (map ->failure-message list))))]))

(defmulti report :type)

(defmethod report :pass [m]
  (alter-meta! *test-var* assoc ::run true :status [:success "" (:line *test-meta*)])
  (inc-report-counter :pass))

(defmethod report :fail [m]
  (inc-report-counter :fail)
  (let [current-test *test-var*
        message (->failure-message m)]
    (alter-meta! current-test assoc ::run true :status [:fail message (:line *test-meta*)])
    (fail *test-name* *test-meta* message)))

(defmethod report :error [{:keys [result raw] :as m}]
  (inc-report-counter :error)
  (let [result (first result)
        current-test *test-var*
        message (string-join "\n"
                             [(when reminder (colorize-warn (str "     ***** " (clojure.string/upper-case reminder) " *****")))
                              (when raw
                                (when (show-raw-choice) (colorize-raw (raw-str raw))))
                              (when-let [msg (:expected-message m)] (str "  exp-msg: " msg))
                              (when-let [msg (:actual-message m)] (str "  act-msg: " msg))
                              (str "    threw: " (class result) " - " (.getMessage result))
                              (pruned-stack-trace result)])]
    (alter-meta! current-test
                 assoc ::run true :status [:error message (:line *test-meta*)])
    (fail *test-name* *test-meta* message)))

(defmethod report :summary [{:keys [test pass fail error run-time ignored-expectations]}]
  (summary (str "\nRan " test " tests containing "
                (+ pass fail error) " assertions in "
                run-time " msecs\n"
                (when (> ignored-expectations 0) (colorize-warn (str "IGNORED " ignored-expectations " EXPECTATIONS\n")))
                (colorize-results (partial = 0 fail error) (str fail " failures, " error " errors")) ".")))

;; TEST RUNNING

(defn disable-run-on-shutdown [] (reset! run-tests-on-shutdown false))
(defn warn-on-iref-updates [] (reset! warn-on-iref-updates-boolean true))

(defn find-every-iref []
  (->> (all-ns)
       (remove #(re-seq #"(clojure\.|expectations)" (str (.name %))))
       (mapcat (comp vals ns-interns))
       (filter bound?)
       (keep #(when-let [val (var-get %)] [% val]))
       (filter (comp #{clojure.lang.Agent clojure.lang.Atom clojure.lang.Ref} class second))))

(defn add-watch-every-iref-for-updates []
  (doseq [[var iref] (find-every-iref)]
    (add-watch iref ::expectations-watching-state-modifications
               (fn [_ reference old-state new-state]
                 (println (colorize-warn
                           (clojure.string/join " "
                                                ["WARNING:"
                                                 (or *test-name* "test name unset")
                                                 "modified" var
                                                 "from" (pr-str old-state)
                                                 "to" (pr-str new-state)])))
                 (when-not *test-name*
                   (.printStackTrace (RuntimeException. "stacktrace for var modification") System/out))))))

(defn remove-watch-every-iref-for-updates []
  (doseq [[var iref] (find-every-iref)]
    (remove-watch iref ::expectations-watching-state-modifications)))

(defn test-var [v]
  (when-let [t (var-get v)]
    (let [tn (test-name (meta v))
          tm (meta v)]
      (started tn tm)
      (inc-report-counter :test)
      (binding [*test-name* tn
                *test-meta* tm
                *test-var*  v]
        (try
          (t)
          (catch Throwable e
            (println "\nunexpected error in" tn)
            (.printStackTrace e))))
      (finished tn tm))))

(defn find-expectations-vars [option-type]
  (->>
   (all-ns)
   (mapcat (comp vals ns-interns))
   (filter (comp #{option-type} :expectations-options meta))))

(defn execute-vars [vars]
  (doseq [var vars]
    (when (bound? var)
      (when-let [vv (var-get var)]
        (vv)))))

(defn create-context [in-context-vars work]
  (case (count in-context-vars)
    0 (work)
    1 ((var-get (first in-context-vars)) work)
    (do
      (println "expectations only supports 0 or 1 :in-context fns. Ignoring:" in-context-vars)
      (work))))

(defn test-vars [vars ignored-expectations]
  (remove-ns 'expectations-options)
  (try
    (require 'expectations-options :reload)
    (catch java.io.FileNotFoundException e))

  (-> (find-expectations-vars :before-run) (execute-vars))
  (when @warn-on-iref-updates-boolean
    (add-watch-every-iref-for-updates))
  (binding [*report-counters* (ref *initial-report-counters*)]
    (let [ns->vars (group-by (comp :ns meta) vars)
          start (System/nanoTime)
          in-context-vars (vec (find-expectations-vars :in-context))]
      (doseq [[a-ns the-vars] ns->vars]
        (doseq [v the-vars]
          (create-context in-context-vars #(test-var v))
          (expectation-finished v))
        (ns-finished (ns-name a-ns)))
      (let [result (assoc @*report-counters*
                     :run-time (int (/ (- (System/nanoTime) start) 1000000))
                     :ignored-expectations ignored-expectations)]
        (when @warn-on-iref-updates-boolean
          (remove-watch-every-iref-for-updates))
        (-> (find-expectations-vars :after-run) (execute-vars))
        result))))

(defn run-tests-in-vars [vars]
  (doto (assoc (test-vars vars 0) :type :summary)
    (report)))

(defn unrun-expectation [{:keys [expectation] run? ::run}]
  (and expectation (not run?)))

(defn ->expectation [ns]
  (->> ns
       ns-interns
       vals
       (sort-by str)
       (filter (comp unrun-expectation meta))))

(defn ->focused-expectations [expectations]
  (->> expectations (filter (comp :focused meta)) seq))

(defn run-tests [namespaces]
  (let [expectations (mapcat ->expectation namespaces)]
    (if-let [focused (->focused-expectations expectations)]
      (doto (assoc (test-vars focused (- (count expectations) (count focused))) :type :summary)
        (report))
      (doto (assoc (test-vars expectations 0) :type :summary)
        (report)))))

(defn run-all-tests
  ([] (run-tests (all-ns)))
  ([re] (run-tests (filter #(re-matches re (name (ns-name %))) (all-ns)))))

(defprotocol CustomPred
  (expect-fn [e a])
  (expected-message [e a str-e str-a])
  (actual-message [e a str-e str-a])
  (message [e a str-e str-a]))

(defmulti compare-expr (fn [e a _ _]
                         (cond
                          (and (map? a) (not (sorted? a)) (contains? a ::from-each-flag)) ::from-each
                          (and (map? a) (not (sorted? a)) (contains? a ::in-flag)) ::in
                          (and (map? e) (not (sorted? e)) (contains? e ::more)) ::more
                          (and (isa? e Throwable) (not= e a)) ::expect-exception
                          (instance? Throwable e) ::expected-exception
                          (instance? Throwable a) ::actual-exception
                          (and (fn? e) (not= e a)) ::fn
                          (instance? expectations.CustomPred e) :custom-pred
                          :default [(class e) (class a)])))

(defmethod compare-expr :default [e a str-e str-a]
  (if (= e a)
    {:type :pass}
    {:type :fail :raw [str-e str-a]
     :result ["expected:" (pr-str e)
              "\n                was:" (pr-str a)]}))

(defmethod compare-expr :custom-pred [e a str-e str-a]
  (if (expect-fn e a)
    {:type :pass}
    {:type :fail
     :raw [str-e str-a]
     :expected-message (expected-message e a str-e str-a)
     :actual-message (actual-message e a str-e str-a)
     :message (message e a str-e str-a)}))

(defmethod compare-expr ::fn [e a str-e str-a]
  (try
    (if (e a)
      {:type :pass}
      {:type :fail :raw [str-e str-a] :result [(pr-str a) "is not" str-e]})
    (catch Exception ex
      {:type :fail :raw [str-e str-a]
       :expected-message (str "also attempted: (" str-e " " str-a ")")
       :actual-message (str   "       and got: " (.getMessage ex))
       :result ["expected:" str-e
                "\n                was:" (pr-str a)]})))

(defn find-failures [the-seq]
  (seq (doall (remove (comp #{:pass} :type) the-seq))))

(defn find-successes [the-seq]
  (first (filter (comp #{:pass} :type) the-seq)))

(defmethod compare-expr ::from-each [e {a ::from-each str-i-a ::from-each-body} str-e str-a]
  (if-let [failures (find-failures (for [{ts ::the-result rd ::ref-data} a]
                                     (assoc (compare-expr e ts str-e str-i-a)
                                       :ref-data rd)))]
    {:type :fail
     :raw [str-e str-a]
     :message (format "the list: %s" (pr-str (map (fn [x] (if-let [y (::in x)] y x))
                                                  (map ::the-result a))))
     :list (mapv #(assoc % :show-raw true) failures)}
    {:type :pass}))

(defmethod compare-expr ::more [{es ::more} a str-e str-a]
  (if-let [failures (find-failures (for [{:keys [e str-e a-fn gen-str-a]} es]
                                     (compare-expr
                                      e
                                      (try (a-fn a) (catch Throwable t t))
                                      str-e (gen-str-a str-a))))]
    {:type :fail
     :raw [str-e str-a]
     :message (format "actual val: %s" (pr-str a))
     :list (mapv #(assoc % :show-raw true) failures)}
    {:type :pass}))

(defmethod compare-expr ::in [e a str-e str-a]
  (cond
   (or (instance? java.util.List (::in a)) (instance? java.util.Set (::in a)))
   (if (find-successes (for [a (::in a)]
                         (compare-expr e a str-e str-a)))
     {:type :pass}
     {:type :fail
      :raw [str-e str-a]
      :list (map #(assoc % :show-raw true) (find-failures
                                         (for [a (::in a)]
                                           (compare-expr e a str-e a))))
      :result [(if (::more e) str-e (format "val %s" (pr-str e))) "not found in" (::in a)]})
   (and (instance? java.util.Map (::in a)) (::more e))
   {:type :fail :raw [str-e str-a]
    :message "Using both 'in with a map and 'more is not supported."}
   (instance? java.util.Map (::in a))
   (let [a (::in a)]
     (if (= e (select-keys a (keys e)))
       {:type :pass}
       {:type :fail
        :expected-message (format "in expected, not actual: %s" (first (clojure.data/diff e a)))
        :actual-message (format "in actual, not expected: %s" (first (clojure.data/diff a e)))
        :raw [str-e str-a]
        :result ["expected:" (pr-str e) "in" (pr-str a)]}))
   :default {:type :fail :raw [str-e str-a]
             :result ["You supplied:" (pr-str (::in a))]
             :message "You must supply a list, set, or map when using (in)"}))

(defmethod compare-expr [Class Object] [e a str-e str-a]
  (if (instance? e a)
    {:type :pass}
    {:type :fail :raw [str-e str-a]
     :expected-message (str "expected: " a " to be an instance of " e)
     :actual-message (str "     was: " a " is an instance of " (class a))}))

(defmethod compare-expr [Class Class] [e a str-e str-a]
  (if (isa? a e)
    {:type :pass}
    {:type :fail :raw [str-e str-a]
     :expected-message (str "expected: " a " to be a " e)}))

(defmethod compare-expr ::actual-exception [e a str-e str-a]
  {:type :error
   :raw [str-e str-a]
   :actual-message (str "exception in actual: " str-a)
   :result [a]})

(defmethod compare-expr ::expected-exception [e a str-e str-a]
  {:type :error
   :raw [str-e str-a]
   :expected-message (str "exception in expected: " str-e)
   :result [e]})

(defmethod compare-expr [java.util.regex.Pattern java.util.regex.Pattern] [e a str-e str-a]
  (compare-expr (.pattern e) (.pattern a) str-e str-a))

(defmethod compare-expr [java.util.regex.Pattern Object] [e a str-e str-a]
  (if (re-seq e a)
    {:type :pass}
    {:type :fail,
     :raw [str-e str-a]
     :result ["regex" (pr-str e) "not found in" (pr-str a)]}))

(defmethod compare-expr [String String] [e a str-e str-a]
  (if (= e a)
    {:type :pass}
    (let [matches (->> (map vector e a) (take-while (partial apply =)) (map first) (apply str))
          e-diverges (clojure.string/replace e matches "")
          a-diverges (clojure.string/replace a matches "")]
      {:type :fail :raw [str-e str-a]
       :result ["expected:" (pr-str e)
                "\n                was:" (pr-str a)]
       :message (str
                 "matches: " (pr-str matches)
                 "\n           diverges: " (pr-str e-diverges)
                 "\n                  &: " (pr-str a-diverges))})))

(defmethod compare-expr ::expect-exception [e a str-e str-a]
  (if (instance? e a)
    {:type :pass}
    {:type :fail :raw [str-e str-a]
     :result [str-a "did not throw" str-e]}))

(defmethod compare-expr [java.util.Map java.util.Map] [e a str-e str-a]
  (if (= e a)
       {:type :pass}
       {:type :fail
        :expected-message (format "in expected, not actual: %s" (first (clojure.data/diff e a)))
        :actual-message (format "in actual, not expected: %s" (first (clojure.data/diff a e)))
        :raw [str-e str-a]
        :result ["expected:" (pr-str e) "\n                was:" (pr-str a)]}))

(defmethod compare-expr [java.util.Set java.util.Set] [e a str-e str-a]
  (if (= e a)
    {:type :pass}
    {:type :fail
     :actual-message (format "in actual, not expected: %s" (first (clojure.data/diff a e)))
     :expected-message (format "in expected, not actual: %s" (first (clojure.data/diff e a)))
     :raw [str-e str-a]
     :result ["expected:" e "\n                was:" (pr-str a)]}))

(defmethod compare-expr [java.util.List java.util.List] [e a str-e str-a]
  (if (= e a)
    {:type :pass}
    (let [diff-fn (fn [e a] (seq (difference (set e) (set a))))]
      {:type :fail
       :actual-message (format "in actual, not expected: %s" (first (clojure.data/diff a e)))
       :expected-message (format "in expected, not actual: %s" (first (clojure.data/diff e a)))
       :raw [str-e str-a]
       :result ["expected:" e "\n                was:" (pr-str a)]
       :message (cond
                 (and
                  (= (set e) (set a))
                  (= (count e) (count a))
                  (= (count e) (count (set a))))
                 "lists appear to contain the same items with different ordering"
                 (and (= (set e) (set a)) (< (count e) (count a)))
                 "some duplicate items in actual are not expected"
                 (and (= (set e) (set a)) (> (count e) (count a)))
                 "some duplicate items in expected are not actual"
                 (< (count e) (count a))
                 "actual is larger than expected"
                 (> (count e) (count a))
                 "expected is larger than actual")})))

(defmacro doexpect [e a]
  `(let [e# (try ~e (catch Throwable t# t#))
         a# (try ~a (catch Throwable t# t#))]
     (report
      (try (compare-expr e# a# '~e '~a)
           (catch Throwable e2#
             (compare-expr e2# a# '~e '~a))))))

(defmacro expect
  ([a] `(expect true (if ~a true false)))
  ([e a]
     `(def ~(vary-meta (gensym) assoc :expectation true)
        (fn [] (doexpect ~e ~a)))))

(defmacro expect-let [bindings e a]
  `(def ~(vary-meta (gensym) assoc :expectation true)
     (fn [] (let ~bindings (doexpect ~e ~a)))))

(defmacro expect-focused
  ([a] `(expect-focused true (if ~a true false)))
  ([e a]
     `(def ~(vary-meta (gensym) assoc :expectation true :focused true)
        (fn [] (doexpect ~e ~a)))))

(defmacro expect-let-focused [bindings e a]
  `(def ~(vary-meta (gensym) assoc :expectation true :focused true)
     (fn [] (let ~bindings (doexpect ~e ~a)))))

(defmacro expanding [n] (list 'quote  (macroexpand-1 n)))

(->
 (Runtime/getRuntime)
 (.addShutdownHook
  (proxy [Thread] []
    (run [] (when @run-tests-on-shutdown (run-all-tests))))))

(defn var->symbol [v]
  (symbol (str (.ns v) "/" (.sym v))))

(defmulti localize class)
(defmethod localize clojure.lang.Atom [a] (atom @a))
(defmethod localize clojure.lang.Agent [a] (agent @a))
(defmethod localize clojure.lang.Ref [a] (ref @a))
(defmethod localize :default [v] v)

(defn binding-&-localized-val [var]
  (when (bound? var)
    (when-let [vv (var-get var)]
      (when (#{clojure.lang.Agent clojure.lang.Atom clojure.lang.Ref} (class vv))
        [(var->symbol var) (list 'localize (var->symbol var))]))))

(defn default-local-vals [namespaces]
  (->>
   namespaces
   (mapcat (comp vals ns-interns))
   (mapcat binding-&-localized-val)
   (remove nil?)
   vec))

(defmacro redef-state [namespaces & forms]
  `(with-redefs ~(default-local-vals namespaces) ~@forms))

(defmacro freeze-time [time & forms]
  `(try
     (org.joda.time.DateTimeUtils/setCurrentMillisFixed (.getMillis ~time))
     ~@forms
     (finally
       (org.joda.time.DateTimeUtils/setCurrentMillisSystem))))

(defmacro ^{:private true} assert-args [fnname & pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                 ~(str fnname " requires " (second pairs)))))
       ~(let [more (nnext pairs)]
          (when more
            (list* `assert-args fnname more)))))

(defmacro context [[sym-kw val & contexts :as args] & forms]
  (assert-args context
               (vector? args) "a vector for its contexts"
               (even? (count args)) "an even number of forms in the contexts vector")
  (if (empty? contexts)
    `(~(symbol (name sym-kw)) ~val
      ~@forms)
    `(~(symbol (name sym-kw)) ~val
      (context ~(vec contexts)
               ~@forms))))

(defmacro from-each [seq-exprs body-expr]
  (let [vs (for [[p1 p2 :as pairs] (partition 2 seq-exprs)
                 :when (and (not= :when p1) (not= :while p1))
                 :let [vars (->> (if (= p1 :let)
                                   p2
                                   pairs)
                                 destructure
                                 (keep-indexed #(when (even? %1) %2))
                                 (map str)
                                 distinct
                                 (remove (partial re-find #"^(map|vec)__\d+$")))]
                 v vars]
             v)]
    `(hash-map ::from-each (doall (for ~seq-exprs
                                    {::the-result (try ~body-expr
                                                       (catch Throwable t# t#))
                                     ::ref-data ~(vec (interleave vs (map symbol vs)))}))
               ::from-each-body '~body-expr
               ::from-each-flag true)))

(defmacro more [& expects]
  `(hash-map ::more ~(vec (map (fn [e] {:e e :str-e `(quote ~e)
                                       :gen-str-a identity
                                       :a-fn identity})
                               expects))))

(defmacro more-> [& expect-pairs]
  (assert-args more->
               (even? (count expect-pairs)) "an even number of forms.")
  `(hash-map ::more ~(vec (map (fn [[e a-form]]
                                 {:e e :str-e `(quote ~e)
                                  :gen-str-a `(fn [x#] (macroexpand-1 (list '-> x# '~a-form)))
                                  :a-fn `(fn [x#] (-> x# ~a-form))})
                               (partition 2 expect-pairs)))))

(defmacro more-of [let-sexp & expect-pairs]
  (assert-args more-of
               (even? (count expect-pairs)) "an even number of expect-pairs")
  `(hash-map ::more ~(vec (map (fn [[e a-form]]
                                 {:e e :str-e `(quote ~e)
                                  :gen-str-a `(fn [x#] (list '~'let ['~let-sexp x#]
                                                            '~a-form))
                                  :a-fn `(fn [~let-sexp] ~a-form)})
                               (partition 2 expect-pairs)))))



(defmacro side-effects [fn-vec & forms]
  (assert-args side-effects
               (vector? fn-vec) "a vector for its fn-vec")
  (let [side-effects-sym (gensym "conf-fn")]
    `(let [~side-effects-sym (atom [])]
       (with-redefs ~(vec (interleave fn-vec (repeat `(fn [& args#] (swap! ~side-effects-sym conj args#)))))
         ~@forms)
       @~side-effects-sym)))
