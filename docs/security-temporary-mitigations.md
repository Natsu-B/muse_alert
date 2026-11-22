# セキュリティ暫定措置台帳

## Expat CVE-2026-134703 の限定バックポート（2026-11-22、[Draft PR #3](https://github.com/Natsu-B/muse_alert/pull/3)）

- **適用状態:** Draft PR #3 は未マージ。mainに適用済みと扱わない。既存のExpat CVE-2026-124317対策はmainにマージ済み。
- **対象・変更前:** Android Emulator開発シェルだけの`secureExpat`。Expat 2.10.0にCVE-2026-124317パッチを限定適用していたが、本CVEのUTF-16変換修正は含まれていなかった。
- **変更後:** `patches/expat-cve-2026-134703.patch` に[上流libexpatコミット702389c](https://github.com/libexpat/libexpat/commit/702389c818cb402f2423fa998fa11907d621959f)の差分を保持し、`flake.nix`の`secureExpat`のみに追加。全域overlayや他の入力更新は行わない。
- **根拠・重要度:** [CVE-2026-134703](https://nvd.nist.gov/vuln/detail/CVE-2026-134703)（中・暫定評価）。4バイトのUTF-16サロゲートペアが2バイトの残容量へコピーされると、不完全なUTF-16出力が生じ得る。修正元の[libexpat PR #1519](https://github.com/libexpat/libexpat/pull/1519)はPR作成時点では未マージ。
- **悪用条件・残存リスク:** XML入力が脆弱なUTF-16変換経路を通り、攻撃者が入力とバッファ境界を制御できる場合に影響し得る。対象アプリ/Emulatorへの入力到達性と実際の影響、その他のExpat利用パッケージ・バンドル版・静的リンクは未確認。
- **検証:** 上流タグ `R_2_10_0` で修正hunkの旧本文が一意に一致することを確認。Nix評価・ビルド・Expatテスト、Emulator実行時リンク・起動検証は未実施。 既存CVE-2026-124317のhunkとは別領域を変更するが、両パッチを順次適用したビルドは未検証。上流修正自体もレビュー中のため、検証が完了するまでDraft。
- **解除条件:** 本PRがmainへマージされた場合に限り、以後追跡する。固定nixpkgsへ本件と既存Expat修正がリリースまたはバックポートされ、バイナリキャッシュ・実リンク・実行時閉包・ビルド・互換性が確認できたら、この暫定対策だけを除く最小の復旧PRを作成する。新版・lock更新のみでは撤去しない。
- **元に戻す差分:** `flake.nix` の `secureExpat.patches` から `./patches/expat-cve-2026-134703.patch` の行だけを削除し、同パッチファイルを削除する。既存のCVE-2026-124317修正は維持する。

## Android EmulatorシェルのExpat修正（2026-11-21、[Draft PR #2](https://github.com/Natsu-B/muse_alert/pull/2)）

- **適用状態:** PRがdefault branchにマージされるまで未適用。Androidアプリ本体の依存を更新したものではない。
- **変更前:** `flake.lock`のnixpkgs `177d0540942fb205af6993b1d5fa0b57799e09f8`で`pkgs.expat`は2.9.0。`flake.nix`の`devShells.x86_64-linux.emulator.NIX_LD_LIBRARY_PATH`にその`expat`を含める。
- **変更後:** Emulatorシェルの参照だけを、固定ハッシュのExpat 2.10.0に上流[PR #1492](https://github.com/libexpat/libexpat/pull/1492)の回帰テスト付きパッチを適用した`secureExpat`へ差し替える。`patches/expat-cve-2026-124317.patch`は上流差分のパス接頭辞のみをソースアーカイブの構造に合わせて調整。nixpkgs入力・他の開発シェル・Androidアプリの依存は据え置く。
- **根拠:** [Expat 2.10.0上流リリース](https://github.com/libexpat/libexpat/releases/tag/R_2_10_0)はCVE-2026-113351、CVE-2026-115506、CVE-2026-117610、CVE-2026-118370を修正するが、[CVE-2026-124317](https://ubuntu.com/security/CVE-2026-124317)（中、未知エンコーディング処理時の境界外読み取り）は2.10.0にも残る。後者を上流PR #1492の修正で補う。
- **悪用条件・残存リスク:** Emulator本体または補助バイナリが当該`libexpat`を実際にロードし、攻撃者が細工したXMLを処理させられる必要がある。実行時リンク・入力経路は未確認。他の推移的依存やAndroid APKのExpat安全性を保証しない。
- **検証:** 上流タグ2.10.0のソースに対するパッチ5hunkの文脈一致を確認。Nix評価、`nix develop .#emulator`、ビルド・Expat回帰テスト、実機Emulator起動は未実施。完了までDraft。
- **解除条件:** 固定nixpkgs入力の`pkgs.expat`が5件のCVEを修正したリリース／バックポートを含み、`NIX_LD_LIBRARY_PATH`と実リンク、Emulator起動の互換性が検証されたとき。別の最小復旧PRで`secureExpat`を削除して元の`expat`へ戻す。
- **元に戻す差分:** `flake.nix`で`secureExpat`のlet定義を削除し、Emulatorシェル配列の`secureExpat`を`expat`に戻す。パッチファイルは未使用になった場合に限り削除する。
