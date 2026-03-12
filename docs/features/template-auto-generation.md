# Template Sync / Auto Generation

## Overview

Org Clock は `:TPL:` タグ付き見出しを `.template.org` に再同期し、必要に応じて `.template.org` から当日用の daily file を自動生成できます。

対象は現時点では Android 実装です。

## `:TPL:` と `.template.org`

- 見出し作成ダイアログで `Add TPL tag` を有効にすると、その見出しは `:TPL:` 付きで作成されます。
- `Sync template` を実行すると、選択中ファイルから `:TPL:` 付きセクションを抽出して `.template.org` にマージします。
- すでに `.template.org` に同じ見出しパスがある場合は、同じパスのセクションを上書きします。
- `:TPL:` が親見出しにも付いている場合、子見出しは独立セクションとしては抽出しません。
- 旧 `template.org` がある場合は、root を開いたタイミングで `.template.org` へ移行します。

## Heading 作成時の挙動

- `Add TPL tag` を付けた heading 作成後は、daily file の保存成功後に `.template.org` 同期を試みます。
- heading 作成は成功したが `.template.org` 同期だけ失敗した場合、UI には warning が表示されます。
- 同期失敗時でも daily file 側の heading 作成結果は保持されます。

## Auto Generation 設定

Settings の `自動生成` セクションから current root ごとに設定を保存できます。

- `Daily`: 毎日同じ時刻に実行
- `Weekly`: 指定曜日の指定時刻に実行
- `Hour` / `Minute`: 24 時間制
- root ごとに enabled / rule / weekday を保存

## Auto Generation の実行条件

- スケジュール時刻を過ぎていて
- 対象 root の設定が enabled で
- 当日の daily file がまだ存在せず
- `.template.org` が存在し、かつ空でない

この条件を満たすと、`.template.org` の内容をそのまま当日ファイルへ複製します。

## 空または未作成の `.template.org`

- `.template.org` が未作成、または空の場合は daily file を生成しません。
- これは no-op として扱われ、既存ファイルも変更しません。

## 手動確認で見るべき点

- `Add TPL tag` 付き heading 作成後に `.template.org` が更新されること
- `Sync template` で既存ファイルの `:TPL:` セクションを再取り込みできること
- `Weekly` 設定では未選択曜日に生成されないこと
- 当日ファイルが既にある場合は上書き生成しないこと
