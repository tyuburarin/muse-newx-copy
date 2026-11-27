# Muse NewX Copy Tools — ビルド方法と設計メモ

## これは何か

X **12.43.0-prod.01** 向けの単体アドオン .mpp です。Piko 本体の .mpp と
**併せて当てる**前提で、旧 X 版 Muse パッチと同じ操作感のコピーを提供します。

- **NewX: Copy post text**
  - ポスト右上の **Ask Grok スロット位置にコピーアイコンを追加し、ワンタップでコピー**
    （v0.2.4 で追加。Grok が実際に表示されるポストではスロットは Grok のまま）
  - ポスト下部のインラインコピーアイコンは **v0.2.6 で削除**（実機で右上ボタンが
    ホームタイムラインに出ることを確認したため。詳細は v0.2.6 の節）
  - ただし**開いたメイン投稿（focal）だけは v0.2.7 で下部バーに戻しました**
    （共有アイコンの左隣。focal は系統 A でヘッダースロットが無いため。
    タイムラインには出ません。詳細は v0.2.7 の節）
  - ⋯ メニュー項目「ツイートのコピー」も残置（フォールバック）
  - ペイロードは著者行（表示名@ハンドル＋雪片ID由来の時刻）＋本文＋メディア直リンクを1行ずつ
  - **本文は「いま画面に表示されている文」が優先**: 翻訳表示中なら翻訳文（全文）、
    原文表示に戻していれば原文。翻訳が無ければ長文ポストのノート本文 → 通常本文の順
- **NewX: Copy media URL**
  - ポストの**画像／動画を長押しすると、そのメディア単体の直リンクをコピー**
    （本命の導線。ネイティブのボトムシートは出ない）
  - ⋯ メニュー項目「メディアURLをコピー」も残置（フォールバック。こちらはポスト全体分）
  - 画像は原寸 imageUrl（クエリ除去）、動画/GIF は最高 bitRate の mp4 バリアント
  - **長押しの動作はパッチオプション `mediaLongPressAction` で選択可能**（v0.2.3）:
    `copy`（既定、URLをコピー）/ `download`（DownloadManager で Downloads に保存）/
    `choose`（毎回「リンクをコピー／ダウンロード」の選択ダイアログを表示。v0.2.6 で
    旧 `both`（コピー＋ダウンロード同時実行）から置き換え。旧キー・旧タイトルは
    choose の別名として受理）。選択は Morphe マネージャでパッチを当てる時に
    行い、変更には再パッチが必要です。アプリ内設定ではありません。
    DownloadManager が使えない場合はコピーにフォールバックしてその旨をトースト表示。
    ⋯ メニュー項目はオプションに関係なく常にコピーです
- **NewX: Allow video playback during calls**（v0.2.3 で追加）
  - 通話中など他アプリが音声を使っている状況でも動画を再生できるようにする、
    旧 X 版 Muse パッチの NewX 移植。仕組みと証拠は後述「通話中動画再生」

v0.1.0 からの変更点がこの v0.2.0 の本体です。⋯ メニュー方式はユーザーの
求める操作感ではなかったため、上記3点（ワンタップ／長押し／表示中テキスト）を
追加しました。メニュー項目は互換のため残しています。

## v0.2.7 変更（focal＝開いたメイン投稿へのコピー導線）

### 問題（実機、v0.2.6＋Piko dev.5）

詳細画面の**返信ポスト**にはヘッダーのコピーボタンが出るのに、**開いた
メイン投稿（focal）には出ない**。focal のヘッダー行は「フォローする」
ピル＋⋯ のみ。ユーザー: 「開いたらコピーできんな」。⋯ メニューの
コピーは効くが、ワンタップ導線が最も重要な面で欠けていました。

### focal 経路の調査結果（dex 実測）

- **focal は系統 A です**: 描画は `Lcom/x/urt/items/post/a2;->a`
  （classes7.dex、1505 命令）。状態クラス `Lcom/x/urt/items/post/y;` の
  toString は文字通り **"FocalPostState(timelinePostState=…,
  authorAppealPivot=…, viewCount=…, followButtonState=…,
  showMessageButton=…, eventSink=…)"**。振り分けは merged lambda
  `Landroidx/compose/foundation/text/o2;->invoke`（classes.dex）で、
  j6 状態→系統 B（c.b）、y 状態→系統 A（a2.a）。つまり focal は c.b を
  **一切通りません** — ヘッダーボタンが出ないのはゲート条件ではなく
  経路そのものが別だからです
- **a2.a のヘッダーには Grok スロットがありません**（優先度1＝ヘッダー
  スロット流用は不成立、証拠付きで却下）: 著者行 @711、フォローピル
  `scribing/urt/e;->j` @756（実在機能、スクショでも表示）、Boost
  テキストボタン @804（`e6.G0` ゲート、ラベル quick_promote_boost*）、
  Super Follow テキストボタン @855（ラベル super_follow)、Message
  テキストボタン @897（PrimaryOutlined)、「Ad」テキスト @925、⋯
  `share/impl/n;->j` @961、著者バッジ @985（`e6.l0` ゲート）、
  super-follower バッジ @1015 `scribing/urt/b;->K`（`e6.Q` ゲート —
  ハードコードされたグリフを描く汎用バッジで c.b や Spaces シートと
  共用、コピー用スロットではない）。ラベル付きピルはいずれも正規の
  機能で、空きのアイコンスロットは存在しません
- **focal も f1 プレゼンタをコンポーズします**: focal プレゼンタは
  `Lcom/x/urt/items/post/m1;`（フィールド `p` = postdetail/z)。
  y.a が内包する j6/e6（timelinePostState）が f1 経路に渡るため、
  v0.2.6 のレジストリ供給（f1.c 先頭フック）は focal でも効きます —
  追加の供給は不要でした
- **下部バーは a2.a 内で完結します**: `iget-object e6;->n`（r1 =
  InlineActionBarState）@1205 → focal 専用スタイル `new t1(c3)` @1206
  → 単一のバー描画 `Lcom/x/inlineactionbar/w;->c(r1, …)` @1235。
  エントリ型は `Lcom/x/models/n4;`（a = `Lcom/x/models/x5;` 列挙、
  TwitterShare が静的単例、c = isEnabled)。状態のエントリ変換は
  `Lcom/x/signing/e;->v(Iterable) → kotlinx/immutable/b`（f1.c @182
  で実測）

### 実装（優先度2: focal 限定インライン、ユーザーの配置指定どおり）

判別子は「メソッド単位」で担保します: **ラップは a2.a の中だけ**に
差し、状態ビルダ f1.c には一切触りません。タイムラインのバー状態は
a2.a を通らないので、構造的にタイムラインへは出ません。

- 拡張 `InlineCopyButton` を v0.2.5 から復元（markIconSize の仮数部
  ビット刻印、selectIcon、isCopyAction、handleEvent、ブリッジ群は
  v0.2.5 と同一）。`filter()` は復元せず、新規に
  `public static r1 wrapFocalBar(r1)`（スタブで正確に型付け:
  `(Lcom/x/inlineactionbar/r1;)Lcom/x/inlineactionbar/r1;`）を追加:
  - エントリをコピーし、**最初の TwitterShare エントリの直前**に
    コピーエントリ単例を挿入（ユーザーの配置指定「共有の左」。
    TwitterShare はネイティブ共有と当方の旧キャリアが共用する型
    なので、同一性ではなく**位置**で判別。コピーエントリは markIconSize
    刻印済み Float で別途識別され、共有エントリには触れない）
  - 共有エントリが無ければ末尾に追加。既にコピーエントリがあれば
    状態をそのまま返す。**失敗時は元の状態をそのまま返す（fail-soft）**
    — バー自体は壊れません
  - 再構築はリフレクション: エントリ列は新設ブリッジ
    `toImmutableEntries`（check-cast Iterable → `signing/e;->v` 呼び
    出し、パッチ側で意味的に解決し記述子を固定）で immutable 化し、
    r1 の6引数コンストラクタに b〜f フィールドをコピーして再生成
