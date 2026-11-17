(ns plmux.expressions
  "Beat Link Trigger のトリガーの式と同じように、Clojure で plmux に機能を足すための仕組み。

  式のファイル (既定は データディレクトリ/expressions.clj) は、読み込むたびに新しい名前空間 plmux.user で評価される。
  ファイルの中では、フックのマクロ (global-setup, tracked-update など) の本体に式を書く。
  本体の中では BLT と同じ名前の値 (status, effective-tempo, beat-within-bar など) が使え、
  globals / locals (どちらも atom) と osc/osc-client などもそのまま使える。

  フックはすべて 1 本のスレッドで順番に実行される。重い処理を書くと、ほかのフックが遅れる。"
  (:import [plmux ExpressionService Model$Player Model$Track WaveformSampler$Sample]))

(def ^ExpressionService service
  "ExpressionService が起動時に設定する。"
  nil)

(def hook-names
  "定義できるフック。順番は GUI の表示順。"
  [:global-setup :setup :came-online :tracked-update :every :beat :phrase-started :phrase-ended :going-offline
   :shutdown :global-shutdown])

(def ^:dynamic ^:no-doc *hooks*
  "読み込み中のファイルで定義されたフック。"
  nil)

(defn ^:no-doc register!
  [hook f opts]
  (when-not *hooks*
    (throw (IllegalStateException. (str (name hook) " can only be used at the top level of the expressions file"))))
  (when (contains? @*hooks* hook)
    (throw (IllegalArgumentException. (str (name hook) " is defined more than once"))))
  (swap! *hooks* assoc hook (assoc opts :fn f))
  hook)

(def ^:no-doc context-symbols
  "フックの本体で使える値。ExpressionService が同じ名前のキーワードで渡す (そのフックで意味のないものは nil)。"
  '[status player device-number playing? tempo-master? synced? on-air? effective-tempo pitch-multiplier
    beat-number beat-within-bar track-time-reached rekordbox-id track-title track-artist track-album track-genre
    track-key track-length phrase phrase-kind phrase-label])

(defmacro ^:no-doc with-context
  [ctx & body]
  `(let [~@(mapcat (fn [s] [s `(get ~ctx ~(keyword (name s)))]) context-symbols)]
     ~@body))

