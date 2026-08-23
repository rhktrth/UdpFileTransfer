# UdpFileTransfer 外部設計書

## 1. はじめに

### 1.1 目的

本書は、UdpFileTransfer の利用者、通信相手、OS から確認できる外部仕様を定義します。コマンドライン、UDP 通信、通信データ形式、ファイル入出力、表示、終了コード、セキュリティ上の境界を対象とします。

内部のクラス構成、並行処理、リソース管理は [`INTERNAL_DESIGN.md`](INTERNAL_DESIGN.md) に記載します。重要な設計判断の理由は [`adr/`](adr/) を参照してください。

### 1.2 対象範囲

UdpFileTransfer は次の2モードを提供します。

- `send`: 1つの入力ファイルをメタデータとデータチャンクに分け、UDP で送信する。
- `recv`: 1つの転送セッションを受信し、指定した出力ファイルを再構成する。

### 1.3 関連文書

| 文書 | 内容 |
| --- | --- |
| [`../README.md`](../README.md) | 利用者向け概要と基本操作 |
| [`README.md`](README.md) | 文書体系と仕様駆動開発の規則 |
| [`INTERNAL_DESIGN.md`](INTERNAL_DESIGN.md) | 内部構造と処理方式 |
| [`OPERATIONS.md`](OPERATIONS.md) | ビルド、テスト、リリース |
| [`adr/`](adr/) | 重要な設計判断と理由 |

---

## 2. システム概要

### 2.1 一方向通信

UdpFileTransfer は、**送信側から受信側への一方向通信しか利用できない環境で、1ファイルを転送する Java 8 コマンドラインツール**です。

ACK、NACK、自動再送、フロー制御、ハンドシェイクは使用しません。UDP データグラムが欠落した場合、受信側で未受信のシーケンス番号を確認し、利用者が電話、チャット、別ネットワークなどの別経路で送信側へ伝えて必要なチャンクだけを再送します。

**EXT-NET-001 — 一方向通信**

ファイル転送に必要なネットワーク通信は、送信側から受信側への UDP データグラムだけで成立しなければなりません。受信側から送信側への通信を必須としてはなりません。

### 2.2 転送セッション

1回の `send` 起動では、入力ファイルに対してランダムな128 bitのセッションIDを1つ生成します。同じ起動中に送るメタデータと全データパケットは同じセッションIDを使用します。

受信側は、通信形式が有効で、ローカルの最大チャンク数以下である**最初のメタデータ**を採用します。その時点でセッションID、チャンクサイズ、チャンク数、ファイルサイズ、SHA-256を固定し、それ以後に届く別セッションのメタデータやデータは現在の転送へ使用しません。

**EXT-SESSION-001 — セッション分離**

採用済みメタデータと異なるセッションIDを持つパケットは、受信途中ファイル、受信済み番号、完了状態へ反映してはなりません。

**EXT-SESSION-002 — 最初のメタデータでセッションを固定**

受信側は最初に採用したメタデータだけをその受信処理の基準とし、後から届いた別セッションの有効なメタデータへ切り替えてはなりません。

セッションIDは、採用済み転送へ別転送のパケットが混入することを防ぐための識別子です。送信者認証、認可、事前共有された転送指定ではありません。待受ポートへ到達できる第三者が先に有効なメタデータを送った場合、そのメタデータが採用され得ます。`recv --expected-session` のような事前指定機能は提供しません。

### 2.3 完全性確認と出力確定

送信側は初期化時に入力ファイル全体の SHA-256 を計算し、メタデータへ格納します。受信側はデータを `<output-file>.part` に再構成し、全チャンク受信後に `.part` のファイルサイズと SHA-256 を検証します。両方が一致した場合だけ `.part` を `<output-file>` へ置換し、正常完了とします。

**EXT-HASH-001 — 完成ファイルの SHA-256 検証**

SHA-256 が一致しない場合、`receive completed` を表示せず、受信エラーとして終了コード `1` で終了します。既存の `<output-file>` は変更せず、不一致の `<output-file>.part` は調査または再実行に備えて残します。

SHA-256 は内容の一致確認に使用しますが、メタデータ自体を認証しません。攻撃者がメタデータとデータの双方を書き換えられる環境で送信者の真正性を保証するものではありません。

### 2.4 提供しない機能