- パッチ側は新規 `FocalInlineCopyInjection.kt`（v0.2.5 の
  InlineCopyInjection から、状態ビルダ・フィルタ部を除いて移植）:
  - `patchFocalBarWrap()`: a2.a（owner を固定したフィンガープリント:
    e6->n 読み＋w.c 呼び出しを含む）を特定し、単一の w.c 呼び出しが
    読む range 先頭レジスタを逆算（move-object* ← iget e6->n、間に
    書き込みが無いことを表明）、その move の**直前**に
    `invoke-static wrapFocalBar` ＋ `move-result-object` を挿入
    （分岐ターゲット移設なし、状態レジスタは除外指定）。move-result の
    型は r1 で、元の iget の型と一致 — v0.2.5 の型規律どおり
  - レンダラ刻印（k.q スコープ）、kind ゲート、アイコン・ラムダの
    selectIcon、f1 イベントハンドラは v0.2.5 のフックをそのまま復元
  - 併用耐性: sim 読み戻しで Piko sim の InlineDownloadButton 刻印と
    共存し、アイコン・ラムダで当方の selectIcon が Piko の後段にある
    ことを確認（v0.2.3 の順序規律）
- 「NewX: Copy post text」の説明文に focal バーの導線を追記

### 検証（2026-11-21、Morphe Desktop 2.1.1 CLI、JDK 21)

検証一式は `~/workspace/x-12.43/scratch/v027-verify/` に保存:

- list-patches: 3パッチ健在（説明文に focal 導線を反映）
- 単体適用・Piko-sim 同時適用・1パッチのみ ×3: いずれも
  `success:true` / `failedPatches:[]`
- オプション行列（タイトル リンクをコピー／ダウンロード／毎回選ぶ、
  旧タイトル コピー＋ダウンロード、ASCII コピー+ダウンロード、
  キー both／choose）: 全て適用成功＋スタブ値が期待どおり
  （ACTION_COPY=0 / ACTION_DOWNLOAD=1 / ACTION_CHOOSE=2 の
  0 始まりコードで、タイトル／キー／旧値の全経路を確認）。
  `bogus` は従来どおり PatchException で却下
- androguard 読み戻し（readback.py）: **単体 63 件・sim 65 件すべて
  PASS**。新規 focal 検査: a2.a に wrapFocalBar 呼び出しがちょうど
  1つ（記述子一致、move-result-object で同一レジスタへ書き戻し、
  そのレジスタが r1 型の e6->n 読み由来であること、単一の w.c
  呼び出しへ供給される move の直前であること）、c.b に wrapFocalBar
  が**無い**こと、ブリッジ3点の本体（createCopyAction は
  TwitterShare sget で n4 を構築、getPresenterPost は check-cast＋
  iget、toImmutableEntries は check-cast Iterable＋signing/e.v)、
  拡張の正確な型付け（wrapFocalBar / markIconSize / selectIcon /
  isCopyAction / handleEvent)、`filter` が拡張にも呼び出し側にも
  **存在しない**こと。従来検査（ヘッダー3フック＋型整合、レジストリ、
  f1.b public、メニュー、翻訳、長押し＋チョーザ、通話ゲート）も全て継続
- 分離検査（isolation.py）: text のみ＝ヘッダー＋レジストリ＋focal
  フック＋翻訳あり／長押し・通話なし、media のみ＝長押しのみ、
  video のみ＝通話ゲートのみ（拡張 dex 非混入）。全 PASS

### 未検証・残リスク（v0.2.7)

- **実機未検証**。ローカル CLI は ART ベリファイアを実行しないため、
  起動可否の最終判定は実機です（型整合は読み戻しで表明済み）
- focal バーでの表示・タップ・ペイロード（翻訳表示時の本文含む）は
  実機確認待ち。wrapFocalBar が fail-soft のため、最悪でも「focal に
  コピーアイコンが出ない」だけでバーやアプリは壊れません
- 系統 A で描画される他の面（focal 以外に a2.a を使う画面があれば）
  にも同じ下部エントリが出ます。これは「系統 A 限定」の設計上の
  帰結で、タイムライン（系統 B）には構造的に出ません

## v0.2.6 変更（インラインコピー削除／長押し第3モードを「毎回選ぶ」に変更）

### 前提: カバレッジ判定は実機証拠で確定

v0.2.5 を実機（Piko 3.12.0-dev.5 同時適用）で当てたユーザーのスクリーン
ショット（2026-11-21）で、(1) アプリが正常起動する（v0.2.4 の VerifyError
解消）、(2) **ホームタイムラインのポスト右上にコピーマークのヘッダー
ボタンが表示される**、の2点が確認できました。つまり系統 B
（sensitivemedia/api/c.b）がタイムラインを描画しており、v0.2.4 で
下段削除の前提にしたゲートは実機証拠で満たされました。これを受けて
ユーザーの指示どおり下段（インライン）コピーを削除します。

### 変更1: インライン（下段）コピーエントリの全削除

削除したもの:

- 状態ビルダのフィルタ追加（変換後リストへの `filter` 呼び出し＋再変換）
- レンダラの markIconSize フック（＋解決器 `InlineRendererResolver.kt`）
- アイコン・ラムダの selectIcon フック、kind ゲート（IconOnly 上書き）、
  イベントハンドラ先頭フック、`createCopyAction` ブリッジ
- 拡張 `InlineCopyButton` クラス全体（filter / markIconSize / selectIcon /
  isCopyAction / handleEvent。v0.2.3 の仮数部ビット・エンコードもろとも）
- パッチ側 `InlineCopyInjection.kt` / `InlineRendererResolver.kt` を削除

**レジストリ供給の付け替え（この変更の主リスク）**: ヘッダーボタンの
タップは「post id → contextual post」レジストリに依存し、その供給は
旧フィルタの副作用でした。付け替え先は新設 `PostRegistryInjection.kt`:

- 状態ビルダ `f1.c` の**先頭**に `HeaderCopyButton.rememberFromPresenter(this)`
  をフック。f1.c はポスト毎にコンポーズされる（旧フィルタと同一頻度）で、
  プレゼンタ f1 自身が contextual post を保持します（フィールド `b`:
  `Lcom/x/models/s1;`、dex で実測。ブリッジはフィールドを public 化して
  check-cast＋iget で読む — 旧ブリッジと同一本体を HeaderCopyButton 側へ移設）
- 候補から外した供給源: ヘッダーコンポーザブル自身（受け取るのは e6 =
  post id ホルダ `c: h6` と表示フラグのみで post モデル参照が無い）、
  翻訳 track（引数は (id, TranslatedPost) のみで post オブジェクトが無い）
- 状態ビルダの解決器群（collection flow 証明など）は旧 InlineCopyInjection
  から一致ロジックをそのまま移設。エントリモデルの解決は flow 証明の
  要素型として必要な記述子のみに削減

読み戻しによる証明（後述）: f1.c の命令0が rememberFromPresenter 呼び出し、
ブリッジ本体が check-cast f1 → iget `f1->b (s1)`、f1.b が public、
出力 dex 全体に `InlineCopyButton` / `markIconSize` / `createCopyAction` /
`isCopyMarked` の文字列が**一切残っていない**（単体適用時）。

存続: ヘッダーボタン（c.b の3フック、v0.2.5 の正確な型付けのまま）、
⋯ メニュー項目、翻訳キャプチャ＋stash、メディア長押し、通話中動画。
なお系統 A だけで描画される画面ではワンタップコピーが無くなります
（⋯ メニューは残る）。これはユーザーが実機でタイムラインの系統 B
表示を確認したうえでの指示です。

### 変更2: 長押しの第3モードは「毎回選ぶ」（チョーザ）

ユーザーの指摘: 旧 `both` は「コピーとダウンロードを両方実行」で、
欲しかったのは「毎回どちらか選べる」でした。

- オプション再定義: キー `copy` / `download` / `choose`、タイトル
  リンクをコピー / ダウンロード / **毎回選ぶ**。コード 2 は
  `ACTION_CHOOSE` に改名
