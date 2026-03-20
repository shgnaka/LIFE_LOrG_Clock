# Minimum Pairing UX and Trust Handoff (Draft)

このドキュメントは、初期の manual pairing UX と trust handoff を固定するための草案です。

## 1. Goal

- まずは誤接続しにくい最小フローを作る
- full peer と viewer peer を pair 時点で分ける
- trust record を first-class に扱う

## 2. Initial UX Shape

初期 UI は manual pairing を基本にする。

入力として扱うもの:

- peer id
- display name
- public key
- role
- optional endpoint

### UI の考え方

- peer id は手入力または peer code の貼り付けを想定する
- public key はコピペを基本とする
- viewer peer か full peer かを checkbox / toggle で明示する
- endpoint は v1 では必須にしない

## 3. Trust Handoff

最小の trust handoff では、ユーザーは次を確認する。

- peer id が想定した端末のものか
- display name が想定した相手か
- public key が一致しているか
- role が full か viewer か

その後、信頼を保存する。

## 4. Recommended Flow

1. ユーザーが peer id / public key / display name を入力する
2. role を full または viewer に選ぶ
3. app が peer に probe を試みる
4. peer が reachable なら trust record を保存する
5. 保存後、trusted peer list と settings に反映する

## 5. Role Rules

### Full peer

- event send / receive が可能
- pending sync と cursor progress を持つ
- catch-up の対象になる

### Viewer peer

- read-only state を受け取る
- event push / ack は行わない
- host 側 projection を読む

## 6. Data Contract

pairing UI から trust storage に渡す最小データ:

- `peer_id`
- `device_id`
- `display_name`
- `public_key`
- `role`
- `endpoint` optional
- `requested_at`

現在の shared model では `PeerPairingDraft` から `PeerRegistrationRequest` に変換して使う。

## 7. Current Implementation Alignment

現時点の実装は、この最小 UX に概ね一致している。

- Android UI に peer id / display name / public key / viewer mode がある
- `SyncIntegrationService.pairTrustedPeer()` が trust record 保存を行う
- viewer peer は role で判定される
- endpoint は v1 では必須でない

