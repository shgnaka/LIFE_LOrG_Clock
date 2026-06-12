# Template Sharing

Org Clockのテンプレート共有はclock event logとは独立した資産同期として扱う。

## Source of truth

- 各端末はテンプレート本文をローカルのorgテンプレートへ保存する
- Desktopは`.orgclock-template.org`を使用する
- Androidはroot設定で選択されたSAFテンプレートを使用する
- ファイルパスやSAF URIは端末間で共有しない
- revision ID、親revision、本文hash、更新端末を交換する

## Synchronization

既存のTLS LAN transportとペアリングcredentialを使用する。

- `POST /v1/template/fetch`
- `POST /v1/template/push`
- Full peerのみ利用可能
- Viewer peerはテンプレートを取得・更新できない

同期規則:

1. 一方にだけテンプレートがあれば、存在する側から共有する
2. 同じrevisionなら変更しない
3. 一方が他方の直接の子revisionならfast-forwardする
4. 両方が異なるrevisionへ分岐した場合は競合とし、どちらも上書きしない

## User flow

- root選択画面で、選択後に既存テンプレートまたはペア端末のテンプレートを利用できることを案内する
- root選択後、Settingsの`Sync with paired device`から同期する
- 同期にはFull roleのペア端末が必要
- 競合時は自動マージせず、どちらも維持する

## Generated daily files

テンプレート同期は既存の日次orgファイルを書き換えない。
同期後のテンプレートは、以後の日次ファイル生成に使用する。