現在は次を提供しません。

- ACK / NACK に基づく自動再送
- 輻輳制御、フロー制御
- 受信側から送信側への完了通知
- 1回の受信処理での複数ファイル受信
- 送信者認証、認可
- 暗号化、MAC、電子署名
- 期待セッションIDの事前指定
- 送信側が指定したパスへの自動保存

### 2.5 動作環境

**EXT-ENV-001 — Java 8 互換**

配布 JAR は Java 8 で実行できなければなりません。

---

## 3. コマンドライン設計

### 3.1 実行形式

**EXT-CLI-001 — 動作モードとファイル指定**

```text
java -jar UdpFileTransfer.jar recv <output-file> [-p <port>] [-n <max-chunks>]
java -jar UdpFileTransfer.jar send <input-file> [-h <host>] [-p <port>] [-c <chunk-size>] [-i <interval-ms>]
```

対象ファイルの指定は必須です。旧 `-m` と `-s` は互換オプションとして扱わず、未定義オプションとしてエラーにします。

### 3.2 送信モード

| オプション | 内容 | 既定値 | 許容値 |
| --- | --- | --- | --- |
| `-h <host>` | 送信先ホスト | `127.0.0.1` | 空文字列以外 |
| `-p <port>` | 送信先 UDP ポート | `30070` | `1..65535` |
| `-c <chunk-size>` | 1データグラムに格納するデータ部分の最大長 | `700` bytes | `1..9972` |
| `-i <interval-ms>` | 各データグラム送信後の待機時間 | `0` ms | `0` 以上の整数 |

**EXT-CLI-002 — 送信モードの既定値**

オプションを省略した場合、ホスト `127.0.0.1`、ポート `30070`、チャンクサイズ `700` bytes、送信間隔 `0` ms を使用します。

### 3.3 受信モード

| オプション | 内容 | 既定値 | 許容値 |
| --- | --- | --- | --- |
| `-p <port>` | 待受 UDP ポート | `30070` | `1..65535` |
| `-n <max-chunks>` | 1転送で受け入れる最大チャンク数 | `300000` | `1` 以上の64 bit整数 |

**EXT-LIMIT-001 — 受信チャンク数上限**

受信側の既定上限は `300000` チャンクです。メタデータの `chunkCount` がローカル上限を超える場合、そのメタデータは採用せず、`<output-file>` と `<output-file>.part` を変更せずに次のメタデータを待ちます。

この上限は、受信側が保持する受信済みシーケンス状態と、1転送あたりに必要となるデータグラム数を直接制限するためのローカル方針です。ファイルサイズそのものの独立した上限は設けません。実際に受信できるファイルサイズは、チャンクサイズと最大チャンク数の組合せによって決まります。

### 3.4 引数エラー

次を引数エラーとします。

- モードまたはファイルがない。
- モードが `send` / `recv` 以外。
- オプション値がない。
- 未定義オプション。
- 整数項目を整数として解釈できない。
- モードに対して使用できないオプション。
- ポート、チャンクサイズ、送信間隔、最大チャンク数、ホスト名が許容条件を満たさない。

**EXT-CLI-003 — 引数エラー時の終了**

標準エラー出力へ `error: <reason>` と使用方法を表示し、終了コード `2` で終了します。

### 3.5 送信中の対話コマンド

| 入力 | 動作 |
| --- | --- |
| 空行 | `all` と同じ |
| `all` | メタデータを2回送信後、データ `0..N-1` を順に送信 |
| `meta` | メタデータを1回送信 |
| `<sequence>` | 指定番号のデータチャンクを1回送信 |
| `quit` | 正常終了 |
| EOF | 正常終了 |

**EXT-CLI-004 — 個別再送**

有効な番号は `0 <= sequence < chunkCount` です。範囲外や数値でない入力はデータを送らず、利用者向けエラーを表示してコマンド受付を継続します。

### 3.6 受信中の対話コマンド

| 入力 | 動作 |
| --- | --- |
| 空行 | `missing` と同じ |
| `missing` | シーケンス `0` から未受信番号を確認 |
| `missing <start>` | `<start>` から未受信番号を確認 |
| `quit` | 受信を中止して正常終了 |
| EOF | 受信を中止して正常終了 |

**EXT-CLI-005 — 未受信番号の確認範囲**