- choose の動作: 長押しでネイティブの `AlertDialog`（項目
  「リンクをコピー」「ダウンロード」、キャンセル可 — 外側タップ／Back で
  何もせず閉じる）を表示し、選んだ方だけを実行します。実行部は既存の
  copy / download 実装をそのまま使用（download の DownloadManager
  不可→コピーへのフォールバック＋トーストも従来どおり）
- **Activity の取得方法（明記）**: フック地点（h5.invoke）には View が
  無いため、ActivityThread リフレクションで前面 Activity を解決します
  （`currentActivityThread().mActivities` から `paused == false` かつ
  finishing/destroyed でないレコードの activity）。ダイアログ表示は
  メインスレッドで実行（必要なら Handler で post）
- **フォールバック（明記）**: 使える Activity が無い、またはダイアログ
  構築が例外になった場合は **コピーを実行し、「選択画面を表示できない
  ためURLをコピーしました」とトースト**します
- 後方互換: `longPressActionCode` は旧キー `both` と旧タイトル
  コピー＋ダウンロード / コピー+ダウンロード（ASCII）も choose（2）の
  別名として受理します。キー／タイトル／別名の全経路を適用＋スタブ
  読み戻しで検証済み（下記）。不正値は新しい許容値一覧つき
  PatchException

### v0.2.6 の検証（Morphe Desktop 2.1.1 CLI、JDK 21、ログは
~/workspace/x-12.43/scratch/v026-verify/）

- `VERSION=0.2.6 ./build.sh` 成功。`list-patches`: **3パッチ**、
  オプションの許容値は copy / download / choose（既定 copy）
- 単体適用・sim 同時適用: 成功（failedPatches=[]）
- パッチ単独 ×3: 成功＋分離を読み戻しで確認（text のみ: ヘッダー＋
  レジストリ＋翻訳のみ／media のみ: 長押しのみ／video のみ: ゲート
  置換のみで拡張 dex 自体が入らない）
- 読み戻し（solo **48項目 ALL PASS** / sim **47項目 ALL PASS**）:
  v0.2.5 の型整合性チェック一式に加え、インライン痕跡の不在
  （上記4文字列）、f1.c 先頭の供給フック、ブリッジ本体と f1.b の
  public 化、メニューフック健在、チョーザの実装存在
  （AlertDialog 構築・項目文字列・フォールバックトースト・
  mActivities 走査）を表明
- オプション検証: タイトル リンクをコピー→0 / ダウンロード→1 /
  毎回選ぶ→2、旧タイトル コピー＋ダウンロード→2 /
  コピー+ダウンロード→2、旧キー both→2、キー choose→2 をそれぞれ
  適用しスタブ読み戻しで確認。`bogus` は新メッセージで PatchException

### 未検証（正直に）

- 実機動作は未確認です: チョーザダイアログが実際に表示・動作するか
  （ActivityThread の `mActivities` は内部 API で、フィールド名は AOSP
  では安定していますが端末／OS 差で解決できない可能性があり、その場合は
  上記フォールバック＝コピー＋トーストになります）、供給フック付け替え
  後のヘッダーボタンのタップ動作（供給タイミングは旧フィルタと同一の
  プレゼンタ・同一頻度ですが、先頭配置自体は新規）、系統 A 画面での
  挙動（設計どおりメニューのみ）
- 「未検証・既知の制限」節のインライン関連の記述（アイコン競合など）は
  エントリ自体を削除したため v0.2.6 以降は無効です

## v0.2.5 修正（ヘッダーフックの VerifyError — 拡張メソッドの正確な型付け）

v0.2.4 は実機で **VerifyError** になった（ユーザーのクラッシュログ
2026-11-21 21:18、Piko 3.12.0-dev.5 同時適用）:

```
Verifier rejected class com.x.sensitivemedia.api.c: void c.b(...) failed to verify:
[0x9A5] register v15 has type Reference java.lang.Object
but expected Reference: com.x.icons.b
```

### 原因（フック別に確定）

ヘッダーの3フックは「横取りした値のレジスタへ move-result で書き戻す」
形で、ART のベリファイアはそのレジスタの型を**拡張メソッドの宣言上の
戻り型**で決める。v0.2.4 は3フックとも `Ljava/lang/Object;` 入出力で
宣言していたため、書き戻した瞬間にレジスタの型が Object に化けた。

- **selectIcon（致命傷・報告された箇所）**: `sget-object v15,
  Lcom/x/icons/a;->I4 Lcom/x/icons/b;` の直後に Object 版 selectIcon を
  挟み、v15 が Object 化。そのまま `Lcom/x/compose/core/j;->f(
  Lcom/x/icons/b; ...)`（Icon コンポーザブル、第1引数 icons/b）へ渡る
  [0x9A5] で検証失敗。
- **wrapClick（同種の地雷）**: y1 `<init>` 直後の v5 を Object 化して
  書き戻し。直後の型付き使用は `move-object v29, v5` →
  `check-cast v29, Lkotlin/jvm/functions/Function0;` →
  clickable `f0.b(..., Function0, ...)` で、check-cast が再窄化する
  ため今回は報告されなかったが、同じ型汚染であることに変わりない。
- **forceShow**: `(Z)Z` で元々正確（iget-boolean の型そのまま）。
  挿入位置・レジスタとも問題なし。

ローカルの Morphe CLI 適用は **ART ベリファイアを実行しない**ため、
v0.2.4 の読み戻し検証（位置・順序のみ）では検出できなかった。

### 修正

拡張メソッドを、横取りする値の **正確な dex 型** で宣言し直した
（`HeaderCopyButton`）:

- `selectIcon(Lcom/x/icons/b;)Lcom/x/icons/b;` — I4/E2 のフィールド型
  かつ Icon コンポーザブルの第1引数型。コピーアイコンは従来通り
  リフレクションで読み、`instanceof` ガード付きでキャストして返す。
- `wrapClick(Lcom/x/urt/items/post/y1;Lcom/x/urt/items/post/e6;)
  Lkotlin/jvm/functions/Function0;` — 引数は構築直後のラムダの正確な
  クラス（y1、dex で確定: `public final synthetic`, super Object,
  interfaces=[Function0], `<init>(Function1;B)V`）。戻りは y1 自身には
  できない（差し替えオブジェクト CopyAction は y1 ではない）ため、
  y1 が実装し、直後の check-cast が名指しする消費型 Function0 を
  使う。これがこの地点で表現可能な正確な型。
- `forceShow(Z)Z` — 変更なし。

アプリ型を Java ソースで名指すため、`stubs/` にコンパイル専用スタブ
（com.x.icons.b / com.x.urt.items.post.y1 / e6）を追加。build.sh は
スタブを別ディレクトリにコンパイルして javac のクラスパスにだけ載せ、
拡張 jar への混入を検査してビルドを落とす。パッチ側
（HeaderCopyInjection）も I4 の型と y1 の Function0 実装をピン留めし、
ドリフト時は PatchException で落とす。

レジスタ監査: 3フックとも横取りしたレジスタ以外を触らず（引数は
既存レジスタの読み取りのみ、作業レジスタ不使用）、move-result の型は
下流の期待型と一致する（下記の型整合性チェックで機械検証）。

### 他パッチの同種監査（バンドル全体）

- **インライン selectIcon（w2 ラムダ）**: Object 入出力だが、
  move-result-object の**直後に check-cast（共有アイコン
  フィールド型）**が挟まっており、型付き使用の前に再窄化される。
  これが v0.2.2〜0.2.3 で実機動作した理由。今回の読み戻しでも
  check-cast の隣接を再確認した。
- 状態ビルダ filter: 戻りは `Ljava/util/List;` で変換の戻り型と一致。
  markIconSize / isCopyAction: float / boolean のスクラッチ経由で安全。
  handleEvent: スクラッチ＋boolean。getPresenterPost /
  createCopyAction ブリッジ: Object 戻りのスタブ本体内でのみ完結。
- メニュー（PostOptionsInjection）: addOption は List、labelFor は
  String（ネイティブのラベル取得と同じ戻り型）、usesIcon /
  handleOptionAction は boolean をスクラッチへ。いずれも v0.1.0 から
  実機動作中。
