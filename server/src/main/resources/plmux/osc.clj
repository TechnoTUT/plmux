(ns plmux.osc
  "OSC の送信。Beat Link Trigger の式で使う overtone/osc-clj (`osc/osc-client` など) と同じ名前・引数にしてあるので、
  BLT 用に書いた式をそのまま使える。送信だけで、受信 (osc-server) はない。

  引数の型は osc-clj と同じく Clojure の値の型で決まる: Long → h (64bit 整数), Double → d (64bit 小数),
  Integer → i, Float → f, String → s, Boolean → T/F, byte 配列 → b。
  32bit で送りたいときは (int x) / (float x) で変換する。"
  (:import [plmux OscClient]))

(defn osc-client
  "host の port (UDP) に送るクライアントを作る。使い終わったら osc-close で閉じる。"
  [host port]
  (OscClient. (str host) (int port)))

(defn osc-send
  "path に args を付けた OSC メッセージを 1 つ送る。"
  [^OscClient client path & args]
  (.send client (str path) (object-array args)))

(defn osc-close
  "クライアントを閉じる。nil でもよい。"
  [^OscClient client & _]
  (when client (.close client)))