1回の `missing` では開始番号から最大1,000個を確認します。後続範囲がある場合は `next: missing <next-start>` を表示します。メタデータ受信前は `no metadata`、全データ受信済みなら `no missing data` を表示します。

### 3.7 終了コード

**EXT-CLI-006 — 終了コード**

| 終了コード | 意味 |
| --- | --- |
| `0` | 正常終了。送信側の `quit` / EOF、受信完了、受信側の明示中止を含む |
| `1` | 送受信の入出力エラー、サイズ不一致、SHA-256不一致、受信側コマンド入力の入出力エラー |
| `2` | コマンドライン引数エラー |

---

## 4. UDP 通信

### 4.1 基本方式

**EXT-NET-002 — 1パケット1データグラム**

メタデータパケットとデータパケットは、それぞれ1個の UDP データグラムで送信します。アプリケーション独自の再分割は行いません。

受信側は指定ポートをローカルのワイルドカードアドレスで待ち受けます。受信可能な送信元の制限は OS、ファイアウォール、ネットワークポリシーで行います。

**EXT-NET-003 — プロトコル応答なし**

受信側は ACK、NACK、未受信一覧、完了通知などを送信側へ UDP 送信しません。

### 4.2 データグラム長

**EXT-WIRE-001 — 最大データグラム長**

UFT1 が扱う最大 UDP データグラム長は `10000` bytesです。これを超えるデータグラムは破棄します。

既定のチャンクサイズは700 bytesです。大きなチャンクサイズを指定すると IP フラグメンテーションが発生し、ネットワークによっては損失しやすくなる可能性があります。`9972` bytes はプロトコル上の上限であり、推奨 MTU を意味しません。

---

## 5. UFT1 通信データ形式

本リポジトリは開発中のため、現在の `UFT1` 識別子のまま通信形式を更新することがあります。旧開発版との後方互換性は保証しません。

### 5.1 共通ヘッダー

**EXT-WIRE-002 — 共通ヘッダー**

すべてのパケットは次の28 bytesから始まります。複数byte整数はビッグエンディアンです。

```text
offset  length  content
0       4       ASCII "UFT1"
4       8       sequence number (signed 64-bit)
12      16      session id (128-bit)
```

`sequence = -1` はメタデータ、`0` 以上はデータを表します。

### 5.2 メタデータ

**EXT-WIRE-003 — メタデータ形式**

共通ヘッダーに続いて次の項目を格納します。

```text
offset  length      content
28      4           chunk size in bytes (signed 32-bit)
32      8           chunk count (signed 64-bit)
40      8           file size in bytes (signed 64-bit)
48      4           source file name length (signed 32-bit)
52      32          SHA-256
84      variable    UTF-8 source file name
```

送信元ファイル名はパスを除いたファイル名だけとし、UTF-8 で `1..1024` bytes とします。メタデータ全体の最大長は1108 bytesです。

**EXT-WIRE-004 — メタデータ項目の整合性**

`chunk size` は `1..9972`、`file size` は0以上とします。`chunkCount` は次と一致しなければなりません。

```text
file size == 0 : 0
file size > 0  : 1 + (file size - 1) / chunk size
```

ファイル名長は実際に残っている UTF-8 byte数と一致し、UTF-8 として厳密に復号できなければなりません。

**EXT-WIRE-005 — SHA-256**

SHA-256 は元ファイル全体の32 byteの生値です。文字列化した16進表現は wire に格納しません。

### 5.3 データパケット

共通ヘッダーの直後をファイルデータとします。データ部分の最大長は `10000 - 28 = 9972` bytesです。

**EXT-WIRE-006 — データ長の厳密な検証**

シーケンス `n` の期待データ長はメタデータの `file size` と `chunk size` から一意に決まります。最終パケットを含め、受信したデータ長が期待値と完全に一致しないパケットは破棄します。

### 5.4 不正・異なる転送のデータ

**EXT-WIRE-007 — 不正データの隔離**

次のデータグラムは当該データグラムだけを破棄し、正常受信を継続します。

- 10000 bytes超
- 共通ヘッダーが短い、または `UFT1` でない
- メタデータの構造・UTF-8・項目間整合性が不正
- ローカル最大チャンク数を超えるメタデータ
- 採用済みメタデータと異なるセッションID
- 範囲外または受信済みシーケンス
- 期待長と異なるデータ