- 翻訳 track: 戻りなし（void）の invoke-static のみ。長押し: 先頭
  フックでスクラッチ使用、戻りは Unit（Object のサブタイプ）。
  通話中パッチ: move-result を同型の const に置換するのみ。

### v0.2.5 の検証（Morphe Desktop 2.1.1 CLI、JDK 21、ログは
~/workspace/x-12.43/scratch/v025-verify/）

- ビルド成功。拡張 dex の実記述子が上記の正確な型であること、スタブ
  クラスが拡張 dex・出力 APK のどちらにも混入していないこと
  （icons/b・y1・e6 の定義が APK 全体で各1つ＝アプリ自身のものだけ）
  を確認。
- list-patches: 3パッチ（全て既定オン）。
- 単体適用・sim 同時適用・パッチ単独 ×3: 全て成功
  （failedPatches=[]）。
- オプション: 表示名3種（リンクをコピー／ダウンロード／
  コピー＋ダウンロード）→ スタブ 0/1/2、キー `both` → 2、不正値
  `bogus` → 従来通り PatchException。
- 読み戻し（solo / sim とも **28項目 ALL PASS**）: v0.2.4 の14項目に
  加え、**型整合性チェック**を新設 — c.b 内の各挿入 invoke について
  「拡張メソッドの記述子型 == 横取りした値の型」を表明:
  selectIcon の記述子 == 直前の sget のフィールド型 == 直後の
  j.f の第1引数型（全て icons/b）、wrapClick の引数1 == 直前の
  `<init>` の型（y1）・引数2 == メソッド第1引数型（e6）・戻り ==
  下流の check-cast 型（Function0）、forceShow == (Z)Z、
  および「c.b 内の HeaderCopyButton フックに Object 型を含むものが
  ないこと」。

### 未検証（正直に）

この VM には **ART ベリファイアが無い**ため、「実機で検証を通る」
こと自体はここでは証明できない。対策は上記の正確な型付け規律＋
型整合性の機械チェックであり、最終確認は実機での起動になる。
ヘッダーボタンの表示・タップ動作、Grok フラグ ON 時の復帰、
系統B のカバー範囲（タイムライン／詳細）も引き続き実機未検証
（v0.2.4 節の記述のまま）。

## v0.2.4 変更（右上ヘッダーコピーボタン追加／長押しオプションのラベル受理修正）

### 1. ヘッダー（右上）コピーボタン — Ask Grok スロットの転用

ポスト右上の「Ask Grok」スロット（`Lcom/x/sensitivemedia/api/c;->b`、
classes6.dex。調査ノート `~/workspace/research_notes/newx-header-copy-button.md`）
を3点フックでコピーボタンに転用しました（新規ファイル
`HeaderCopyInjection.kt` ＋拡張 `HeaderCopyButton`）:

- ゲート読み（`e6;->b0` iget-boolean、命令 @1138）の直後:
  `v4 = HeaderCopyButton.forceShow(v4)` — スロットを常時表示にする
- クリックラムダ `y1(eventSink, 13)` の `<init>`（@1180）の直後:
  `v5 = wrapClick(v5, v1)` — コンポーザの remembered-value 保存（@1181）の
  **前**に入るため、再コンポーズ時に復元される値もラップ済みになる
- Grok アイコン `icons/a;->I4` の sget（@1206）の直後:
  `v15 = selectIcon(v15)` — コピーマーク `ic_vector_copy_stroke`
  （`icons/a;->E2`、0x7f080515。拡張が反射で読む。パッチ側でも
  フィールドの存在・型をピン留め）

