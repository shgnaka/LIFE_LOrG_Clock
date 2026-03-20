# Sync Peer and Trust Model (Draft)

このドキュメントは、Org Clock の長期同期機能における peer 認識、端末識別、信頼関係、viewer 権限モデルを整理するための設計草案です。

## 1. Purpose

このドキュメントの目的:

- 同期相手をどう識別するかを決める
- 誰を信頼して同期対象にするかを決める
- full peer と viewer peer の権限差を明確にする
- Android / Desktop / iOS / macOS をまたぐ端末モデルを統一する

## 2. Terms

### `device_id`

- アプリが各端末に対して永続的に付与する local stable ID
- event の author として使う
- 端末をまたいで重複しないこと

### `peer_id`

- 他端末を network/trust 上で識別する ID
- v1 では `device_id` と同一でもよい
- 将来的に endpoint rotation や複数 transport を持たせるなら分離余地を残す

### `endpoint`

- その peer に到達するための transport address
- 例:
  - `host:port`
  - Bonjour/mDNS 発見結果
  - 将来の relay route

### `trust record`

- その peer を同期対象として許可した証跡
- peer_id
- 公開鍵
- 登録時刻
- 権限種別

## 3. Peer Categories

### 3.1 Full peer

full peer は次を行える。

- event を送る
- event を受ける
- clock sync の対象になる
- 自身も local event log を真として持つ

対象例:

- Android main device
- Desktop host
- 将来の iOS / macOS main app

### 3.2 Viewer peer

viewer peer は read-only で扱う。

できること:

- active clock / history / projection の読み取り
- host から状態を受け取る

できないこと:

- clock event の author になる
- sync event を他 peer に配布する
- authoritative change を行う

対象例:

- read-only dashboard
- host 状態を見るだけの専用 viewer

## 4. Identity Model

### 4.1 Device identity

各端末は初回起動時に `device_id` を生成し、永続保存する。

要件:

- app reinstall をまたぐかどうかは別途判断
- 少なくとも通常の app lifecycle では stable
- event author として使える

推奨:

- UUIDv4 / UUIDv7 / ULID ベース

### 4.2 Key identity

各 full peer は sync 用の鍵ペアを持つ。

要件:

- private key は local secure storage
- public key は trust record に配布可能
- event transport または envelope verification に利用可能

viewer peer は read-only なので、v1 では full signing key を持たなくてもよい。
ただし host 側の access control が必要なら別の viewer credential を持たせる余地を残す。

## 5. Trust Model

同期に必要な trust の最小要件:

- 相手 peer を明示的に許可していること
- 相手の公開鍵を知っていること
- その peer が full peer か viewer peer かを区別できること

trust record の最小項目:

- `peer_id`
- `display_name`
- `public_key`
- `role`
- `added_at`
- `last_seen_at`
- `revoked_at` optional

## 6. Pairing Flow

理想的な基本フロー:

1. A が B を検出する、または B の登録情報を読み取る
2. A は B の `peer_id` と `public_key` を取得する
3. ユーザーが A 上で B を承認する
4. A に trust record が保存される
5. 必要なら B 側でも A を承認する

最初の実装では、双方向承認でも片方向承認でもよいが、docs 上で固定する。

## 7. Recommended Initial Pairing UX

Phase 1 / Phase 2 の推奨:

- まずは manual pairing を採用する
- 自動 discovery は補助にとどめる

候補:

- peer code の手入力
- QR code
- LAN candidate 検出後の明示承認

理由:

- 誤接続を避けやすい
- trust record を明示的に作れる
- iOS/macOS 参加時も概念が崩れにくい

## 8. Role-Based Authorization

### Full peer authorization

full peer に許可するもの:

- event receive
- event send
- pending event catch-up
- sync cursor 更新

### Viewer peer authorization

viewer peer に許可するもの:

- read-only state fetch
- active clock projection の取得
- history snapshot の取得

viewer peer に禁止するもの:

- clock event publish
- full peer 向けの event push

## 9. Trust Failure Handling

以下は trust failure として扱う:

- unknown peer
- revoked peer
- public key mismatch
- role mismatch

対応:

- event apply を拒否
- local event は失わない
- `sync error` として可視化
- trust を修復した後に再試行可能にする

## 10. Storage Responsibilities

各端末で保持する想定:

- local `device_id`
- local private key
- trusted peer records
- peer public keys
- peer role
- last seen / last success metadata

full peer ではこれに加えて:

- local event log
- sync cursor / last-seen

peer ごとの sync progress は trust record とは分けて保持してよい。
trusted peer の生存情報と event catch-up の cursor は別の責務とみなす。

## 11. Existing Implementation Gap

現状の実装では:

- trusted peer list はある
- peer public key の保存口もある
- しかし UI 上の trust 追加フローは最小限で、公開鍵登録まで一貫していない

このため、長期設計では trust record を first-class に扱う必要がある。

## 11.1 Formalized Model

Shared model では次を正式化した:

- `PeerTrustRole` で `Full` / `Viewer` を区別する
- `PeerRegistrationRequest` で pairing 時の入力を表す
- `PeerTrustRecord` で `peer_id` / `device_id` / `public_key` / `role` / `registered_at` / `last_seen_at` / `revoked_at` を保持する
- register / revoke / repair は pure model operation として表現する

## 12. Open Decisions

- `peer_id == device_id` で固定するか
- viewer peer に専用 credential を持たせるか
- trust record の export/import をどうするか
- pair/unpair をユーザーにどこまで exposed するか

## 13. Related Docs

- `docs/sync-architecture-next.md`
- `docs/sync-migration-plan.md`
- `docs/sync-failure-recovery.md`
