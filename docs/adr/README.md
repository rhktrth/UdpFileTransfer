# アーキテクチャ決定記録

`docs/adr/` には UdpFileTransfer の**現在有効な重要な設計判断と理由**だけを置きます。変更履歴の台帳にはしません。

判断が変わった場合は、現在の設計を最も簡潔に表すよう既存ADRを編集・統合・削除します。過去経緯はGit履歴、Issue、PRを参照します。

現在の外部仕様は [`../EXTERNAL_DESIGN.md`](../EXTERNAL_DESIGN.md)、内部設計は [`../INTERNAL_DESIGN.md`](../INTERNAL_DESIGN.md) を正本とし、ADRは「なぜその設計を選ぶか」「どの前提が変われば見直すか」を扱います。

## 現在のADR

- [ADR-0001: Java 8 の小規模 CLI として実装を閉じる](0001-minimal-java8-cli.md)
- [ADR-0002: 転送ネットワークを片方向のまま保ち、信頼性回復を利用者へ委ねる](0002-one-way-transfer-and-operator-recovery.md)
- [ADR-0003: シーケンス番号を論理ファイル位置として扱い、順不同・重複受信を許容する](0003-sequence-addressed-idempotent-reconstruction.md)
- [ADR-0004: 検証済みメタデータで受信セッションを確立し、保存先は受信側が所有する](0004-receiver-session-and-trust-boundary.md)
- [ADR-0005: 並行処理を対話入力に限定し、プロセスのライフサイクルをCLIが所有する](0005-lifecycle-and-concurrency-ownership.md)
- [ADR-0006: UFT1にセッションID、ファイルサイズ、SHA-256を含め、検証後に出力を確定する](0006-session-size-and-sha256.md)
