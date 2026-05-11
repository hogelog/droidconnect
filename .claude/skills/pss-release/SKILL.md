---
name: pss-release
description: pocket-secure-shell のリリース作業（リリースノート PR → タグ push → 動作確認 → 次バージョンへの bump PR）を一気通貫で実行する。引数: 対象バージョン X.Y.Z（省略時は main の baseVersionName を使う）
---

# pocket-secure-shell リリーススキル

`hogelog/pocket-secure-shell` のリリースを一気通貫で実施する:

1. リリースノート PR を作成 → ユーザーがマージ
2. タグ push → `release.yml` 起動（main.yml で internal track にアップ済みの AAB を alpha に promote）
3. APK/AAB の attestation 検証
4. 次バージョンへの `baseVersionName` bump PR

**全工程 detached 必須**（feedback_dev_detached）。フォローアップで呼ばれた場合も attached/detached 判定を入れ直す（feedback_followup_redetach）。

## 引数

`X.Y.Z` 形式の対象バージョン。省略可。

省略時は main の `app/build.gradle.kts` の `val baseVersionName = "X.Y.Z"` を読み取り、その値を採用する。

## 前提チェック（着手前に必ず実行）

ズレている場合は実行を止めて `mcp__aka__send_message` でユーザー確認する。

1. **直近のリリース対象 PR が main マージ済みか**: 直近の PR (`gh pr list --repo hogelog/pocket-secure-shell --state merged --limit 3`) を確認し、リリースに含めるべき変更が main に入っているか
2. **baseVersionName と意図したバージョンの一致**: 省略時は main の `baseVersionName` をそのまま使うので問題ないが、引数指定の場合は main の `baseVersionName` と一致しているか確認
3. **同名タグが既に無いか**: `gh api /repos/hogelog/pocket-secure-shell/git/refs/tags/vX.Y.Z` が 200 ならタグ既存 → 中断

## 手順

### 1. ローカル clone（read-only host repo 回避）

タグ履歴が必要（前回タグ〜HEAD の差分を読むため）なので浅 clone にしない。

```bash
WORK=/tmp/pss-release-vX.Y.Z
rm -rf "$WORK"
git clone --branch main --single-branch https://github.com/hogelog/pocket-secure-shell.git "$WORK"
cd "$WORK" && git fetch --tags origin
```

### 2. baseVersionName 抽出（引数省略時）

```bash
VERSION=$(grep -oP 'val baseVersionName = "\K[0-9]+\.[0-9]+\.[0-9]+' "$WORK/app/build.gradle.kts")
```

### 3. リリースノート PR

タグを打つ commit に release notes ファイルが入っている必要があるため、**タグ push 前に必ず**マージする。

#### 3.1. 直前タグの特定と差分コミット取得

```bash
cd "$WORK"
PREV_TAG=$(git tag -l "v*" --sort=-v:refname | head -1)
if [ -n "$PREV_TAG" ]; then
  COMMITS=$(git log "$PREV_TAG..HEAD" --pretty=format:"- %s" --no-merges)
else
  COMMITS=$(git log --pretty=format:"- %s" --no-merges)
fi
```

#### 3.2. ドラフト生成

`$COMMITS` を Play Store 向けに要約する。

- **500 文字以内**（Play Store の whatsnew 上限）
- **英語**（public repo、ユーザー向け文面）
- 内部実装・リファクタリング・依存更新は除外、ユーザーに見える変更のみを残す
- 形式: 短い箇条書き（`- xxx` ×数行）。1〜2 段落の散文も可
- 「Bump baseVersionName to ...」のような meta commit は除外

#### 3.3. ユーザーレビュー

`mcp__aka__send_message` でドラフトを送り、`OK` / 編集指示を待つ。返事が来たら反映してもう一度送るか、`OK` なら次へ。

#### 3.4. リリースノート PR を作成

dev skill のワークフローで PR を作る:

- worktree branch: `release-notes-vX.Y.Z`
- ファイル: `app/src/main/play/release-notes/en-US/default.txt` を新規作成し、承認されたノートを書き込む
- commit: `Add Play release notes for vX.Y.Z`
- PR title: `Add Play release notes for vX.Y.Z`
- PR body: ノート本文をそのまま貼る（+ バージョン番号）
- `mcp__aka__github_create_pr` で head/base 明示（feedback_github_create_pr_explicit_head_base）
- マージはユーザー（feedback_no_ai_merge）

#### 3.5. マージ待ち

```bash
until [ "$(gh pr view <num> --repo hogelog/pocket-secure-shell --json state --jq .state)" = "MERGED" ]; do
  sleep 30
done
```

マージされたら clone を更新:

```bash
cd "$WORK" && git pull origin main
```

### 4. タグ push

```bash
cd "$WORK" && git tag "v$VERSION" && git push origin "v$VERSION"
```