不正なデータによって受信途中ファイル、受信済み番号、完了状態を進めてはなりません。

---

## 6. 送信機能

### 6.1 初期化

**EXT-SEND-001 — 送信情報の確定**

送信側は対話コマンド受付前に、入力ファイルサイズ、ファイル名、SHA-256、ランダムな128 bitセッションID、送信先アドレスを確定します。

入力ファイルを読めない、ファイル名が条件を満たさない、SHA-256を計算できない、名前解決やUDP初期化に失敗した場合は、対話コマンド受付へ進まず送信エラーとして終了します。

### 6.2 入力ファイルの変更

**EXT-SEND-002 — 転送中の入力ファイルは変更しない**

利用者は送信プロセス初期化後から転送終了まで入力ファイルを変更してはなりません。送信側がファイルサイズの変化を検出した場合は送信エラーとします。同じサイズのまま内容が変更された場合も、完成後のSHA-256検証により正常完了しません。

### 6.3 一括送信

**EXT-SEND-003 — `all` の送信順序**

`all` は、同一内容・同一セッションIDのメタデータを2回送信した後、データ `0..chunkCount-1` を昇順に各1回送信します。

### 6.4 送信間隔

`interval-ms > 0` の場合、各 UDP データグラム送信後に指定時間だけ待機します。待機中の割り込みは送信エラーとします。

---

## 7. 受信・ファイル機能

### 7.1 メタデータ採用

**EXT-RECV-001 — 最初の受理可能なメタデータを採用**

受信側は、通信形式が有効で、かつ `chunkCount` がローカル最大チャンク数以下である最初のメタデータを採用します。メタデータ採用前のデータは使用しません。採用後に届いた別のメタデータへ切り替えません。

### 7.2 受信途中ファイル

**EXT-FILE-001 — `.part` へ受信し、検証後に出力を確定**

採用可能なメタデータを受信した後にだけ `<output-file>.part` を `WRITE + CREATE + TRUNCATE_EXISTING` 相当で開きます。上限超過または不正なメタデータだけでは、既存の `<output-file>` と `<output-file>.part` を変更しません。

受信中は既存の `<output-file>` を変更しません。全チャンクの受信、サイズ確認、SHA-256確認がすべて成功した場合だけ `.part` を `<output-file>` へ rename/replace します。既存の最終ファイルがある場合は、この時点で置換します。

保存先はCLIで指定した `<output-file>` だけで決定します。メタデータの送信元ファイル名をローカル保存先として使用しません。

### 7.3 再構成と重複

**EXT-RECV-002 — 順不同・重複受信**

シーケンス `n` のデータは `chunk size * n` の位置へ書き込みます。到着順には依存しません。同じシーケンスを複数回受信した場合、**最初に正常に書き込みを完了した内容を採用し、後続の重複データを無視します。**

### 7.4 完了

**EXT-RECV-003 — 完了条件**

必要な全シーケンスを正常に `.part` へ書き込み、`.part` のファイルサイズがメタデータの `file size` と一致し、SHA-256が一致し、最終出力への置換に成功した場合にだけ `receive completed` を表示して正常終了します。

0 byteファイルも同じ規則で、空ファイルのSHA-256を検証します。

### 7.5 中止・検証失敗後の扱い

`quit` またはEOFでは受信待ちを解除して正常終了します。途中までの `.part` は自動削除しません。既存の最終出力は変更しません。

サイズまたはSHA-256が一致しなかった場合も `.part` を残し、最終出力へは置換しません。復旧する場合は原因を確認したうえで `recv` を再起動し、送信側からメタデータと全チャンクを再送します。新たにメタデータを採用すると `.part` は切り詰められ、受信状態は最初から作り直されます。

### 7.6 未受信番号

未受信番号は、メタデータの `0..chunkCount-1` から正常受信済み番号を除いたものです。`missing` は1回につき最大1,000番号を確認します。

---

## 8. 表示・エラー・セキュリティ

### 8.1 主な表示

起動時は `UdpFileTransfer` を表示します。送信モードでは入力ファイル、送信先、ポート、チャンクサイズ、送信間隔を表示します。受信モードでは出力ファイル、ポート、最大チャンク数を表示します。

