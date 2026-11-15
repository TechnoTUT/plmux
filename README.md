# plmux

[Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger) の機能のうち、サークルで使うものをサーバー（ディスプレイなし）＋ Web GUI で使えるようにしたもの。
XDJ-AZ での利用を前提にしている。

- **プレイヤー状態表示**: BPM / ピッチ / 拍 / 再生・On-Air・Master・Sync / 曲名・アーティスト・アートワーク / 再生位置
- **曲履歴ログ**: 一定時間 (既定 15 秒) 再生かつ On-Air になった曲を自動記録。セッション（イベント）単位で管理し CSV に出力
- **Ableton Link**: Carabiner 経由で Link セッションの監視、または Link のテンポと拍を CDJ のマスターに追従

## 構成

```
vendor/beat-link/     git submodule (main)  Pro DJ Link ライブラリ
vendor/crate-digger/  git submodule (main)  beat-link が使う rekordbox DB パーサ
server/               Java 21 + Javalin     REST API + WebSocket、Vue のビルド成果物を配信
web/                  Vue 3 + Vite + TS     Web GUI
deploy/k8s/           参考用マニフェスト（本番は TechnoTUT/Infra）
```

beat-link はリリース版 (8.0.0) ではなく **main** を使う。XDJ-AZ 対応（Pro DJ Link モード検出、
Device Library Plus、Beatport Streaming のスロット等）がまだリリースされていないため。
main が依存する crate-digger も SNAPSHOT なので、リモートの SNAPSHOT は使わず submodule からビルドする。

## ビルド・起動

```bash
git clone --recurse-submodules <this repo>    # 既存の clone なら: git submodule update --init

(cd web && npm ci && npm run build)           # web/dist が jar に同梱される
./mvnw -DskipTests package -pl server -am
java -jar server/target/plmux-server.jar      # http://localhost:8080
```

開発時は `java -jar ...` でサーバーを起動したまま `cd web && npm run dev` で Vite を起動する
(API / WebSocket は 8080 に中継される)。

### コンテナ

```bash
docker build -t plmux .
docker run --network host -v plmux-data:/data plmux
```

`--network host` が必須（Pro DJ Link は UDP ブロードキャストを使うため）。

## 設定（環境変数）

| 変数 | 既定値 | 説明 |
|---|---|---|
| `PLMUX_HTTP_PORT` | `8080` | Web GUI / API のポート |
| `PLMUX_DATA_DIR` | `./data` | SQLite (`plmux.db`) の保存先 |
| `PLMUX_DEVICE_NAME` | `plmux` | DJ Link 上に名乗るデバイス名 |
| `PLMUX_USE_STANDARD_PLAYER_NUMBER` | `false` | Virtual CDJ に 1〜4 番を使うか。XDJ-AZ では不要 |
| `PLMUX_HISTORY_MIN_PLAY_SECONDS` | `15` | 何秒再生されたら履歴に記録するか |
| `PLMUX_HISTORY_REQUIRE_ON_AIR` | `true` | On-Air（フェーダーが上がっている）ときだけ再生時間を数えるか |
| `PLMUX_LINK_MODE` | `OFF` | 起動時の Link モード: `OFF` / `MONITOR` / `FOLLOW` |
| `PLMUX_LINK_LATENCY_MS` | `1` | 拍パケットの遅延補正 |
| `PLMUX_LINK_ALIGN_BARS` | `true` | 拍だけでなく小節の頭も揃える |
| `PLMUX_CARABINER_PORT` | `17000` | Carabiner のポート |
| `PLMUX_CARABINER_EMBEDDED` | `true` | Carabiner が起動していなければ同梱バイナリを起動する |
| `TZ` | (OS) | 履歴 CSV の時刻のタイムゾーン |

## XDJ-AZ で使うときの注意

- XDJ-AZ 本体の設定で **LINK (Pro DJ Link) モード** にする。それ以外のモードだと画面に警告が出る。
- サーバーは XDJ-AZ の LINK 端子と**同じ LAN（同じ L2 セグメント）**に置く。
- メタデータは XDJ-AZ への dbserver 問い合わせで取得する（XDJ-AZ は Device Library Plus の ID を使うため、
  DeviceSQL の DB ファイルのダウンロードは使えない。beat-link main が自動で切り替える）。
- 4 デッキ分のステータスが 1 台から届くので、プレイヤーはステータスパケットから列挙している。
- XDJ-AZ / XDJ-XZ ではスロット表記を `USB 1` / `USB 2` に読み替えている（BLT と同じ）。

## Kubernetes

`deploy/k8s/` は参考用。本番マニフェストは TechnoTUT/Infra に置く。ポイント:

- **`hostNetwork: true` が必須。** さらに Pod は **DJ ブースの LAN に NIC を持つノード**に固定する
  (`nodeSelector`)。クラスタが会場の外にある場合は、会場に置いたマシン (k3s agent 等) をノードとして参加させる。
- UDP 50000〜50002 と SQLite を占有するので `replicas: 1`、`strategy: Recreate`。
- 履歴 DB 用の PVC を `/data` に、同梱 Carabiner の展開先として `/tmp` に emptyDir をマウントする。
- 同梱 Carabiner は **x86_64 のみ**（lib-carabiner に入っている ARM 版は 32bit armhf 用）。
  arm64 ノードで Link を使う場合は Carabiner を別途起動し、`PLMUX_CARABINER_EMBEDDED=false` にする。
- Ingress 経由の場合は WebSocket (`/ws`) が通るようにする。
- 現状 Web GUI に認証はない。ブース LAN 内に閉じるか、Ingress 側で認証をかけること。
- コンテナイメージは `.github/workflows/image.yml` で `ghcr.io/<owner>/<repo>` に push される。

## beat-link の更新

```bash
scripts/update-vendor.sh   # submodule を main 最新に更新し、pom.xml の beatlink.version を追従
./mvnw -DskipTests package -pl server -am
git add vendor pom.xml && git commit -m "Update beat-link"
```

## API

| メソッド | パス | 説明 |
|---|---|---|
| WS | `/ws` | `state`（状態, 最大 10Hz）/ `beat`（拍）/ `history`（新しい履歴）を配信 |
| GET | `/api/state` | 現在の状態 |
| GET | `/api/players/{n}/artwork` | アートワーク画像 |
| GET / PUT | `/api/link` | Link 設定 `{mode, latencyMs, alignBars}` |
| GET / POST | `/api/sessions` | セッション一覧 / 開始 `{name}` |
| POST | `/api/sessions/end` | 進行中のセッションを終了 |
| GET | `/api/history?session=current\|all\|{id}` | 履歴 |
| GET | `/api/history.csv?session=...` | 履歴 CSV |
| DELETE | `/api/history/{id}` | 履歴を 1 件削除 |
| GET | `/healthz` | ヘルスチェック |

## ライセンス

Eclipse Public License 2.0（Beat Link Trigger に合わせる）。
Ableton Link の追従ロジックは [beat-carabiner](https://github.com/Deep-Symmetry/beat-carabiner) を Java に移植したもの。
