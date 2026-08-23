# UdpFileTransfer 内部設計書

## 1. はじめに

本書は [`EXTERNAL_DESIGN.md`](EXTERNAL_DESIGN.md) の外部仕様を実現するために維持する内部構造と処理方式を定義します。クラス責務、送受信処理、状態、並行処理、ファイル・UDP入出力、検証、エラー、リソース管理を対象とします。

補助メソッド名やローカル変数など、設計上の意味を持たない実装詳細は固定しません。

---

## 2. 設計方針と全体構成

UdpFileTransfer は単一 Maven モジュールの Java 8 CLI です。製品実行時依存は Java SE 標準ライブラリだけとします。

主な方針は次のとおりです。

1. UDP転送経路は送信側から受信側への一方向だけにする。
2. 各転送に128 bitランダムセッションIDを付け、採用済み転送へ別転送のパケットを混在させない。
3. メタデータにファイルサイズとSHA-256を持たせ、チャンク条件と受信結果を検証する。
4. 受信側はローカルの最大チャンク数をメタデータ採用前に検証し、受信状態とデータグラム数を有限に保つ。
5. シーケンス番号をファイル位置として扱い、順不同・重複を直接処理する。
6. データは最終出力ではなく `.part` へ書き、サイズとSHA-256の検証後に最終出力へ置換する。
7. 不正なネットワーク入力はパケット単位で隔離し、ローカルI/Oエラーとは分離する。
8. 並行処理は受信中の対話入力に必要な範囲だけに限定する。

### 2.1 全体構成

```text
UdpFileTransferExec
   |-- 引数解析 / 対話入力 / 終了コード
   |
   +-- send --> UdpFileSender --> UdpWireFormat --> UDP
   |               |                 |
   |               +--> FileHash     +--> UFT1 codec
   |               +--> input file
   |
   +-- recv --> UdpFileReceiver --> UdpWireFormat <-- UDP
                   |                 |
                   +--> FileHash     +--> UFT1 codec
                   +--> <output>.part --verify--> <output>
```

**INT-CMP-001 — 責務分離**

主要責務はCLI、送信、受信、通信形式、ファイルハッシュの5つに分離します。仕様上の必要性がない抽象化層やDIコンテナは追加しません。

---

## 3. クラス設計

### 3.1 `UdpFileTransferExec`

**INT-CMP-002 — CLI責務**

- `main` と `run` の分離
- コマンドライン解析と値域検証
- 既定値適用
- 標準入出力
- 送受信オブジェクトの起動
- 対話コマンド
- 受信入力スレッド
- 例外から終了コードへの変換

`System.exit` は `main` だけが呼びます。

CLI のチャンク関連オプションは次に統一します。

- 送信側 `-c <chunk-size>`
- 受信側 `-n <max-chunks>`

旧 `-s` / `-m` の互換処理は持ちません。

### 3.2 `UdpFileSender`

**INT-CMP-003 — 送信責務**

1つの入力ファイルについて、初期ファイルサイズ、SHA-256、ランダムセッションID、メタデータ、送信先、ファイルチャネル、UDPチャネルを所有します。

受信状態、受信側の `maxChunkCount`、ACK/NACK、自動再送状態は持ちません。最大チャンク数は受信側ローカル方針であり、送信側は相手の設定値を仮定しません。

### 3.3 `UdpFileReceiver`

**INT-CMP-004 — 受信責務**

- UDP待受
- 最大チャンク数を含むメタデータ採用判断
- 最初に採用したメタデータによるセッション固定
- 採用セッションIDによるパケット選別
- `<output>.part` の作成・位置指定書き込み
- 受信済み番号管理
- 未受信番号表示
- `.part` のファイルサイズとSHA-256による完成検証
- 検証成功後の最終出力への置換
- `close()` による待受解除

### 3.4 `UdpWireFormat`

**INT-CMP-005 — 通信形式責務**

UFT1の定数、共通ヘッダー、メタデータの符号化・復号、厳密UTF-8検証、項目間整合性、シーケンスごとの期待データ長を一か所で管理します。

主要定数は次のとおりです。

```text
MAX_PACKET_SIZE               = 10000
SESSION_ID_SIZE               = 16
SHA256_SIZE                   = 32
DATA_HEADER_SIZE              = 28
METADATA_FIELDS_SIZE          = 56
METADATA_HEADER_SIZE          = 84
MAX_DATA_PAYLOAD_SIZE         = 9972
MAX_METADATA_FILE_NAME_SIZE   = 1024
```

