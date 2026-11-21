# セキュリティ暫定措置台帳

## Android EmulatorシェルのExpat修正（2026-11-21、[Draft PR #2](https://github.com/Natsu-B/muse_alert/pull/2)）

- **適用状態:** PRがdefault branchにマージされるまで未適用。Androidアプリ本体の依存を更新したものではない。
- **変更前:** `flake.lock`のnixpkgs `177d0540942fb205af6993b1d5fa0b57799e09f8`で`pkgs.expat`は2.9.0。`flake.nix`の`devShells.x86_64-linux.emulator.NIX_LD_LIBRARY_PATH`にその`expat`を含める。
- **変更後:** Emulatorシェルの参照だけを、固定ハッシュのExpat 2.10.0に上流[PR #1492](https://github.com/libexpat/libexpat/pull/1492)の回帰テスト付きパッチを適用した`secureExpat`へ差し替える。`patches/expat-cve-2026-124317.patch`は上流差分のパス接頭辞のみをソースアーカイブの構造に合わせて調整。nixpkgs入力・他の開発シェル・Androidアプリの依存は据え置く。
- **根拠:** [Expat 2.10.0上流リリース](https://github.com/libexpat/libexpat/releases/tag/R_2_10_0)はCVE-2026-113351、CVE-2026-115506、CVE-2026-117610、CVE-2026-118370を修正するが、[CVE-2026-124317](https://ubuntu.com/security/CVE-2026-124317)（中、未知エンコーディング処理時の境界外読み取り）は2.10.0にも残る。後者を上流PR #1492の修正で補う。
- **悪用条件・残存リスク:** Emulator本体または補助バイナリが当該`libexpat`を実際にロードし、攻撃者が細工したXMLを処理させられる必要がある。実行時リンク・入力経路は未確認。他の推移的依存やAndroid APKのExpat安全性を保証しない。
- **検証:** 上流タグ2.10.0のソースに対するパッチ5hunkの文脈一致を確認。Nix評価、`nix develop .#emulator`、ビルド・Expat回帰テスト、実機Emulator起動は未実施。完了までDraft。
- **解除条件:** 固定nixpkgs入力の`pkgs.expat`が5件のCVEを修正したリリース／バックポートを含み、`NIX_LD_LIBRARY_PATH`と実リンク、Emulator起動の互換性が検証されたとき。別の最小復旧PRで`secureExpat`を削除して元の`expat`へ戻す。
- **元に戻す差分:** `flake.nix`で`secureExpat`のlet定義を削除し、Emulatorシェル配列の`secureExpat`を`expat`に戻す。パッチファイルは未使用になった場合に限り削除する。
