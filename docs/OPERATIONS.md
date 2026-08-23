# 運用手順

この文書は UdpFileTransfer のビルド、テスト、配布物生成の正本です。

## 前提

- JDK 8 以上
- Maven 3.9 以上

## 検証

```bash
mvn -B -ntp verify
```

コンパイル、テスト、実行可能JAR、配布ZIPの生成まで実行します。

## 生成物

```text
target/UdpFileTransfer.jar
target/UdpFileTransfer-<version>.zip
```

`UdpFileTransfer.jar` の `Main-Class` は `com.github.rhktrth.udpfiletransfer.UdpFileTransferExec` です。

配布ZIPには次を含めます。

- `UdpFileTransfer.jar`
- `README.md`
- `LICENSE.txt`

配布ZIPには `docs/` を含めません。そのため、配布される `README.md` から設計文書へ移動するリンクは GitHub 上の絶対URLを使用します。

生成物は `target/` にだけ置き、Git管理しません。

## CI

`.github/workflows/test.yml` は Pull Request と `main` push で Java 8 / Java 25 の `mvn -B -ntp verify` を実行します。ソースコードだけでなく、仕様書・運用文書だけの変更でも同じ検証を実行します。

同じrefで新しい実行が開始された場合は古い実行を取り消し、jobにはtimeoutを設定します。UDP integration testはloopbackのみを使用し、CIから外部ホストへ接続しません。

GitHub Actions で利用する外部actionは、可変のmajor tagではなく完全なcommit SHAで固定します。可読性のため、固定したSHAの行には対応versionをコメントとして記載します。更新はDependabot等のPRで差分を確認して行います。

## バージョン

通常開発では `pom.xml` のproject versionを使用します。

正式ReleaseのversionはGitHub Releaseのtag `vX.Y.Z` を正本とします。Release workflowはtagから `X.Y.Z` を取り出し、checkoutした作業ツリーの `pom.xml` に一時的に反映してbuildします。この変更はrepositoryへcommitしません。

## GitHub Release

正式な配布先はGitHub Releasesです。

`.github/workflows/release.yml` は次の場合に既存Releaseへ配布物をbuildしてuploadします。

- GitHub Releaseが `published` になったとき
- `workflow_dispatch` で既存Release tagを指定したとき

workflowでは次を確認します。

- tagが `vX.Y.Z` 形式
- tagのcommitが既定branch `main` の履歴上
- tagからversionを決定して一時的にPOMへ反映
- Java 8で `mvn -B -ntp verify` が成功
- 配布ZIPのSHA-256 checksumを生成

公開asset:

- `UdpFileTransfer-<version>.zip`
- `UdpFileTransfer-<version>.zip.sha256`

通常のRelease手順:

1. 対象commitが `main` に入り、CIが成功していることを確認する。
2. Releases画面で `vX.Y.Z` tagを指定してReleaseをpublishする。
3. `Release` workflowの成功を確認する。
4. ZIPとSHA-256 checksumが揃っていることを確認する。

GitHub PackagesはMaven dependencyとして配布する必要が生じた場合だけ使用します。