**Grok 共存は「判別あり」（調査の推奨形 (a')）で実装しました。**
forceShow が元のゲート値（フラグ AND ポスト適格性の最終判定）を拡張側に
覚え、selectIcon / wrapClick はゲートが true の間はネイティブ値を素通し
します。つまり **Grok が実際に表示されるポストでは Ask Grok ボタンが
そのまま機能し**、そうでないポストでのみスロットがコピーボタンになり
ます。1ポストのヘッダー区間のコンポーズは UI スレッド上で連続する
ため、この受け渡しは安全です。

ヘッダーはポストモデルではなく UI 状態（e6）しか受け取らないため、
タップ時のペイロード解決はレジストリ経由です: 同じパッチのインライン
フィルタがコンポーズ毎に「ポストID → contextual post」を覚え
（ID パースはポスト実体ごとに1回）、タップ時は e6 の postId（`e6.c` →
`h6.b`、DisplayedTranslation と同じ読み方）で引いて、メニュー／
インラインと同一のペイロードビルダーでコピーします（表示中翻訳の
優先も同一）。レジストリに無いポスト（下記のレイアウト B 以外で描画
されたポスト等）では「コピーできる内容がありません」のトーストに
なります。

### 2. カバレッジ判定 — インライン（下段）ボタンは**残置**

ユーザー決定は「右上へ移して下段は消す」でしたが、削除の前提条件
（Grok スロットのあるレイアウト系 B がタイムラインとポスト詳細の
両方を描画すること）を静的解析で確定できなかったため、**v0.2.4 では
インライン項目を残しています**。根拠:

- 完全なポストレイアウトが2系統ある: 系 B（`sensitivemedia/api/c.b`、
  Grok スロットあり）と系 A（`urt/items/post/a2;->a`、Grok 区間なし）
- 両系統とも LazyList のアイテム内容ファクトリを持つ
  （系 B は `j6.a`、系 A は `y.a`）。系 A の状態 `y` は URT ポスト
  プレゼンタ `m1` が構築し、`y.a` の呼び出し元は classes.dex の
  結合ラムダ1ケースのみ — タイムラインが系 A を使う可能性を排除
  できなかった
- 系 B 側の傍証（翻訳対応の本文コンポーザ `o6` は `c.d ← c.b` から
  のみ生成される、`j6.a` の呼び出し元が urt/items/post の中核
  コンポーザ群・引用・コミュニティノートに広がる）だけでは、
  「系 B がタイムラインを描画する」の証明には届かなかった

したがって系 B で描画される画面では右上ボタンと下段ボタンの両方が
出ます（どちらも同じペイロード）。系 A で描画される画面では従来
どおり下段のみです。実機で「どの画面が系 B か」が確認できれば、
次版で下段の削除に進めます。⋯ メニューのコピーは全画面で従来
どおり使えます。

### 3. 長押しオプション: Manager 2.6.0 が「タイトル」を渡す問題の修正

v0.2.3 を実機（Manager 2.6.0）で当てると、UI で長押しアクションを
選ぶたびに `PatchException: Invalid mediaLongPressAction value:
"コピー＋ダウンロード"`（および `"リンクをコピー"`）で失敗しました。
**Manager 2.6.0 はオプション値としてキーではなくタイトル（values
マップの値）を渡す**ため、v0.2.3 では UI 選択が全て落ちていました
（既定のまま＝キー `copy` が使われる場合のみ成功）。

`longPressActionCode` を書き換え、正規化（trim＋小文字化）のうえで
キー（copy/download/both）と、オプション定義にある**正確なタイトル**
（リンクをコピー / ダウンロード / コピー＋ダウンロード）の両方を
受理するようにしました。追加の別名として コピー（→copy）と
ASCII プラス表記 コピー+ダウンロード（→both）も受理します。
未知の値は従来どおり PatchException で落とします。

### v0.2.4 の検証（Morphe Desktop 2.1.1 CLI、JDK 21、ログは
`~/workspace/x-12.43/scratch/v024-verify/`）

- `VERSION=0.2.4 ./build.sh` 成功、`list-patches` で**3パッチ**表示
- 単体適用: 成功（failedPatches=[]）。読み戻し全項目 PASS:
  c.b の3フックが想定位置にあること（ゲート直後 forceShow＋move-result
  の後に元の if-eqz／y1<init>(13) 直後 wrapClick／I4 sget 直後
  selectIcon）、インライン一式・メニュー・翻訳キャプチャ・長押し・
  動画ゲートの全フック健在、フィルタに rememberPost 呼び出し追加
- シミュレーション同時適用: 成功（Sim＋3パッチ）。読み戻しも全 PASS
  （シミュレーションは c.b に触れないためヘッダーフックへの干渉なし）
- パッチ単独適用×3: いずれも成功し分離を読み戻しで確認
  （text のみ: ヘッダー＋インライン＋翻訳のみ／media のみ: 長押しのみ／
  video のみ: ゲート置換のみで拡張 dex 自体が入らない）
- オプション検証: **タイトル3種**（リンクをコピー→スタブ0／
  ダウンロード→1／コピー＋ダウンロード→2）をそれぞれ適用し読み戻し
  で確認。キー `both`→2 も引き続き成功。不正値 `bogus` は新しい
  メッセージで PatchException。なお CLI 検証時は JVM の文字コードを
  UTF-8 にしないと `-O` の日本語が文字化けして落ちる（実機の
  マネージャ UI 経由では発生しない、検証環境側の注意点）

**未検証（実機）**: 右上ボタンが実際に表示・動作するか、どの画面が
系 B で描画されるか（タイムライン／詳細／検索／プロフィール）、
ヘッダー行のレイアウトへの影響（⋯ との間隔）、Grok フラグ ON 時に
本当に Grok 表示へ戻るか。既知の化粧的制限: ボタンのコンテンツ
説明（TalkBack 読み上げ）はネイティブの "Ask Grok" 文字列のまま
です（文字列リソース差し替えは未実施）。

## v0.2.3 変更（アイコン競合の解消／通話中再生の移植／長押しアクション選択）

v0.2.2 は実機で Piko 3.12.0-dev.4 との同時適用が通った一方、ユーザーから
2件の実機フィードバックがありました。あわせて長押しアクションの選択機能も
追加しています。

### 1. コピーアイコンがダウンロードアイコンに化ける問題（解消）

実機スクリーンショット: メディア付きポストではバー末尾にダウンロード
アイコンが2つ並び、テキストのみのポスト（Piko のダウンロードはメディア
述語で隠れる）でも末尾にダウンロード風アイコンが1つ出る。後者が本バンドルの
コピーエントリで、化けていたのはこちらです。

**原因**: アイコン・ラムダへ届く判別情報はサイズ float の符号だけ。
Piko の `selectIcon` は「負なら自前アイコンに差し替え」の二値判定で、
本バンドルのコピーエントリ（負マーク）も自前扱いしてしまいます。命令順は
本バンドル @129 → Piko @135（v0.2.2 シミュレーション読み戻し）で、
**後に実行される Piko の差し替えが勝って**いました。

**修正**（2つの合わせ技）:

- **エンコードの分離**: 本バンドルのマークを「負値＋仮数部最下位ビット=1」に
  変更（`InlineCopyButton.markIconSize`）。Piko のマークは `-abs(size)` で、
  通常のアイコンサイズ（24.0 等の整数 float）は仮数部最下位ビットが 0 なので
  区別できます。大きさの変化は最大 1 ulp（24f で約 2e-6）で、レイアウト側の
  `Math.abs` は実質元のサイズを読み戻します。本バンドルの `selectIcon` は
  このマークのときだけコピーアイコンを返し、それ以外は入力アイコンを
  そのまま返します
- **実行順の反転**: 本バンドルの selectIcon フックを、Piko の selectIcon
  呼び出しが既にある場合は**その move-result の直後**に差し込むよう変更
  （無ければ従来の位置）。これで本バンドルの selector が最後に実行されます

**命令レベルの証明**（v0.2.3 シミュレーション同時適用の読み戻し、
`v023-verify/readback-sim.txt`）: レンダラ `k;->q` のマーク連鎖は
Muse @76 → Piko @85（どちらも identity ゲート付き）。アイコン・ラムダ
`w2;->invoke` では Piko selectIcon @129 → move-result-object @130 →
Muse selectIcon @134 → move-result-object @135 が、描画呼び出し @140 の前の
アイコンレジスタへの最後の書き込みです。したがって:

- コピーエントリ（マーク=符号＋最下位ビット）: Piko が一旦ダウンロードに
  差し替えるが、最後に Muse がコピーマークを検出してコピーアイコンに戻す
- Piko のダウンロードエントリ（マーク=素の負値、最下位ビット0）: Piko が
  ダウンロードに差し替え、Muse はコピーマークでないので入力をそのまま返す
- 本物の共有エントリ（正値）: どちらも差し替えない

単体適用時は従来位置に Muse の selector だけが入り、同じ判定で動きます。
残る前提: ネイティブのアイコンサイズの float 表現の最下位ビットが 0 で
あること（12.43 の InlineActionSizing のサイズはこの条件を満たします）。
将来サイズ側が変わって最下位ビット=1 の値を使うようになると誤判定し得ます。

### 2. 通話中動画再生（NewX 移植）

旧 X（12.19.1）の調査（`~/workspace/research_notes/video-call-investigation.md`）
の対象だった Twitter AV プレイヤーは 12.43 に**存在しません**
（`Lcom/twitter/media/av/player/` は全 dex で 0 件、エラー文字列
`media_error_audio_focus_rejected` のリソースも消滅）。12.43 は media3
ExoPlayer ＋ X 独自のオーディオフォーカス・リース系
（`Lcom/x/audiofocus/`）＋ VideoAudioFocus コーディネータ
（`Lcom/x/media/playback/audiofocus/`）で再生します。命令レベルで追った
結果、ゲートは高確度で特定できました:

- `audiofocus/d.F`（Player.Listener コールバック）が「audible」
  （再生中＋音声トラックあり＋音量あり）を計算し、変化時にマネージャ
  `audiofocus/j.b(playerListener, audible)` を呼ぶ
- `j.b` は audible のとき `article/y.M` → `audiofocus/d.b(Function1)` →
  `AudioManager.requestAudioFocus(AudioFocusRequest)` でフォーカスを要求。
  **要求自体は実際に走り、結果も本物**です
- 結果が false（通話中など）だと、ログ文字列そのまま
  `"onAudible: focus denied in state=…, pausing the video"` を残して
  **`Player.pause()` を呼び、動画が始まらない**。これが旧 `u.N` ゲートの
  対応物です（ミュート自動再生のタイムラインは audible=false で要求自体を
  しない、という非対称性も旧版と同じ構造）

**パッチ**（第3パッチ「NewX: Allow video playback during calls」、既定オン、
単独で無効化可能）: `j.b` 内のフォーカス要求の `move-result` を
`const/4 vReg, 0x1` に置換し、直後の `if-nez` が常に granted 側へ進むように
します。旧版の「IF_EQZ を NOP する」と同じ思想で、変えるのは**この
コンシューマの分岐判定だけ**です:

- フォーカス要求は従来通り実行され、リース内部の帳簿は本物の結果を保持
- 通話側のフォーカスは奪わない（拒否された要求では X は何も保持しない）
- 他のコンシューマ（音声プレイヤー、Spaces、Grok ボイス）は無改造
- エラーメッセージを隠す類の変更ではない（止めていた pause そのものを外す）

フィンガープリントは厳格です: スコープ＋引数シグネチャ＋3つのログ文字列
（denied / pausing / granted）で解決し、解決クラスが想定の `j;` であること、
リース要求の invoke がちょうど1つ、その move-result と同一レジスタへの
`if-nez`、メソッド内に media3 Player の `pause()` 呼び出しがあることを
全部満たさないと PatchException で落とします。読み戻しでは
`j.b` の @30 に要求 invoke、@31 に `const/4 v2, 1`（move-result と置換済み）、
@33 に元の `if-nez` が残ることを確認済みです。

**未検証**: 実機で通話中に全画面動画が実際に再生されるか（音声まで出るか）は
未確認です。旧版と同じく、再生開始自体はこの層に他の拒否機構が無いため
見込みは高い一方、音声は端末/OS のポリシー次第です。また VideoAudioFocus の
コーディネータがどの画面のプレイヤーに付くか（全画面のみか、タイムラインの
音声付き再生にも付くか）は追い切っていないため、パッチの効き目が全画面以外に
及ぶ可能性があります（旧版でもゲートメソッド全体への適用が推奨案でした）。

### 3. メディア長押しのアクション選択（パッチオプション）

「NewX: Copy media URL」に Morphe パッチオプション `mediaLongPressAction`
を追加しました（文字列、既定 `copy`、許容値 `copy` / `download` / `both`）。
パッチ時に拡張スタブ `MediaLongPress.longPressAction()` の本体を対応する
定数（0 / 1 / 2）を返すよう書き換える、既存ブリッジと同じ方式です。
不正な値は許容値を列挙した PatchException で落ちます（検証済み）。
DownloadManager が使えない場合はコピーへフォールバックし、その旨を
トーストで知らせます。**この選択はパッチを当てる時にマネージャで行う
もので、変更には再パッチが必要です**（単体バンドルにはアプリ内設定 UI を
持たせる手段がありません）。

### v0.2.3 の検証（Morphe Desktop 2.1.1 CLI、JDK 21、ログは
`~/workspace/x-12.43/scratch/v023-verify/`）

- `VERSION=0.2.3 ./build.sh` 成功、`list-patches` で**3パッチ**表示
  （mediaLongPressAction オプションも既定 copy・許容値3件で表示）
- 単体適用: 成功（3パッチとも適用、failedPatches=[]）
- シミュレーション同時適用: 成功（Sim＋3パッチ）。読み戻しで上記の
  マーク連鎖・selector 順序・ゲート置換・全フックの共存を確認
- パッチ単独適用×3: いずれも成功し、分離も読み戻しで確認
  （text のみ: インライン＋翻訳のみ／media のみ: 長押しのみ／
  video のみ: ゲート置換のみで拡張 dex 自体が入らない）
- オプション検証: download でスタブが 1 を返し、both で 2 を返すことを
  それぞれ適用後の読み戻しで確認。既定 copy は 0。不正値 `bogus` は
  `Invalid mediaLongPressAction value: "bogus". Allowed values are:
  copy, download, both.` で失敗

## v0.2.1 修正（Piko 同時適用で落ちた問題）

v0.2.0 は単体では適用できましたが、Piko 3.12.0-dev.4 と同時適用すると
実機（Manager 2.6.0 / patcher 2.4.0）で「NewX: Copy post text」が
`PatchException: Expected one NewX inline action list conversion in
Lcom/x/inlineactionbar/f1;->c(...)` で失敗しました。

**原因**: Piko の「NewX: Customize inline actions」（ダウンロードボタンの
依存パッチ）は、本バンドルと同じ変換箇所を **execute フェーズ**で先に
書き換えます（本バンドルのフックは finalize フェーズ）。具体的には元の
immutable 化変換の直後に「hidden-set 読み取り＋自前フィルタ呼び出し＋
**同じ変換の再呼び出し**」を挿入するため、メソッド内の変換が2つになり、
「変換はちょうど1つ」を要求していた本バンドルのマッチャが落ちました。
監査の結果、同じ種類の壊れ方があと2箇所潜んでいました:

- 捕捉レンダラ `k;->q`: Piko の markIconSize ブロックがサイズ値の流れを
  invoke 結果経由に変えるため、本バンドルの「サイズ読み→描画呼び出し」の
  来歴証明が途切れる
- アイコン・ラムダ `w2;->invoke`: Piko の selectIcon ブロックが同じ
  サイズフィールドを2回目の iget で読むため、「サイズ読みはちょうど1つ」
  の数え方が壊れる

**修正**（他のマッチャは弱めていません）:

1. 状態ビルダ: 変換を「ちょうど1つ」ではなく「同一参照の変換チェーンの
   **最後**」にフックする形に変更。Piko のフィルタの後段で動くため、
   identity 追加の自前フィルタと競合しません（単体時は従来どおり1つ）
2. 来歴証明: `markIconSize(Object, F)F` の結果は F 引数の来歴を引き継ぐ
   ものとして扱う（マークは符号反転のみで、値の出所は変わらないため）
3. アイコン・ラムダ: サイズフィールドの特定を「命令数」ではなく
   「相異なるフィールド」で数える形に変更（分岐内の実アクセスが
   ちょうど1つである要求は維持）

kind 上書き・イベントハンドラ・長押し・翻訳キャプチャの各マッチャは
Piko の先行編集を受けても壊れないことを監査で確認しました（メソッド単位の
照合か、先行ブロックが数え上げ対象の範囲外にあるため）。

## v0.2.2 修正（Piko 同時適用の2件目: レンダラの tint 引数）

v0.2.1 を実機で当てると、失敗箇所が状態ビルダの先へ進み、今度は捕捉
レンダラ解決で落ちました:
`PatchException: Expected exactly one NewX captured inline-action tint
argument in Lcom/x/inlineactionbar/k;->q(...), found 0`

**原因**: 犯人はダウンロードボタンではなく **Piko の「NewX: Theme」
（DynamicColorPatch）**でした。Theme は同じ捕捉レンダラ `k;->q` を解決して、
キャプチャ済み tint フィールドの読み取り（iget-wide）の**直後**に
「そのレジスタをそのまま書き換える」色変換を差し込みます
（`invoke-static DynamicColorPalette->inlineActionTint(J)J` を
(reg, reg+1) で呼び、move-result-wide で同じレジスタに戻す。content tint
側も同様）。本バンドルの来歴証明はこの move-result-wide を「出所不明」と
するため、tint 値の流れがそこで途切れ、tint 引数の候補が0件になっていました。

**v0.2.1 のシミュレーションが再現できなかった理由**: シミュレーションは
Customize inline actions と Inline download button の変換だけを移植して
いて、Theme の tint 変換を**含んでいなかった**ためです。共有メソッドを
触る Piko パッチはこの2つだけではない、という前提漏れでした。

**修正**（InlineRendererResolver）:

1. 来歴証明の「素通し」扱いを一般化: move-result の直前の invoke について、
   (a) markIconSize(Object, F)F は従来どおり F 引数の来歴を引き継ぎ、
   (b) **第1引数レジスタ = move-result の書き戻し先レジスタ**である
   in-place 変換は、その引数の来歴（wide は高位ワードも）を引き継ぐ。
   変換結果はその引数だけから決まるため、「その値がどこから来たか」は
   変わりません。メソッド名・クラス名には依存しない構造条件です
2. tint 引数／content tint／アイコンサイズの3箇所に、来歴証明が0件の
   場合の**レジスタ同一性フォールバック**を追加（in-place 変換は読み取りと
   同じレジスタへ書き戻すため、レジスタ一致は来歴と等価に強い同定です）。
   それでも同定できない場合は、候補・全 wide 引数・読み取り位置を列挙する
   診断メッセージ付きで落とします（次の失敗が自己説明的になるように）

**シミュレーションの忠実度を上げ直しました**: 拡張参照を Piko 実物の
記述子（`Lapp/morphe/extension/newx/misc/InlineDownloadButton;`、
`.../InlineActionFilter;`、`.../theme/DynamicColorPalette;`）に差し替え、
Theme の tint 変換（item 6）を追加。検証結果:

- 改善シミュレーション＋v0.2.1（旧）: 実機と**同一の例外・同一の
  スタック**（InlineRendererResolver.kt:272）で失敗を再現
- 改善シミュレーション＋v0.2.2: 3パッチとも適用成功。dex 読み戻しで
  k.q 内に Muse の mark @76、Piko の mark @85、Theme の tint 変換
  @138 / content tint 変換 @150 が共存し、状態ビルダのフィルタ連結
  （Piko @191 → Muse @197）、w2 の selectIcon 2件、kind ゲート2件、
  イベント先頭ブロック2件も確認
- 単体適用・片方だけ適用も従来どおり成功し、単体時のフック位置は
  v0.2.0/v0.2.1 と同一。片方だけの分離も読み戻しで確認
  （text のみ: mark/track あり・長押しなし／media のみ: その逆）

なお Piko ローカルクローンはタグ v3.12.0-dev.4 そのもの
（"chore: Release v3.12.0-dev.4" コミット）であることを git で確認済みで、
上記の原因特定は実物の dev.4 ソースに基づきます。それでも本物の dev.4
バンドルとの同時適用そのものはこの環境では実行できず、**実機確認が要る**
点は変わりません（次節の注記も参照）。

## 仕組み（v0.2.0 の追加分）

### ワンタップ・インラインコピー

Piko の InlineDownloadButton と同じ経路の移植です（バンドル内に自己完結）。

1. **リスト注入**: 状態ビルダ `Lcom/x/inlineactionbar/f1;->c(Composer)` が
   CanonicalPost のエントリ列を immutable 化する変換の直後に拡張
   `InlineCopyButton.filter(list, presenter)` を通し、コピーエントリを末尾に追加。
   表示条件なし（全ポスト常時表示）。タイムラインとポスト詳細の両方に出ます
2. **エントリ**: パッチ時に拡張スタブ `createCopyAction()` の本体を注入し、
   `InlineActionEntry(TwitterShare, count=null, isEnabled=true)` を生成して
   拡張側 static にキャッシュ。識別は actionType ではなく**シングルトンの
   identity（==）**で行うため、本物の共有エントリと衝突しません
3. **アイコン**: レンダラ（12.43 では捕捉ラムダ `Lcom/x/inlineactionbar/k;->q`）が
   サイズを読む位置で `markIconSize` を呼び、コピーエントリのときだけサイズを
   **負値化**（符号がアイコン・ラムダへの唯一の伝達チャネル）。TwitterShare の
   アイコン分岐では `Math.abs` でレイアウト用に戻し、符号が負なら
   `selectIcon` で `ic_vector_copy_stroke`（ARSC 0x7f080515）に差し替え
4. **スロット幅**: レイアウト・ラムダが kind モデルを作る直前に identity ゲートを
   入れ、コピーエントリの kind だけを `IconOnly` に書き換え（TwitterShare の
   広い Countless スロットを避ける）
5. **イベント**: プレゼンタのイベントハンドラ `f1;->d` の先頭で
   `handleEvent(presenter, event, longPress)` を呼び、イベント内包エントリを
   identity で照合。一致時のみペイロードをコピーして処理を打ち切ります
   （長押しもタップ同扱い）。ペイロード生成はメニュー版ハンドラと共用で、
   presenter からはパッチ時ブリッジ `getPresenterPost`（f1 の `b` フィールド =
   ContextualPost）でポストを取り、モデル読みは拡張側リフレクション
   （PostModelAccess）。Context は ActivityThread から取得

### 画像／動画の長押しコピー

NewX にはメディア長押しが既に実装されており、長押しイベント
`DidLongPressMediaContent`（`Lcom/x/urt/items/post/k4;`）のフィールド `a` に
**押されたメディア単体**が入ります。消費側ハンドラ `Lcom/x/urt/items/post/h5;->invoke`
の入口でこれを横取りし、`MediaUrlExtractor.extractUrls()` に単体リストで渡して
直リンクをコピー、**そのまま return してネイティブのボトムシート
（Save / Copy Photo 等）を表示しません**。タップは別イベント
（DidClickMediaContent）なので、タップ→ビューア遷移は無傷です。
ハンドラの解決は難読化名ではなく意味条件（k4 の instanceof ＋ media/v の構築 ＋
k4 の b/c フィールド読み ＋ const-string "long_press"）で行います。

副作用: 長押しから Save Photo / Save 4K Photo 等のシート項目へは到達できなく
なります（ビューア側のメニューには残る想定ですが未検証です）。

### 表示中の翻訳文コピー

「表示中かどうか」は CanonicalPost の中にはありません（ユーザーの
原文／翻訳トグルはコンポジション状態に住む）。両プレゼンター（タイムラインの
自動翻訳とポスト詳細のオンデマンド翻訳）が、表示中の TranslatedPost
（非表示時は null）を登録メソッド
`Lcom/x/scribing/urt/c;->R(d; h6; l; Composer; I)V` に渡すので、その入口で
拡張 `DisplayedTranslation.track(postId, translatedPost)` を呼び、
post id → 翻訳全文を stash します（ConcurrentHashMap ベース、直近64件）。
コピー時は stash ヒットが最優先（ノート原文・通常原文より上）、ミス時は
従来のフォールバック連鎖です。トグルで原文に戻すと再コンポーズで null が
来て stash が消えるため追随します。R の解決は引数シグネチャ＋TranslatedPost の
toString アンカー＋呼び出し元（両プレゼンター）チェックで行い、クラス名には
依存しません。CanonicalPost.K（自動翻訳モデル）の無条件採用はしません
（原文表示中のポストを誤コピーするため）。プレビュー文（切り詰め）も使いません。

## ビルド

Gradle は使いません（Muse パッチと同じ手作業パイプライン）。

```bash
VERSION=0.2.3 ./build.sh   # → build/muse-newx-copy-0.2.3.mpp
./build.sh                 # バージョン無指定時は 0.1.0（MANIFEST の Version に反映）
```

依存は Muse パッチの BUILD.md と同一です: JDK 17、Morphe Desktop 2.1.1 の
fat jar、Gradle 8.13 配布物の lib/（kotlin-compiler-embeddable 2.0.21 等）、
Android SDK build-tools 36.0.0 の d8、platform android-36 の android.jar。
パッチ側 kotlinc フラグ `-Xcontext-receivers -Xskip-metadata-version-check
-Xskip-prerelease-check -jvm-target 11` も同一です。
なお Morphe 2.1.1 には `cloneMutable` が無いため、フレーム拡張は
`addFreeRegisters`（BytecodeUtils）で代替しています（状態ビルダと
createCopyAction ブリッジ）。

## .mpp の構造

```
META-INF/MANIFEST.MF        Name / Description / Version / Patcher-Version: 2.0.1
META-INF/main.kotlin_module
classes.dex                 パッチクラスの dex
local/muse/newx/.../*.class パッチ本体（Kotlin）
app/crimera/bytecode/*.class 同梱した型付きバイトコード射出ライブラリ
app/crimera/patches/common/* 同梱した共通ヘルパー（toString ラベル解決など）
extensions/muse-newx.dex    拡張（アプリ側で動く Java コード）の dex
LICENSE / NOTICE            Piko 由来（GPLv3）
```

## 検証済み（v0.2.1、2026-11-20、Morphe Desktop 2.1.1 CLI、JDK 21 で実行）

- `VERSION=0.2.1 ./build.sh` 成功、`list-patches` で2パッチ表示（初期値オン、
  互換は 12.43.0-prod.01 のみ）
- 本 .mpp **単体**適用: 成功（success=true、failedPatches=[]）。dex 読み戻しで
  全フックの着地を確認（v0.2.0 と同一の位置: 状態ビルダ filter @188、
  markIconSize @76、selectIcon @125、kind ゲート @151、handleEvent @3、
  長押し @3、翻訳 track @0、ブリッジ2件、拡張クラス統合）
- **片方だけ有効**の適用も成功し、フックの分離も従来どおり
  （text のみ: インライン／翻訳あり・長押しなし／media のみ: その逆）
- **Piko 先行適用の再現テスト**（次項のシミュレーション）:
  - シミュレーション＋v0.2.0（旧）: 実機と**同一の例外・同一の
    スタックトレース**（InlineCopyInjection.kt:514）で失敗を再現
  - シミュレーション＋v0.2.1: 3パッチとも適用成功。dex 読み戻しで
    フックの連結を確認（状態ビルダは変換3つ＋フィルタ2段
    （Sim @191 → 本バンドル @197）、markIconSize 2件、selectIcon 2件、
    kind ゲート2件、イベント先頭ブロック2件、長押し・翻訳・メニューは従来どおり）
  - シミュレーション＋Copy post text のみ（exclusive）でも適用成功

### Piko シミュレーションについて（重要）

手元の Piko .mpp（piko-build/piko.mpp、x-repro/piko-3.11.0.mpp）は
`list-patches` で確認したところ NewX パッチを**含まない**旧ビルドのため、
本物の Piko 3.12.0-dev.4 との同時適用はこの環境では再現できません。
代わりに、Piko dev のソース（CustomizeInlineActionsPatch /
InlineDownloadButtonPatch、および v0.2.2 で DynamicColorPatch）から、
共有メソッドへの挿入命令列をそのまま移植した使い捨てバンドル
（`~/workspace/x-12.43/scratch/piko-sim/`、execute フェーズで先に当たる）
で再現しました。v0.2.2 では拡張参照も Piko 実物の記述子に差し替え、
Theme の tint 変換も含めています。**それでもこれはシミュレーション
であり、本物の dev.4 との同時適用を保証するものではありません**
（実機確認が要ります）。実物の Piko は設定読み取りやモデル解決ブリッジ
など、共有メソッドの「数え上げ対象」には影響しない追加の編集も行い
ますが、万一 dev.4 側の挿入形状が想定と違えば、別のマッチャが次に
落ちる可能性は残ります。v0.2.2 のマッチャは、落ちる場合でも候補と
引数レジスタを列挙する診断メッセージを出すようにしてあります。

## 検証済み（v0.2.0 時点、2026-11-20、Morphe Desktop 2.1.1 CLI、JDK 21 で実行）

- `list-patches`: 2パッチとも表示され、初期値オン、互換バージョンは
  com.twitter.android 12.43.0-prod.01 のみ
- 本 .mpp **単体**を 12.43.0-prod.01 の APKM に適用: エラーなく適用完了
  （result.json: success=true、failedPatches=[]）
- 出力 dex の読み戻し（androguard）で全フックの着地を確認:
  - 状態ビルダ `f1;->c` の immutable 変換（`Lcom/x/signing/e;->v`）直後に
    `InlineCopyButton.filter(List, Object)` の呼び出し＋再変換が入っている
    （追加レジスタ v29/v30 を使用、変換結果レジスタの流れも一致）
  - 捕捉レンダラ `Lcom/x/inlineactionbar/k;->q` に `markIconSize` 呼び出し
  - アイコン・ラムダ `Lcom/x/compose/core/w2;->invoke` に `selectIcon` 呼び出し
  - レイアウト・ラムダ `Lcom/x/inlineactionbar/m;->invoke` に kind 上書き用
    `isCopyAction` 呼び出し
  - イベントハンドラ `f1;->d` の先頭（命令3）に `handleEvent` 呼び出し
  - `h5;->invoke` の先頭に instance-of k4 → `MediaLongPress.onMediaLongPress`
    → Unit return の横取り列（k4 以外は命令6以降の元処理へ素通り）
  - `Lcom/x/scribing/urt/c;->R` の命令0に `DisplayedTranslation.track`
    （引数レジスタ v7=h6 post id、v8=TranslatedPost で一致）
  - 拡張ブリッジ: `createCopyAction` 本体 = `new n4(TwitterShare, null, true)`、
    `getPresenterPost` 本体 = check-cast f1 → iget `b` (s1) → return
  - 拡張クラス一式（InlineCopyButton / DisplayedTranslation / MediaLongPress
    ほか）がマージされ、v0.1.0 のメニューフック（j0 コンストラクタの
    addOption、o.invoke の handleOptionAction）も従来どおり入っている
- **片方だけ有効**でも適用できることを確認（v0.1.0 の性質を維持）:
  - Copy post text のみ: 適用成功。インライン／翻訳フック入り、長押しフック無し
  - Copy media URL のみ: 適用成功。長押しフック入り、インライン／翻訳フック無し

## 未検証・既知の制限

- **実機動作は未確認**です（アイコンの描画、ワンタップ／長押しのコピー結果、
  トースト、翻訳文が実際に入るか）。パッチ適用と dex 上の着地までが検証範囲です
- **Piko 本家の InlineDownloadButton との同時適用時のアイコン競合**:
  v0.2.2 までは実際に化けていましたが、v0.2.3 で解消しました
  （「v0.2.3 変更」の 1 を参照。マークの最下位ビット分離＋selector の
  実行順反転）。残る前提はネイティブのアイコンサイズの float 表現の
  最下位ビットが 0 であることで、将来の X 更新でサイズ値が変わると
  再び化ける可能性があります。タップの振り分けは従来通り identity です
- 長押しでネイティブシートを抑止するため、長押し経由の Save Photo 等は
  使えなくなります（§「画像／動画の長押しコピー」参照）
- Piko 本体のポストオプションパッチ（Translator / BrowseObject 等）との
  同時適用は v0.1.0 時点から未検証のままです（同じ3メソッドへの多重フックは
  設計上共存する想定）。インライン系の同時適用については v0.2.1 で
  シミュレーション検証済みですが、本物の dev.4 との同時適用・実機動作は
  未検証です（「Piko シミュレーションについて」参照）
- 動画のインライン長押しに別経路が残る可能性が調査で指摘されています
  （merged lambda の判別ケース）。写真／GIF は k4 経路が命令レベルで
  確認済みですが、動画の長押しだけは実機確認が要ります
- 翻訳 stash のキーは post id 文字列です。R に届く h6 の id と、コピー時に
  timeline post の toString から取る id が一致する前提で、これは dex 上の
  構造からの推定です（実データ未確認）。ミスした場合は原文がコピーされます
  （v0.1.0 と同じ挙動にフォールバック）

## in-tree 実装からの変更点（適応一覧）

v0.1.0 の適応（登録方式、モデル読み取りのリフレクション化、設定 DSL 削除、
互換の単一化、Morphe 2.1.1 API 適応、本文フォールバック追加）は
v0.1.0 の BUILD.md に記録した通りで、本バージョンでも維持しています。
v0.2.0 で追加した適応:

8. **インライン経路**: Piko の CustomizeInlineActions の hidden-set 機構は
   持たず、フィルタは自前の `filter(List, Object)`（コピーエントリ追加のみ）。
   状態ビルダのフレーム拡張は Morphe 2.1.1 の `addFreeRegisters` で代替。
   レンダラは 12.43 では直接形が enabled を読まないため、Piko の
   captured-lambda 解決器（InlineActionRendererResolver）も移植しました
9. **ブリッジの最小化**: InlineDownloadButton のモデルブリッジ群
   （getCanonicalPost 等）は移植せず、presenter → ContextualPost の
   `getPresenterPost` だけをパッチ時ブリッジにし、残りは既存の
   PostModelAccess（リフレクション）が担います。Context は presenter 経由
   ではなく ActivityThread から取るため、Application 初期化フックは不要です
10. **翻訳キャプチャ**: 旧 X（12.19.1）の copy-fix4 と同型の「表示時キャプチャ」
    ですが、NewX では表示判定をプレゼンター側が済ませた結果が R の引数に
    来るため、拡張側の再判定は不要で、track は (id, 翻訳文) の写しのみです
11. **同時適用耐性（v0.2.1）**: Piko の execute フェーズが共有メソッドを
    先に書き換える前提で、状態ビルダ（変換チェーン最後尾へのフック）、
    レンダラ来歴証明（markIconSize の素通し扱い）、アイコン・ラムダ
    （フィールド単位の数え上げ）の3マッチャを先行編集耐性のある形に変更。
    詳細は「v0.2.1 修正」の節を参照
12. **同時適用耐性（v0.2.2）**: Theme（DynamicColorPatch）も同じ捕捉
    レンダラを触ることが判明したため、来歴証明の素通し扱いを
    「第1引数 = 書き戻し先」の in-place 変換一般に広げ、tint／content
    tint／アイコンサイズにレジスタ同一性フォールバックと診断メッセージを
    追加。シミュレーションにも Theme 変換と Piko 実物の拡張記述子を反映。
    詳細は「v0.2.2 修正」の節を参照
13. **v0.2.3 の追加**: (a) アイコンマークを「符号＋仮数部最下位ビット」の
    複合エンコードに変更し、selectIcon フックを Piko の selector の
    move-result 直後へ差し込む順序制御を追加。(b) 通話中再生パッチは
    拡張を使わない単独の bytecode パッチで、リース要求の move-result を
    定数に置換するだけ（InstructionExtensions.replaceInstruction）。
    (c) 長押しアクションは stringOption（Morphe 2.1.1 の PatchBuilder
    拡張）で宣言し、finalize で拡張スタブ本体を書き換える。詳細は
    「v0.2.3 変更」の節を参照