有効なメタデータ採用時は `source file: <name>`、受信途中ファイル開始時は `receiving: <output-path>.part`、検証と置換まで成功した完了時は `receive completed` を表示します。

外部由来の送信元ファイル名に制御文字またはUnicodeの書式制御文字が含まれる場合、端末制御へ解釈されないよう `\uXXXX` 形式で表示します。

不正データの診断は利用者向け補助情報であり、すべての破棄理由について1パケットごとの固定メッセージを外部契約とはしません。

### 8.2 致命的エラー

| 種別 | 表示形式 |
| --- | --- |
| 引数エラー | `error: <reason>` + 使用方法 |
| 送信入出力エラー | `send error: <reason>` |
| 受信入出力・サイズ・SHA-256エラー | `receive error: <reason>` |
| 受信側コマンド入力エラー | `input error: <reason>` |

### 8.3 セキュリティ上の境界

**EXT-SEC-001 — 認証・暗号化は提供しない**

セッションIDはランダムな転送識別子、SHA-256は完成ファイルとメタデータの内容一致確認です。どちらも送信者認証、認可、暗号化、MAC、電子署名を提供しません。

特に、セッションIDは「期待する送信者を事前に指定する値」ではありません。受信側は最初に受理可能なメタデータを採用するため、待受 UDP ポートへ到達できる送信元を信頼できない場合は、OS、ファイアウォール、ネットワーク構成で到達範囲を制限する必要があります。

---

## 9. 受入条件

| ID | 前提・操作 | 期待結果 |
| --- | --- | --- |
| `AC-ENV-001` | Java 8で検証する | コンパイル・テスト・JAR生成が成功する |
| `AC-CLI-001` | `send <file>` を既定値で起動 | host 127.0.0.1 / port 30070 / chunk size 700 / interval 0 |
| `AC-CLI-002` | 不正引数で起動 | 理由とUsageをstderrへ表示しexit 2 |
| `AC-LIMIT-001` | `recv <file>` を既定値または `-n` 指定で起動 | 既定300000、指定値を最大チャンク数として使用 |
| `AC-LIMIT-002` | 上限超過metadataを既存出力ファイルがある状態で受信 | metadataを採用せず、最終ファイルと`.part`を変更しない |
| `AC-WIRE-001` | 固定セッションIDとsequenceでヘッダー生成 | UFT1、big-endian sequence、16 byte session IDが規定位置に並ぶ |
| `AC-WIRE-002` | 固定値でメタデータ生成 | file size、chunk count、SHA-256、UTF-8名を含む規定byte列になる |
| `AC-WIRE-003` | 不整合メタデータ、不正UTF-8、10000 bytes超を受信 | 状態を進めず正常受信を継続する |
| `AC-SESSION-001` | 採用メタデータと異なるsession IDのデータを受信 | そのデータを`.part`・受信済み状態へ反映しない |
| `AC-SESSION-002` | 有効metadata A採用後に別sessionの有効metadata Bを受信 | Aを固定し、Bへ切り替えない |
| `AC-SEND-001` | 2チャンクのファイルで `all` | metadata, metadata, data0, data1の順で同一session IDを使用 |
| `AC-SEND-002` | 送信初期化後に入力ファイルサイズを変更 | 送信エラーになる |
| `AC-SEND-003` | CLIで範囲外sequenceを入力後 `quit` | 送信せずエラーを表示し、CLIは継続して正常終了できる |
| `AC-RECV-001` | 全データを順不同で受信 | 元と同じbyte列になり検証後に正常完了 |
| `AC-RECV-002` | 0 byteやチャンク境界サイズを転送 | 正確なサイズ・内容で完成する |
| `AC-RECV-003` | 期待値と異なる最終payload長を受信 | 当該packetを破棄し、正しいpacketで後続完了できる |
| `AC-RECV-004` | 一部のみ受信して `missing` | 未受信番号を最大1000件範囲で表示する |
| `AC-RECV-005` | 同一sequenceを異なる内容で重複受信 | 最初に正常書き込みした内容を維持し、後続を無視する |
| `AC-HASH-001` | 正しいデータをすべて受信 | size/SHA-256一致と置換後に `receive completed` |
| `AC-HASH-002` | metadataのSHA-256と異なる完成データを受信 | 最終出力を変更せず`.part`を残し、exit 1 |
| `AC-FILE-001` | 既存最終出力がある状態で転送を開始 | 受信・検証中は既存最終出力を維持する |
| `AC-FILE-002` | 検証に成功する | `.part`を最終出力へ置換し、`.part`は残らない |
| `AC-FILE-003` | 制御文字を含む送信元名を受信 | 保存先には使わず、表示では制御文字をエスケープする |
| `AC-ERR-001` | CLI受信で`.part`の出力先を開けない | `receive error:` を表示しexit 1 |
| `AC-ERR-002` | 受信側stdin読み取りが失敗 | `input error:` を表示しexit 1 |
| `AC-LIFE-001` | UDP待受中に `quit` 相当の停止 | 受信待ちを解除し正常終了する |