`maxChunkCount` は wire format の制約ではなく受信側のローカルポリシーなので、`UdpWireFormat` の固定上限にはしません。

### 3.5 `FileHash`

**INT-CMP-006 — SHA-256責務**

ファイル全体をストリームで読み、Java標準 `MessageDigest` のSHA-256を計算します。送信側と受信側で同じ実装を使用し、ファイル全体をメモリへ載せません。

---

## 4. UFT1 内部処理

### 4.1 共通ヘッダー

`writeHeader` / `readHeader` 相当の処理は `UFT1`、sequence、16 byte session IDを扱います。受信側はヘッダーを解析した後、採用メタデータのsession IDと受信パケットのsession IDを比較します。

**INT-WIRE-001 — セッションIDを全パケットで共有**

1つの `UdpFileSender` が生成するメタデータとデータは、初期化時に生成した同一の16 byte session IDを使用します。

セッションIDは採用後のパケット分離に使うだけであり、送信者認証や事前の期待セッション指定には使いません。

### 4.2 メタデータ

`Metadata` は次を保持します。

```text
chunkSizeBytes
chunkCount
fileSizeBytes
sourceFileName
sessionId[16]
sha256[32]
fileNameBytes
```

`chunkCount` は任意入力として信頼せず、次の値と一致する場合だけ通信形式として有効とします。

```text
fileSize == 0 : 0
fileSize > 0  : 1 + (fileSize - 1) / chunkSize
```

この整合性検証とは別に、受信側は `chunkCount <= maxChunkCount` をローカル受入条件として確認します。

**INT-WIRE-002 — UTF-8を厳密に検証**

外部から受信したファイル名は `CharsetDecoder` の malformed / unmappable を `REPORT` として復号します。置換文字による黙示的な受理は行いません。

### 4.3 データ長

シーケンス `n` の期待長は次のように求めます。

```text
offset = chunkSize * n
expectedLength = min(chunkSize, fileSize - offset)
```

**INT-WIRE-003 — 最終データも期待長と完全一致させる**

最終シーケンスを含め、受信payload長は期待長と完全一致する場合だけ受理します。

### 4.4 UDPデータグラム上限

受信バッファは `MAX_PACKET_SIZE + 1` byte確保します。受信結果が10000 bytesを超えた場合は、内容解析前に破棄します。

**INT-WIRE-004 — 切り詰められた巨大データグラムを正常扱いしない**

バッファを10000 bytesちょうどにしないことで、10000 bytesを超えるデータグラムを少なくとも1 byte分観測し、サイズ超過を検出します。それ以上に大きいデータグラムがバッファで切り詰められても、観測長が10001 bytesになるため超過判定できます。

---

## 5. 送信処理

### 5.1 初期化

送信オブジェクト生成時に次を同期的に行います。

1. ファイルサイズ取得
2. ファイル名取得
3. SHA-256計算
4. SHA-256計算後にファイルサイズを再確認
5. `SecureRandom` で16 byte session ID生成
6. メタデータ生成
7. 送信先名前解決
8. 入力 `FileChannel` と送信 `DatagramChannel` を開く

**INT-SEND-001 — 初期化完了後にだけコマンドを受け付ける**

途中まで初期化された送信オブジェクトをCLIへ渡しません。

### 5.2 データ送信

各シーケンス送信前後に現在のファイルサイズが初期サイズと一致することを確認します。期待payload長まで位置指定読み取りし、初期ファイルサイズより後の拡張データを送らないよう `ByteBuffer.limit` を設定します。

同じサイズのまま内容が変更された場合は送信中に完全検出できないため、受信完了時のSHA-256で不一致になります。

### 5.3 一括送信

`sendAll()` はメタデータ2回、その後データ0からN-1の順に同期送信します。個別再送も同じメタデータ・session IDに基づくパケットを生成します。

---

## 6. 受信処理

### 6.1 状態

```text
WAIT_METADATA
    |
    | 有効かつ chunkCount <= maxChunkCount の最初のmetadata
    v
RECEIVING(sessionId fixed, write <output>.part)
    |
    | all sequences written
    v
VERIFYING_PART
    |-- file size
    |-- SHA-256
    v
PUBLISHING
    |-- move/replace <output>.part -> <output>
    v
COMPLETED
```

これと独立して `closed` を持ち、待受を明示停止できます。

