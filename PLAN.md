# Android SSH Client for Claude Code - 開発プラン

## 目的

自分のVPS（Debian, ポート31244）にSSH接続し、Claude Code をストレスなく操作できる Android SSH クライアントを作る。既存アプリの鍵管理や日本語入力の不満を解消する。

## 技術構成

### ターミナル描画
- **Termux の terminal-emulator / terminal-view ライブラリ**を利用
  - https://github.com/termux/termux-app の `terminal-emulator` モジュール（純Java、UIなし）
  - https://github.com/termux/termux-app の `terminal-view` モジュール（Android View）
  - **git submodule で取り込む**（ソースコピーではなく）
  - ライセンス: GPLv3（個人利用のため問題なし）
- xterm-256color 互換、Claude Code のTUI表示を正しく描画することが最優先

### SSH接続
- **sshlib**（ConnectBot由来のSSHライブラリ）
  - https://github.com/connectbot/sshlib
  - Ed25519, ECDSA, RSA 鍵対応
  - ポートフォワーディング対応
  - ライセンス: BSD-3-Clause
  - Maven Central: `org.connectbot:sshlib`

#### SSHライブラリ選定理由
他の候補も調査した上で sshlib を採用:
- **sshj** (GitHub ★2.6k, Apache 2.0): 人気は高いが、Android 14 で X25519 アルゴリズムエラーが報告されている。BouncyCastle プロバイダの扱いで Android 固有のワークアラウンドが必要。
- **Apache MINA SSHD** (Apache 2.0): 高機能だが、公式に Android サポートを「目標外」と明記。設定が煩雑で依存も重い。
- **sshlib** を選んだ理由: Android 専用設計で10年以上の実績。余計なワークアラウンド不要。ConnectBot で日常的にテストされている。

#### 将来の選択肢メモ
ConnectBot が `termlib`（libvterm + JNI + Kotlin/Compose, Apache 2.0）を開発中。
Maven Central に公開済み（2025/12）だがまだ star 10 程度で成熟度は未知数。
terminal-view に不満が出た場合の乗り換え先として注視する。

### 言語・ビルド
- Kotlin（新規コードはすべてKotlin）
- Minimum SDK: 34 (Android 14)
- Target SDK: 36 (Android 16)
- Gradle (Kotlin DSL)
- Jetpack Compose は使わない（terminal-view が従来のView系のため、Activity + ViewBinding で構成）

### 認証
- **鍵認証のみ**（パスワード認証は実装しない）
- Ed25519 を優先、ECDSA / RSA もサポート

## アーキテクチャ

```
┌─────────────────────────────────────────┐
│              UI Layer                   │
│  ┌──────────┐  ┌─────────────────────┐  │
│  │ 接続管理  │  │  TerminalView       │  │
│  │ 画面     │  │  (Termux由来)        │  │
│  └──────────┘  └─────────────────────┘  │
├─────────────────────────────────────────┤
│           Session Layer                 │
│  ┌──────────────────────────────────┐   │
│  │  SshSession                      │   │
│  │  - 接続/切断管理                  │   │
│  │  - 鍵認証                        │   │
│  │  - PTYチャネル                    │   │
│  │  - TerminalEmulator との橋渡し    │   │
│  └──────────────────────────────────┘   │
├─────────────────────────────────────────┤
│           Core Layer                    │
│  ┌────────────┐  ┌─────────────────┐   │
│  │ sshlib     │  │ terminal-       │   │
│  │ (SSH通信)  │  │ emulator        │   │
│  └────────────┘  │ (エスケープ処理) │   │
│                  └─────────────────┘   │
├─────────────────────────────────────────┤
│           Data Layer                    │
│  ┌────────────────┐  ┌──────────────┐  │
│  │ 接続プロファイル │  │ 鍵ストレージ  │  │
│  │ (Room DB)      │  │ (AndroidKS)  │  │
│  └────────────────┘  └──────────────┘  │
└─────────────────────────────────────────┘
```

## 機能スコープ