(defn- hook-form
  [hook body opts]
  `(register! ~hook (fn [ctx#] (with-context ctx# ~@body)) ~opts))

(defmacro global-setup
  "式を読み込んだとき (起動したとき・ファイルが変わったとき) に 1 回実行する。OSC の送り先を作るなど。"
  [& body]
  (hook-form :global-setup body {}))

(defmacro global-shutdown
  "式を捨てるとき (終了するとき・ファイルが変わったとき) に 1 回実行する。global-setup で作ったものを閉じる。"
  [& body]
  (hook-form :global-shutdown body {}))

(defmacro setup
  "BLT のトリガーの Setup にあたる。global-setup の直後に実行する (locals を使う BLT の式を貼るとき用)。"
  [& body]
  (hook-form :setup body {}))

(defmacro shutdown
  "BLT のトリガーの Shutdown にあたる。global-shutdown の直前に実行する。"
  [& body]
  (hook-form :shutdown body {}))

(defmacro came-online
  "DJ Link につながったとき。式を読み込んだときにすでにつながっていれば、そのときにも実行する。"
  [& body]
  (hook-form :came-online body {}))

(defmacro going-offline
  "DJ Link から切れたとき。つながったまま式を捨てるときにも実行する。"
  [& body]
  (hook-form :going-offline body {}))

(defmacro tracked-update
  "テンポマスターのデッキの状態が届くたび (約 200ms ごと)。status は beat-link の CdjStatus (デモでは nil)。"
  [& body]
  (hook-form :tracked-update body {}))

(defmacro beat
  "テンポマスターのデッキの拍ごと。status は beat-link の Beat (デモでは nil)。"
  [& body]
  (hook-form :beat body {}))

(defmacro phrase-started
  "テンポマスターのデッキで、rekordbox のフレーズ (曲の構成) に入ったとき。phrase / phrase-kind / phrase-label が使える。"
  [& body]
  (hook-form :phrase-started body {}))

(defmacro phrase-ended
  "テンポマスターのデッキで、フレーズが終わったとき (次のフレーズの phrase-started の前・曲や再生デッキが変わったとき)。"
  [& body]
  (hook-form :phrase-ended body {}))

(defmacro every
  "interval-ms ミリ秒ごとに、テンポマスターのデッキの今の状態で実行する (plmux だけの機能。最短 20ms)。
  status は nil。照明などを tracked-update より細かく動かしたいときに使う。"
  [interval-ms & body]
  (hook-form :every body {:interval-ms interval-ms}))

;; ---- 式から使える関数 ----

(defn current-state
  "plmux の今の状態 (Model$State。GUI に送っているものと同じ)。"
  []
  (.state service))

(defn master-player
  "テンポマスターのデッキ (Model$Player)。なければ nil。"
  []
  (.masterPlayer service))

(defn waveform
  "デッキの波形・ビートグリッド・キュー・フレーズ (Model$Waveform)。まだ取れていなければ nil。"
  [player]
  (.waveform service (int player)))

(defn waveform-sample
  "デッキの曲の time-ms (曲の先頭からのミリ秒) のあたり (前後約 0.05 秒) の波形の色と大きさ。
  {:r :g :b :level} の map で、どれも 0〜1。曲の範囲外や、波形がまだ取れていなければ nil。

  色は GUI の表示の形式 (Settings) とは関係なく、rekordbox の色付き (RGB) 波形から取る。
  曲に色付き波形の解析がないときだけ表示用の波形を使い、3-Band なら低域・中域・高域を赤・緑・青にする
  (Blue では :r :g :b が nil)。"
  [player time-ms]
  (when-let [^WaveformSampler$Sample s (.sample service (int player) (double time-ms))]
    (let [rgb (.rgb s)]
      {:r     (when rgb (aget rgb 0))
       :g     (when rgb (aget rgb 1))
       :b     (when rgb (aget rgb 2))
       :level (.level s)})))

(defn rgb-waveform-detail
  "デッキの曲の色付き (RGB) 詳細波形を beat-link の WaveformDetail で返す (表示の形式とは関係ない)。
  BLT の式の (.getLatestDetailFor (WaveformFinder/getInstance) n) の代わりに使うと、.segmentColor が使える。
  実機だけで、デモや色付き波形がない曲では nil。"
  [player]
  (.rgbWaveformDetail service (int player)))

(defn beat-at
  "デッキの曲の time-ms にある拍の番号 (1 始まり)。ビートグリッドがなければ 0。"
  [player time-ms]
  (.beatAt service (int player) (double time-ms)))

(defn phrase-at
  "デッキの曲の、その拍 (1 始まり) を含むフレーズ (Model$Phrase)。なければ nil。"
  [player beat]
  (.phraseAt service (int player) (int beat)))

(def ^:private user-api
  '[global-setup global-shutdown setup shutdown came-online going-offline tracked-update beat phrase-started phrase-ended every
    current-state master-player waveform waveform-sample rgb-waveform-detail beat-at phrase-at])

(defn ^:no-doc load-expressions
  "source を新しい名前空間 plmux.user で評価し、定義されたフックの map を返す。"
  [^String source ^String path]
  (remove-ns 'plmux.user)
  (let [user-ns (create-ns 'plmux.user)
        hooks   (atom {})]
    (binding [*ns* user-ns
              *hooks* hooks]
      (refer-clojure)
      (require '[plmux.osc :as osc])
      (refer 'plmux.expressions :only user-api)
      ;; BLT の式でよく使う beat-link のクラス
      (import '[org.deepsymmetry.beatlink Beat CdjStatus DeviceFinder DeviceUpdate Util VirtualCdj]
              '[org.deepsymmetry.beatlink.data AnalysisTagFinder BeatGridFinder MetadataFinder TimeFinder
                WaveformFinder]
              '[plmux Model$Player Model$Phrase Model$State Model$Track Model$Waveform])
      (intern user-ns 'globals (atom {}))
      (intern user-ns 'locals (atom {}))
      (clojure.lang.Compiler/load (java.io.StringReader. source) path (.getName (java.io.File. path))))
    @hooks))

(defn ^:no-doc unload-expressions
  []
  (remove-ns 'plmux.user))
