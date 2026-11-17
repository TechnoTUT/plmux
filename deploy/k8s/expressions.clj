;; plmux の Clojure の式の設定例: TechnoTUT/dmxctl で照明を曲に合わせる。
;; dmxctl の README「Beat Link Trigger との連携」と同じことを、BLT なしで plmux から行う。
;;
;; plmux のイメージには式を同梱しない。本番ではこのファイルを ConfigMap (plmux-expressions) で投入し、
;; PLMUX_EXPRESSIONS_FILE=/config/expressions.clj で読ませる (kustomization.yaml / deployment.yaml を参照)。
;; ConfigMap を更新すると、Pod を作り直さなくても数十秒で読み込み直される。
;;
;; dmxctl 側: 「MIDI/OSC」タブで OSC を受信、「オート」タブでオートを ON。テンポの元は Ableton Link
;; (plmux の Link モードを Follow CDJ Master に)。

(def dmxctl-host "192.168.1.20")   ; dmxctl を動かしている PC の IP アドレス
(def dmxctl-port 9000)             ; dmxctl の OSC 受信ポート (UDP)

(global-setup
  (swap! globals assoc :dmx (osc/osc-client dmxctl-host dmxctl-port)))

;; 波形の色・大きさを LEAD 秒先読みして、照明が光る (LIGHT 秒遅れる) ときにちょうどその色・明るさになるよう送る。
;; 色は GUI の表示の形式 (3-Band など) とは関係なく、rekordbox の RGB 波形から取る。
(every 40
  (when playing?
    (let [lead  0.15
          light 0.05
          s     (waveform-sample device-number (+ track-time-reached (* lead 1000 pitch-multiplier)))]
      (when s
        (when (:r s)
          (osc/osc-send (:dmx @globals) "/dmx/main/rgb/at"
                        (float (:r s)) (float (:g s)) (float (:b s)) (float (- lead light))))
        (osc/osc-send (:dmx @globals) "/dmx/level/at" (float (:level s)) (float (- lead light)))))))

;; rekordbox のフレーズ (intro / verse / bridge / chorus / up / down / outro) で見た目を切り替える。
(phrase-started
  (when phrase-kind
    (osc/osc-send (:dmx @globals) "/dmx/phrase" phrase-kind)))

;; Ableton Link (UDP マルチキャスト) が dmxctl の PC まで届かないときだけ、テンポと小節の頭を OSC で送る。
;; (beat
;;   (osc/osc-send (:dmx @globals) "/dmx/bpm" (float effective-tempo))
;;   (when (= beat-within-bar 1)
;;     (osc/osc-send (:dmx @globals) "/dmx/resync")))

(global-shutdown
  (osc/osc-close (:dmx @globals)))