`release.yml` が起動する。所要 ~4 分。

### 5. workflow 完了待ち

優先: `gh run watch <run-id> --repo hogelog/pocket-secure-shell --exit-status`

```bash
RUN_ID=$(gh run list --repo hogelog/pocket-secure-shell --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN_ID" --repo hogelog/pocket-secure-shell --exit-status
```

**注意**: `GH_TOKEN` は ~1 時間で期限切れる。長時間待ち中に 401 になったら anonymous curl で polling に切り替える:

```bash
until curl -sL "https://api.github.com/repos/hogelog/pocket-secure-shell/releases/tags/v$VERSION" | jq -e '.assets | length > 0' >/dev/null 2>&1; do sleep 20; done
```

### 6. 動作確認（APK + AAB）

```bash
VERIFY=/tmp/pss-verify-v$VERSION
rm -rf "$VERIFY" && mkdir -p "$VERIFY" && cd "$VERIFY"
curl -sLO "https://github.com/hogelog/pocket-secure-shell/releases/download/v$VERSION/pocketsecureshell-v$VERSION.apk"
curl -sLO "https://github.com/hogelog/pocket-secure-shell/releases/download/v$VERSION/pocketsecureshell-v$VERSION.aab"
sha256sum "pocketsecureshell-v$VERSION.apk" "pocketsecureshell-v$VERSION.aab"

gh attestation verify "pocketsecureshell-v$VERSION.apk" --repo hogelog/pocket-secure-shell --source-ref "refs/tags/v$VERSION" --signer-workflow hogelog/pocket-secure-shell/.github/workflows/release.yml
gh attestation verify "pocketsecureshell-v$VERSION.aab" --repo hogelog/pocket-secure-shell --source-ref "refs/tags/v$VERSION" --signer-workflow hogelog/pocket-secure-shell/.github/workflows/release.yml
```

両方 exit 0 なら OK。

**GH_TOKEN 失効時のフォールバック**: GitHub Attestations API は public repo なら anonymous で読める。bundle を落として offline 検証:

```bash
SHA=$(sha256sum "pocketsecureshell-v$VERSION.apk" | awk '{print $1}')
curl -sL "https://api.github.com/repos/hogelog/pocket-secure-shell/attestations/sha256:$SHA" | jq '.attestations[0].bundle' > apk.bundle.json
env -u GH_TOKEN -u GITHUB_TOKEN gh attestation verify "pocketsecureshell-v$VERSION.apk" --bundle apk.bundle.json --repo hogelog/pocket-secure-shell
```

### 7. 次バージョンへの baseVersionName bump PR

patch+1（X.Y.Z → X.Y.(Z+1)）を新しい `baseVersionName` に。dev skill のワークフロー使用。

- branch: `chore-bump-base-version-X.Y.(Z+1)`
- 編集: `app/build.gradle.kts` の `val baseVersionName = "X.Y.Z"` → `"X.Y.(Z+1)"`
- commit: `Bump baseVersionName to X.Y.(Z+1)`
- PR title: `Bump baseVersionName to X.Y.(Z+1)`
- PR body 例: `PR builds and debug builds now report the next version.`
- `mcp__aka__github_create_pr` で head/base 明示（feedback_github_create_pr_explicit_head_base）
- 英語（public repo）
- マージはユーザー（feedback_no_ai_merge）

### 8. 完了報告

`mcp__aka__send_message` で以下を送る:

- リリースバージョン
- リリースノート PR URL（マージ済み）
- Release URL: `https://github.com/hogelog/pocket-secure-shell/releases/tag/v$VERSION`
- Workflow run URL
- APK / AAB の sha256
- attestation 検証結果（APK/AAB ともに exit 0）
- bump PR URL

## 設計メモ

- **release-notes PR は最初に**: タグ commit に含まれている必要があるため、タグ push 前に必ずマージする
- **`default.txt` は全 track 共通**: `app/src/main/play/release-notes/en-US/default.txt` に書くと internal/alpha どちらの publish にも同じノートが乗る（Gradle Play Publisher の挙動）
- **ロケールは en-US のみ**: 他ロケールは Play Console の default language（en-US）にフォールバック
- **patch bump がデフォルト**: minor/major bump が必要な場合はユーザー指示があるまで patch 固定。指示があったらそれに従う
- **タグと Release はセット**: 失敗時のロールバックは両方削除（`gh release delete vX.Y.Z --yes` + `gh api -X DELETE /repos/hogelog/pocket-secure-shell/git/refs/tags/vX.Y.Z`）
- **ベースは常に main HEAD**: clone は標準 clone（タグ取得のため）
- **attestation の content は確認だけ**: bundle の中身に異常があってもこのスキルでは深追いしない。exit code で判定し、不一致なら停止して報告