---

## 10. 仕様とテストの対応

| 受入条件 | 主なテスト |
| --- | --- |
| `AC-ENV-001` | GitHub Actions Java 8 / Java 25 `mvn verify` |
| `AC-CLI-001` | `UdpFileTransferExecTest.sendUsesDefaults` |
| `AC-CLI-002` | `recvRejectsSendOnlyOptions`, `sendRejectsReceiveOnlyOptions`, `malformedArgumentsAreRejected`, `invalidRangesAreRejected`, `oldTerminologyOptionsAreRejected` |
| `AC-LIMIT-001` | `receiveUsesDefaultMaxChunkCount`, `receiveAcceptsMaxChunkCountOption` |
| `AC-LIMIT-002` | `UdpFileReceiverTest.rejectsChunkCountAboveConfiguredLimitWithoutTouchingFiles` |
| `AC-WIRE-001` | `UdpWireFormatTest.headerEncodingUsesExactBytes`, `headerDecoderReturnsSequenceAndSession` |
| `AC-WIRE-002` | `UdpWireFormatTest.metadataCodecUsesExactBytesAndFields` |
| `AC-WIRE-003` | `UdpWireFormatTest.metadataDecoderRejectsInconsistentAndInvalidFields`, `UdpFileReceiverTest.rejectsMalformedAndOversizedPacketsThenContinues` |
| `AC-SESSION-001` | `UdpFileReceiverTest.ignoresPacketsFromAnotherSession` |
| `AC-SESSION-002` | `UdpFileReceiverTest.keepsFirstAcceptedMetadataForSession` |
| `AC-SEND-001` | `UdpFileSenderTest.sendAllUsesOneSessionAndCurrentWireFormat` |
| `AC-SEND-002` | `UdpFileSenderTest.rejectsSourceSizeChangeDuringTransfer` |
| `AC-SEND-003` | `UdpFileTransferExecTest.invalidSendSequenceKeepsCommandLoopRunning` |
| `AC-RECV-001` | `UdpFileReceiverTest.reconstructsOutOfOrderFileAndVerifiesHash` |
| `AC-RECV-002` | `UdpFileReceiverTest.transfersFileSizeBoundaries` |
| `AC-RECV-003` | `UdpFileReceiverTest.rejectsWrongPayloadLengthThenAcceptsCorrectPacket` |
| `AC-RECV-004` | `UdpFileReceiverTest.missingCommandIsPagedAndValidatesStart` |
| `AC-RECV-005` | `UdpFileReceiverTest.keepsFirstSuccessfulChunkForDuplicateSequence` |
| `AC-HASH-001` | 正常受信テスト群 |
| `AC-HASH-002` | `UdpFileReceiverTest.failsWhenCompletedFileHashDoesNotMatchMetadata`, `UdpFileTransferExecTest.receiveHashMismatchReturnsOne` |
| `AC-FILE-001..002` | `UdpFileReceiverTest.replacesExistingOutputOnlyAfterValidation` |
| `AC-FILE-003` | `UdpFileReceiverTest.escapesControlCharactersInSourceFileName` |
| `AC-ERR-001` | `UdpFileReceiverTest.propagatesFailureWhenPartCannotBeOpened`, `UdpFileTransferExecTest.receiveOutputOpenFailureReturnsOne` |
| `AC-ERR-002` | `UdpFileTransferExecTest.receiveInputFailureReturnsOne` |
| `AC-LIFE-001` | `UdpFileReceiverTest.closeStopsReceiverWaitingForMetadata` |