### Phase 1: 最小限動くもの
- [ ] プロジェクトセットアップ（Gradle, 依存関係）
- [ ] Termux termux-app リポジトリを git submodule として追加
- [ ] terminal-emulator / terminal-view モジュールをビルドに組み込み
- [ ] sshlib で SSH 接続（ホスト・ポート・ユーザーを直書き）
- [ ] Ed25519 鍵ペアの生成、アプリ内保存
- [ ] 鍵認証による SSH 接続
- [ ] PTY チャネルと TerminalEmulator の接続
- [ ] 画面にターミナル表示、キー入力が送れる状態
- [ ] 動作確認: VPS に接続して `claude` コマンドを起動、TUI が表示される

### Phase 2: 鍵管理の強化
- [ ] Android Keystore への秘密鍵保存
- [ ] 公開鍵のエクスポート（クリップボードコピー / 共有）
- [ ] 既存秘密鍵のインポート（ファイル選択）
- [ ] 複数鍵の管理UI
- [ ] 鍵のバックアップ/リストア手段

### Phase 3: 接続プロファイル
- [ ] Room DB による接続先の保存（ホスト, ポート, ユーザー, 使用鍵）
- [ ] 接続先一覧画面
- [ ] ワンタップ接続
- [ ] デフォルト接続先の設定（アプリ起動→即接続）

### Phase 4: 日本語入力改善
- [ ] IME composing text の表示対応
- [ ] 変換確定テキストの正確な入力
- [ ] wcwidth による全角文字幅計算の修正
- [ ] カーソル位置ずれの修正
- [ ] 日本語入力時のターミナル再描画

### Phase 5: 使い勝手の改善
- [ ] Ctrl / Esc / Tab のソフトキーバー（画面下部に追加キー行）
- [ ] フォントサイズ変更（ピンチズーム）
- [ ] 画面回転対応
- [ ] セッション切断時の自動再接続
- [ ] 通知によるバックグラウンドセッション維持（Foreground Service）

## 依存ライブラリのソース取得方針

### terminal-emulator / terminal-view
Termux のリポジトリを git submodule として取り込む。maven 公開されていないため、ソースからビルドする。

```bash
git submodule add https://github.com/termux/termux-app.git vendor/termux-app
```

settings.gradle.kts で必要なモジュールだけ include:
```kotlin
include(":vendor:termux-app:terminal-emulator")
include(":vendor:termux-app:terminal-view")
```

※ Termux リポジトリの構成によっては gradle 設定の調整が必要。
submodule は特定のタグ/コミットに固定して安定性を確保する。

### sshlib
Maven Central から依存:
```kotlin
implementation("org.connectbot:sshlib:2.2.22") // バージョンは最新を確認
```

## ディレクトリ構成

```
project-root/
├── app/
│   └── src/main/
│       ├── java/org/hogel/droidconnect/
│       │   ├── MainActivity.kt
│       │   ├── ui/
│       │   │   └── TerminalActivity.kt
│       │   └── ssh/
│       │       ├── SshSession.kt
│       │       └── SshKeyManager.kt
│       └── res/
├── vendor/
│   └── termux-app/              (git submodule)
│       ├── terminal-emulator/
│       └── terminal-view/
├── settings.gradle.kts
├── build.gradle.kts
└── .gitmodules
```

## 開発の進め方

1. Phase 1 を最優先で動かす。鍵認証でSSH接続し、Claude Code のTUIが表示されれば最低限使える
2. 実際に毎日使いながら Phase 2〜5 を不満駆動で進める
3. 各Phase内でもタスクの優先度は使いながら調整する

## 注意事項

- Termux の terminal-emulator/terminal-view は GPLv3。自プロジェクトもGPLv3とする（個人利用なので配布予定なし）
- sshlib は BSD-3-Clause なので GPL との組み合わせは問題なし
- Android Keystore は端末リセットで鍵が消えるため、鍵のバックアップ手段も Phase 2 で検討する
- Phase 1 では接続先情報はハードコードでよい。プロファイル管理は Phase 3 で実装する