### 6.2 メタデータ採用

**INT-RECV-001 — ローカル上限を受信途中ファイル作成前に確認**

通信形式として有効でも `chunkCount > maxChunkCount` のメタデータは採用しません。この時点では最終出力も `.part` も開きません。

最初に受理可能なメタデータを返した時点で、その `Metadata` オブジェクトを受信処理全体の基準として固定します。以後の受信ループでは、同じ `sessionId` のデータパケットだけを処理します。後着のメタデータへ状態遷移を戻しません。

採用したメタデータの `chunkCount` を公開して `missing` 処理が参照できるようにします。

### 6.3 受信途中ファイル

採用メタデータ確定後に、CLIで指定された最終出力文字列へ `.part` を付加したパスを `WRITE + CREATE + TRUNCATE_EXISTING` で開きます。最終出力はこの時点では開きません。

同一の `recv` 再実行で有効メタデータを採用すると、残っている `.part` は最初から再構成するため切り詰めます。

### 6.4 データ受信

データは次の順で判定します。

1. UDPデータグラム長
2. 共通ヘッダー
3. session ID一致
4. sequence範囲
5. 未受信であること
6. payload長が期待値と一致
7. `.part` の `chunkSize * sequence` へ全byte書き込み
8. 受信済み集合へ追加
9. 受信件数を加算

**INT-RECV-002 — 書き込み後にだけ受信済みとする**

ファイル書き込みに失敗したデータを受信済みへ進めません。そのため、同じシーケンスについて最初に正常書き込みが完了した内容が確定し、後続の重複パケットは書き込み前に破棄されます。

### 6.5 完成検証と公開

全シーケンス受信後に `.part` の `FileChannel` を閉じ、実ファイルサイズを確認してからSHA-256を再計算します。

**INT-RECV-003 — 検証成功後にだけ最終出力へ置換**

ファイルサイズまたはSHA-256が一致しない場合は `IOException` としてCLIへ伝播し、`.part` を残します。既存の最終出力には触れません。

両検証に成功した場合、`Files.move(..., REPLACE_EXISTING)` で `.part` を最終出力へ置換します。置換成功後にだけ `receive completed` を出力します。

### 6.6 送信元ファイル名表示

保存先には使いません。表示前にISO制御文字とUnicode `FORMAT` 文字を `\uXXXX` へ変換し、改行や端末制御が利用者表示へ注入されないようにします。

---

## 7. 受信状態とリソース上限

### 7.1 受信済みシーケンス

`receivedSequences` は正常書き込み済みのシーケンスだけを保持する並行アクセス可能な集合です。未受信番号を全件事前生成しません。

`missing` は `0..chunkCount-1` と受信済み集合との差分を、開始位置から最大1,000件の範囲で走査して表示します。

### 7.2 最大チャンク数

受信側の `maxChunkCount` はCLIから渡され、既定値は300,000です。これは通信形式上の上限ではなく、受信側が受け入れる転送のリソース上限です。

**INT-LIMIT-001 — 状態量と受信回数をチャンク数で制限**

ファイルサイズだけを制限しても、チャンクサイズが小さければ非常に大きな `chunkCount` を作れます。受信済み集合の要素数と必要データグラム数は `chunkCount` に比例するため、受信側は `chunkCount` 自体を採用前に制限します。

送信側には同じ上限を設けません。受信側が `-n` でより大きな転送を明示的に許容できるようにし、送信側と受信側のローカル設定を暗黙に結合しません。

---

## 8. 並行処理

送信モードは単一スレッドです。

受信モードでは、呼び出し元スレッドがUDP受信を行い、標準入力だけをデーモンスレッドで読みます。

共有状態は次です。

- `receivedSequences`: 並行アクセス可能な集合
- `chunkCount`, `receivedSequenceCount`, `closed`: 可視性を確保
- `receiveChannel`: `close()` と受信処理の小さな同期区間で管理

**INT-CONC-001 — 受信状態の更新は受信処理だけが行う**

入力スレッドは `missing` 参照と `close()` だけを行い、ファイル再構成状態を変更しません。

**INT-CONC-002 — チャネルcloseで待受解除**

ブロッキングUDP受信は `DatagramChannel.close()` で解除します。明示停止に伴う受信例外は致命的エラーにしません。

入力スレッドで `IOException` が発生した場合は `AtomicReference` 相当で呼び出し元へ引き渡し、受信をcloseして終了コード1へ変換します。

---

## 9. エラー処理

内部では次を分離します。

1. CLI引数誤り → exit 2
2. 対話入力誤り → 表示して継続
3. 不正・別セッション・上限超過UDP入力 → 当該データを破棄して継続
4. ファイル/ネットワークI/O失敗 → `IOException` でCLIへ伝播、exit 1
5. 完成サイズまたはSHA-256不一致 → `.part` を残して `IOException`、exit 1
6. 最終出力への置換失敗 → `.part` を残して `IOException`、exit 1
7. 利用者の明示停止 → 正常終了

**INT-ERR-001 — 下位層はプロセス終了を所有しない**

`UdpFileSender`、`UdpFileReceiver`、`UdpWireFormat`、`FileHash` から `System.exit` を呼びません。

---

## 10. リソース管理

### 10.1 送信側

`UdpFileSender` が入力 `FileChannel` と送信 `DatagramChannel` を所有し、`close()` で両方を閉じます。

### 10.2 受信側

1回の `receive()` が受信 `DatagramChannel` と、その転送の `.part` 用 `FileChannel` を所有します。`.part` は採用可能なメタデータ受信後にだけ開きます。

完成検証時は `.part` の出力チャネルを閉じた後にファイルサイズとSHA-256を読み直します。成功時だけ最終出力へ置換します。

中止、サイズ不一致、SHA-256不一致では `.part` を自動削除しません。これは失敗原因の確認を可能にし、最終出力を未検証データで上書きしないためです。次回受信でメタデータを採用した時点で `.part` を切り詰めます。

---

## 11. 実行環境と依存関係

**INT-ENV-001 — Java SE標準ライブラリのみ**

製品コードはJava 8の標準APIだけを使用します。暗号ハッシュは `java.security.MessageDigest`、session IDは `java.security.SecureRandom`、最終出力への置換は `java.nio.file.Files.move` を使用します。

ビルドの正本はMavenです。具体手順は [`OPERATIONS.md`](OPERATIONS.md) に従います。

---

## 12. 内部不変条件

- UFT1の1パケットは1 UDPデータグラムで完結する。
- 1送信オブジェクト中の全パケットは同じsession IDを使う。
- `chunkCount` は `fileSize` と `chunkSize` から導出した値と一致する。
- データpayload長はsequenceごとの期待値と完全一致する。
- 受信側は最初に採用したmetadataをその受信処理中に変更しない。
- 受信側は採用session ID以外のパケットを受信状態へ反映しない。
- `chunkCount > maxChunkCount` のmetadataでは最終出力も`.part`も変更しない。
- ファイル書き込み完了後にだけsequenceを受信済みとする。
- 受信中のデータは`.part`にだけ書き込み、検証前の最終出力を変更しない。
- `receive completed` はサイズ、SHA-256、最終出力への置換がすべて成功した後にだけ表示する。
- 外部由来ファイル名はローカル保存先へ使用しない。
- session IDとSHA-256を認証機能として扱わない。

---

## 13. 外部仕様・受入条件・実装の対応

| 外部仕様 / 受入条件 | 主な内部設計 | 実装 |
| --- | --- | --- |
| `EXT-SESSION-001..002`, `AC-SESSION-001..002` | `INT-WIRE-001`, `INT-RECV-001..002` | `UdpWireFormat`, `UdpFileReceiver` |
| `EXT-HASH-001`, `AC-HASH-001..002` | `INT-CMP-006`, `INT-RECV-003` | `FileHash`, `UdpFileReceiver` |
| `EXT-LIMIT-001`, `AC-LIMIT-001..002` | `INT-RECV-001`, `INT-LIMIT-001` | CLI options, `UdpFileReceiver` |
| `EXT-WIRE-001..007` | `INT-WIRE-001..004` | `UdpWireFormat`, receiver |
| `EXT-SEND-001..003` | `INT-SEND-001`, 5.2..5.3 | `UdpFileSender` |
| `EXT-RECV-001..003`, `AC-RECV-*` | `INT-RECV-001..003` | `UdpFileReceiver` |
| `EXT-FILE-001`, `AC-FILE-001..003` | 6.3..6.6, 10.2 | `UdpFileReceiver` |
| `EXT-CLI-*` | `INT-CMP-002` | `UdpFileTransferExec` |
| `EXT-SEC-001` | `INT-WIRE-001`, 12 | wire / receiver / docs |
