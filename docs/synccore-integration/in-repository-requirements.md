# In-repository sync-core Requirements and Acceptance Tests

## 1. Status

- Status: Requirements Baseline v1 (Gate 5 implementation complete)
- Decision: sync-core を外部リポジトリから取得せず、このリポジトリで実装・保守する
- Current runtime: in-repository `:sync-core` module と Android/Desktop host adapter
- Migration rule: 外部方式は Gate 5 完了後の通常 build/runtime から削除済み
- Scope owner: Org Clock repository
- Requirement keywords: `MUST`, `MUST NOT`, `SHOULD`, `MAY` は RFC 2119 相当の強さで解釈する
- Numbered requirement (`AR-*` から `QR-*`) は、本文で `SHOULD` / `MAY` と明記しない限りすべて `MUST`

## 2. Goal

Org Clock が必要とする LAN 同期機能を、このリポジトリだけでビルド、テスト、変更できる状態にする。

sync-core は command の意味を解釈しない汎用 message delivery 基盤とする。`topic + payload` を安全かつ永続的に peer 間配送し、command/event/template の意味論と適用処理は上位層が担当する。

内製 sync-core は次を担当する。

- outgoing message の永続キュー
- dispatch、retry、delivery state 管理
- incoming message の検証、永続 inbox、上位層への引き渡し
- peer trust、署名、replay protection
- runtime lifecycle と metrics
- Android と Desktop で共有する transport 非依存の同期ロジック

### 2.1 Success criteria

次をすべて満たした時点で内製化完了とする。

1. 本文書の `MUST` requirement に対応する acceptance test がすべて green
2. 外部 sync-core がなくても Android/desktop の required build gate が green
3. 既存 wire format と Android DB data を移行できる
4. sync 無効時の local clock behavior に回帰がない
5. `SYNC_CORE_DIR`、外部 Maven 座標、composite build を削除済み

## 3. Non-goals

- NAT traversal、STUN/TURN、relay
- internet 経由の同期
- 中央同期サーバー
- pairing UX の全面再設計
- `clock.command.v1` / `clock.result.v1` の破壊的変更
- clock event sync、template sync の同時移行
- payload 内容の conflict resolution や domain validation
- peer discovery protocol の新規設計

汎用 message API は将来の event/template topic を運べる形にするが、この移行で既存の event/template transport を置き換えることは求めない。

## 4. System Boundary and Terms

### 4.1 Core model

sync-core が扱う outgoing message は最低限、次を持つ。

| Field | Requirement |
|---|---|
| `messageId` | 空でない一意な idempotency key |
| `topic` | 空でない versioned topic。例: `clock.command.v1` |
| `payloadJson` | UTF-8 JSON。core は意味を解釈しない |
| `targetPeerId` | trust store で解決可能な peer |
| `createdAtEpochMs` | UTC epoch milliseconds |
| `expiresAtEpochMs` | optional。指定時は created time より後 |

既存 `SyncCommand.commandId` は移行時に `messageId` と同一視する。

### 4.2 Delivery terms

- `transport accepted`: remote ingress が検証後に durable inbox へ保存した状態
- `domain applied`: 上位層が payload を適用し、必要なら result message を返した状態
- `acked`: transport accepted を表し、domain applied を意味しない
- `terminal`: `acked`, `rejected`, `failed`, `expired`, `cancelled` のいずれか

### 4.3 Delivery guarantee

- outgoing transport は **at-least-once delivery** とする
- crash、timeout、応答消失により同一 `messageId` が再送されることを許容する
- incoming durable inbox は同一 `senderPeerId + messageId` を一度だけ上位層へ公開する
- domain side effect の effectively-once 性は、既存 `CommandIdStore` など上位層の idempotency と組み合わせて保証する

### 4.4 Trust boundary

- sync-core は envelope authenticity、peer trust、replay、timestamp、resource limit を検証する
- 上位層は payload schema、domain permission、target existence、business rule を検証する
- transport acceptance は payload の domain validity を保証しない

## 5. Architecture Requirements

### AR-01 Repository ownership

sync-core の本体、契約、テストはこのリポジトリに置く。通常のビルドに外部 sync-core の checkout、`SYNC_CORE_DIR`、snapshot artifact を要求しない。

### AR-02 Module boundary

transport 非依存の API と engine は Android API に依存しない。Android 固有の Room、Keystore、HTTP server 実装は platform adapter として分離する。

採用する初期構成:

- `:sync-core`: Kotlin Multiplatform module
  - `commonMain/api`: message、delivery、error、metrics の public contract
  - `commonMain/engine`: queue orchestration、retry、state machine
  - `commonTest`: engine contract tests
- Android host adapter: Room、Android Keystore、LAN ingress/egress
- Desktop host adapter: JDBC/file-backed store、TLS identity、LAN ingress/egress

API と engine を別 Gradle module へ分割するのは、独立 release/versioning が必要になった時点まで行わない。

依存方向は `host app -> platform adapter -> :sync-core` とし、`:sync-core` から `app`, `desktopApp`, Android SDK、Room へ依存してはならない。

### AR-03 Existing app boundary

`OrgSyncCoreClient` をアプリ側の境界として維持する。domain、UI、repository は sync-core の内部型へ直接依存しない。

### AR-04 Build independence

外部 sync-core が存在しない環境で、少なくとも次が成功すること。

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :desktopApp:test
```

### AR-05 Platform delivery scope

- Android command-sync adapter の置換を外部 dependency 削除の必須範囲とする
- `:sync-core` は初回から Android/JVM target で compile/test する
- Desktop の既存 event/template transport はこの移行では置換しない
- Desktop sync-core adapter は別 gate で追加可能だが、core API に Android 固有前提を持ち込まない

### AR-06 Dependency and API control

- public API は `api` package に限定する
- serialization format と enum wire value を golden fixture で固定する
- wall clock、random、dispatcher、store、transport は interface 注入可能にする
- engine unit test は Android runtime、実 DB、実 socket を要求してはならない

## 6. Functional Requirements

### FR-01 Submit

有効な outgoing message を durable queue へ transactionally 登録する。

- blank id/topic/peer、invalid timestamp、payload size 超過は保存前に拒否する
- 同一 `messageId` かつ同一内容の再登録は idempotent success とする
- 同一 `messageId` で内容が異なる再登録は `MESSAGE_ID_CONFLICT` として拒否する
- submit 成功は durable write 完了後にのみ返す

### FR-02 Durable queue

登録済み message は process restart 後も失われない。terminal state に到達するまで再送対象として保持する。message、state、attempt count、next attempt、last error は同一 storage transaction の整合性を保つ。

### FR-03 Dispatch result

dispatch 結果を以下へ分類する。

- accepted: terminal success
- rejected: terminal failure
- retryable failure: retry 待ち

`accepted` は remote durable inbox への保存を意味する。remote domain execution の成功は別の result topic で表す。

### FR-04 Retry

retryable failure は bounded exponential backoff で再試行する。retry count と次回実行時刻を永続化する。再起動で backoff 状態を失わない。

初期既定値:

- initial delay: 1 second
- multiplier: 2
- maximum delay: 60 seconds
- jitter: 0-20% additive
- automatic retry limit: 10 attempts

上記値は設定可能にし、テストでは clock と random source を注入する。

- attempt 1 は submit/flush 時の初回 dispatch とする
- retry limit 10 は合計10回の dispatch attempt を意味する
- limit 到達後は `failed` + `RETRY_EXHAUSTED` とする
- retryable classification は timeout、connection failure、HTTP 408/425/429/5xx
- authentication、trust、schema、unsupported protocol、HTTP 400/401/403/404/409/413/422 は automatic retry しない

### FR-05 Expiration

期限切れ message は dispatch せず `expired` terminal state にする。dispatch 中に期限を超えた場合、その attempt の応答は処理するが追加 retry は行わない。

- core API 自体は `expiresAtEpochMs` optional を維持する
- 新規 `clock.command.v1` submit は adapter が24 hoursの expirationを必ず設定する
- 新規 `clock.result.v1` submit は adapter が7 daysの expirationを設定する
- legacy row/message に expiration がない場合は互換性のため無期限として読み込むが、新規作成では使用しない

### FR-06 Delivery state

最低限、次の state を typed value として観測可能にする。

- `pending`
- `dispatching`
- `retry_wait`
- `acked`
- `rejected`
- `failed`
- `expired`
- `cancelled`

許可する主な transition:

```text
pending -> dispatching
dispatching -> acked | rejected | retry_wait | failed | expired
retry_wait -> dispatching | cancelled | expired
pending -> cancelled | expired
failed -> pending  (explicit manual retry only)
```

state transition、attempt number、error code、sanitized detail、occurred time を永続履歴へ記録する。不正な transition は storage を変更せず error とする。

### FR-07 Incoming delivery

検証済み incoming message は HTTP success を返す前に durable inbox へ保存する。

- inbox key は `senderPeerId + messageId`
- 同一内容の再配送は idempotent success とし、上位層へ再公開しない
- 同じ key で内容が異なる場合は conflict として拒否する
- `OrgSyncCoreClient` への引き渡し前に process が停止しても、再起動後に未処理 message を公開する
- 未検証、署名不正、期限外、replay、上限超過 message は inbox に保存せず domain execution へ渡さない
- 上位層の処理完了 ack を受けるまで inbox row を削除しない

### FR-08 Result routing

incoming command の送信 peer と topic を durable inbox metadata として記録し、対応する `clock.result.v1` を同じ peer へ返す。

- process restart 後も result route を解決できる
- route は result submit が durable queue に成功するまで保持する
- route が不明な result は送信せず `RESULT_ROUTE_NOT_FOUND` として観測可能にする
- result の outgoing `messageId` は再試行しても安定させる

### FR-09 Lifecycle

`start` と `stop` は冪等である。二重 start で worker や HTTP listener を重複起動しない。stop 後に新規 dispatch を開始しない。

### FR-10 Manual flush

`flushNow` は現在 due の message を即時処理する。同時呼び出しでも同一 message を並列 dispatch しない。

### FR-11 Ordering and concurrency

- 同一 target peer への message は queue registration 順の FIFO を既定とする
- 先頭 message が `retry_wait` の間、後続 message は追い越さない
- target peer が異なる message は並列 dispatch 可能とする
- peer ごとの in-flight 上限は1、全体の既定上限は4とする
- ordering key を将来追加できる設計は許容するが、v1 public API には公開しない

### FR-12 Crash recovery and lease

dispatch 前に message を `dispatching` とし、lease deadline を永続化する。process crash 後、期限切れ lease は `pending` に回収して再配送する。

- lease の既定値は30 seconds
- lease 回収後の再配送は attempt count に含める
- at-least-once のため、crash 境界で duplicate delivery が発生し得る

### FR-13 Manual retry and cancellation

- retry exhausted の `failed` message は明示的な manual retry を許可する
- manual retry は同じ `messageId` を維持し、attempt count を0へ戻して新しい delivery event を追加する
- `pending` / `retry_wait` は手動 cancel 可能とする
- `dispatching` の cancel は現在の I/O を強制中断せず、応答後の追加 retry を抑止する
- terminal message の cancel は idempotent no-op とする

### FR-14 Queue inspection

上位層は message payload を再parseせず、message id、topic、peer、state、attempt、created time、next attempt、last error を page 単位で取得できる。

### FR-15 Store failure behavior

- queue/inbox write 失敗時は成功を返さない
- state update 失敗時は in-memory state だけを進めない
- health check failure は sync runtime を開始せず、typed persistence error を返す
- store failure が local domain operation を rollback させてはならない

### FR-16 Required core ports

public contract は特定 framework 型を公開せず、最低限次の capability を提供する。

```text
submit(message) -> SubmitOutcome
cancel(messageId) -> CancelOutcome
retry(messageId) -> RetryOutcome
flushDue() -> FlushSummary
start() / stop()
observeDeliveryEvents() -> stream
listOutgoing(query) -> page
receiveVerified(envelope) -> IngressOutcome
claimIncoming(limit) -> ClaimIncomingOutcome
ackIncoming(receiptId, outcome) -> AckIncomingOutcome
metricsSnapshot() -> metrics
healthCheck() -> health
```

- stream の backpressure 方針を明示し、observer の遅延で engine dispatch を停止させない
- `SubmitOutcome`, `IngressOutcome` 等は stable error code を持つ typed result とする
- exception は programming error または cancellation に限定し、通常の delivery failure を表現するために使わない

### FR-17 Incoming processing outcome

上位層は incoming message の処理結果を `processed`, `rejected`, `retry_later` で ack する。

- `processed` / `rejected` は inbox terminal
- `retry_later` は上位層への再公開対象
- domain validation failure は transport sender への result message で通知し、transport retry を要求しない
- processing lease の既定値は60 seconds とし、consumer crash 後は再公開する

### FR-18 Runtime modes

- `Off`: listener、background dispatcher、scheduled flush を停止する
- `Standard`: listener を有効化し、WorkManager 等による15分周期と手動 flush を利用する
- `Active`: listener と継続 dispatcher を有効化し、due message を既定1秒周期で確認する
- mode transition は冪等で、旧 mode の worker/service を残さない
- app process 起動時は persisted setting と build feature flag の両方を満たす場合だけ自動開始する

### FR-19 Ingress protocol outcomes

HTTP ingress は durable outcome を次のように返す。

| Condition | Status | Retry semantics |
|---|---:|---|
| new message committed | 202 | terminal transport success |
| same sender/id/content already committed | 202 | terminal transport success |
| malformed envelope/unsupported schema | 400 | no automatic retry |
| untrusted peer/invalid signature | 401 | no automatic retry |
| peer role not authorized for topic | 403 | no automatic retry |
| same sender/id with different content | 409 | no automatic retry |
| body/payload too large | 413 | no automatic retry |
| source rate limited | 429 | retry using `Retry-After` |
| inbox full/store temporarily unavailable | 503 | retry using `Retry-After` |

success body は機密情報を含まず、sender が domain application 完了と誤認する表現を使わない。

### FR-20 Topic policy

host は supported topic と peer role/capability の対応を `TopicPolicy` port としてcoreへ渡す。

- unsupported topic は durable inbox 保存前に拒否する
- topic policy は payload body を解釈せず、topic、peer identity、role/capabilityだけで判定する
- v1 command adapter は `clock.command.v1` をFull peerにのみ許可する
- result topic は対応routeを持つtrusted peerからのみ許可する

## 7. Security Requirements

### SR-01 Trusted peer only

incoming message は登録済み peer key と `alg` による envelope signature 検証成功後のみ受理する。標準署名方式は P-256 / ECDSA / SHA-256 の `ES256` とし、Ed25519 は対応端末または legacy peer の optional 方式としてのみ扱う。pairing secret や接続元IPだけを message authenticity の根拠にしてはならない。

- release build の network ingress に unsigned message endpoint を設けない
- legacy `/v1/incoming-command` は削除するか、debug build + loopback bind + 明示設定の全条件を満たす test endpoint に限定する
- Activity extra など process 内の manual debug path は network ingress と分離する

### SR-02 Replay protection

`senderPeerId + messageId` を replay key とし、process restart 後も重複を識別する。正当な同一内容の再配送には idempotent success を返し、内容が異なる再利用は拒否する。

### SR-03 Timestamp validation

許容 clock skew 外の envelope を拒否する。既定は過去・未来とも 300 seconds とし、設定可能範囲は 30-900 seconds とする。timestamp 判定には注入可能な UTC clock を使う。

- skew 判定対象は署名済み envelope の `sentAtEpochMs`
- `clock.command.v1.requested_at` など payload 内の domain timestamp は上位層が形式・意味を検証し、transport replay 判定には使用しない
- retry 時は original domain payload を変えず、新しい envelope id/nonce/sent time/signature を生成する

### SR-04 Transport security

通常運用では TLS 1.2 以上を必須とし、cleartext HTTP endpoint を受理しない。

- pairing で取得した certificate SHA-256 fingerprint を pinning に利用する
- hostname/IP の一致だけで self-signed certificate を信頼しない
- certificate pin 不一致は automatic retry しない
- debug build でも明示的な test adapter 以外に trust-all mode を設けない
- message envelope signature は TLS と独立して必須とする

### SR-05 Resource limits

body size、request rate、queue/inbox depth、delivery history に上限を設ける。上限超過は安全側に拒否し、process crash や無制限 growth を起こさない。

初期既定値:

| Limit | Default | Behavior on overflow |
|---|---:|---|
| encoded HTTP body | 128 KiB | request reject |
| decoded `payloadJson` | 96 KiB | message reject |
| requests per source IP | 120/minute | rate-limit reject |
| tracked rate-limit source keys | 256 | oldest inactive key evict |
| pending outgoing total | 2,000 | new submit reject |
| pending outgoing per peer | 500 | new submit reject |
| durable inbox unprocessed | 2,000 | ingress busy/retryable response |
| in-memory delivery snapshot | 100 | oldest snapshot evict |
| result route records | 20,000 | terminal/expired oldest record prune only |

pending/unprocessed data を capacity 確保のために自動削除してはならない。

### SR-06 Secret handling

private key をログ、DB、payload に平文保存しない。Android private key は Android Keystore で管理する。

- Desktop private key は OS-protected file permission と encrypted-at-rest storage を使用する
- pairing secret、signature、full payload、private endpoint credential を通常ログへ出さない
- error detail は最大512 characters に制限し、HTTP response body は保存前に sanitize する

### SR-07 Canonical signature input

署名対象は field order に依存しない versioned canonical representation とする。少なくとも schema version、message type、message id、sender peer id、target peer id、topic、sent time、nonce、payload hash を含める。

既存 envelope との wire compatibility 期間は既存 canonical form を受理する。新 canonical form を導入する場合は schema version を上げ、downgrade ambiguity を許可しない。

### SR-08 Peer authorization

trusted peer ごとに role/capability を保持し、topic の受信可否を上位層が判定できる peer identity と role を渡す。viewer peer から mutation command を受理してはならない。

## 8. Data Retention Requirements

### DR-01 Retention defaults

| Data | Retention | Maximum rows | Prune rule |
|---|---:|---:|---|
| replay/inbox dedupe key | 7 days | 100,000 | expired then oldest |
| processed command id | 90 days | 100,000 | expired then oldest |
| terminal outgoing message | 7 days | 20,000 | terminal only |
| delivery event history | 30 days | 100,000 | oldest terminal history |
| result route | 7 days after result terminal | 20,000 | resolved/expired only |
| pending/retry/dispatching outgoing | no TTL except message expiration | 2,000 capacity | never retention-prune |
| unprocessed incoming | no retention prune | 2,000 capacity | explicit processing required |

### DR-02 Cleanup

- cleanup は transactionally 実行する
- active queue/inbox row を削除してはならない
- cleanup failure は sync delivery を停止させず metrics/error に記録する
- clock は注入可能とし retention test を deterministic にする

### DR-03 User data deletion

peer revoke 時は、その peer の credential/private routing metadata を無効化する。未配送 queue の扱いは自動削除ではなく `rejected` terminal state への遷移とし、監査履歴を保持する。

## 9. Observability Requirements

### OR-01 Metrics

最低限、次を snapshot として取得できること。

- submitted total
- accepted total
- rejected total
- retry attempts total
- queue depth
- oldest pending age
- incoming rejected total
- inbox unprocessed depth
- expired lease recovery total
- persistence error total
- last successful dispatch time per peer

### OR-02 Error visibility

直近の transport、validation、persistence error を機密情報なしで確認できること。

最低限の stable error code:

| Category | Codes |
|---|---|
| validation | `INVALID_MESSAGE`, `PAYLOAD_TOO_LARGE`, `MESSAGE_ID_CONFLICT` |
| trust/security | `PEER_NOT_TRUSTED`, `SIGNATURE_INVALID`, `TIMESTAMP_OUT_OF_RANGE`, `REPLAY_CONFLICT`, `CERTIFICATE_PIN_MISMATCH`, `PEER_NOT_AUTHORIZED` |
| capacity | `QUEUE_FULL`, `INBOX_FULL`, `RATE_LIMITED` |
| transport | `NETWORK_UNREACHABLE`, `TIMEOUT`, `REMOTE_BUSY`, `PROTOCOL_ERROR` |
| lifecycle | `EXPIRED`, `CANCELLED`, `RETRY_EXHAUSTED`, `RESULT_ROUTE_NOT_FOUND` |
| persistence | `STORE_UNAVAILABLE`, `STORE_WRITE_FAILED`, `STORE_READ_FAILED`, `MIGRATION_FAILED` |

### OR-03 Structured state

UI はログ文字列を parse せず、typed delivery state と metrics を利用する。

### OR-04 Logging

structured log は message id の短縮/hashed representation、topic、peer の非機密識別子、state、error code、duration を含めてよい。payload、signature、pairing secret、private key、full credential は含めてはならない。

## 10. Compatibility Requirements

### CR-01 Wire compatibility

既存の `clock.command.v1`, `clock.result.v1`, signed envelope と互換性を維持する。

- current production fixture を byte-level golden input として保存する
- decoder は既存 field name、enum value、timestamp unit を受理する
- encoder output は既存 peer が解釈できる
- unknown optional field は無視し、unknown required schema version は拒否する
- wire schema version と domain topic version を別に管理する

### CR-02 Stored data migration

既存の `orgclock_sync_queue.db` を利用中の端末で、pending queue、processed result、replay registry を失わない。

- Room schema version 1 と2の fixture から migration test を行う
- migration は pending/retry count/next retry/error、processed result、delivery event、replay key を保持する
- destructive migration fallback を使用しない
- migration failure 時は旧DBを削除・上書きせず `MIGRATION_FAILED` とする

### CR-03 Disabled regression

sync integration が無効な場合、local clock、notification、file persistence の動作を変えない。

### CR-04 Failure isolation

sync-core の初期化または永続化が失敗しても、アプリの local clock 操作を利用可能に保つ。

### CR-05 App boundary compatibility

移行期間中は `OrgSyncCoreClient` の既存 observable behavior を維持する。

- `submitOutgoing`
- `observeIncomingCommands`
- `reportResult`
- `observeDeliveryState`
- `metricsSnapshot`
- lifecycle methods

外部/内製 implementation を同じ contract test suite に通し、composition root の切替以外で domain/UI code を変更しない。

### CR-06 Protocol negotiation

v1 は明示的 negotiation を追加せず、unsupported envelope schema/topic は stable error で拒否する。将来の capability negotiation を妨げないよう、peer metadata に supported protocol versions を追加可能とする。

## 11. Quality Requirements

### QR-01 Determinism

engine test は fake UTC clock、fake random、fake transport、in-memory store で完全に再現可能である。実時間 `delay` に依存しない。

### QR-02 Performance

release 相当の JVM/Android local test environment で次を目標とする。

- 2,000件 queue から due 100件を取得: p95 100 ms以下
- 128 KiB envelope validation: p95 50 ms以下
- submit durable write: p95 100 ms以下（端末I/O異常時を除く）
- engine が idle のとき busy loop を行わない

性能値は correctness gate ではなく regression threshold とし、測定環境を結果に記録する。

### QR-03 Cancellation

coroutine cancellation を握りつぶさない。network/store operation は cancellation を伝播し、transaction boundary を壊さない。

### QR-04 Thread safety

public operation は複数 coroutine から呼び出し可能とする。message 単位の state transition は serialized で、observer callback を store lock 内から呼ばない。

### QR-05 Testability

core public behavior の各 `MUST` requirement は automated test ID を持つ。platform security のうち自動化困難な項目は明示的な inspection procedure と証跡を持つ。

## 12. Test Strategy

テストは fake clock、fake dispatcher、in-memory store を使う deterministic unit test を中心にする。実時間 sleep、実ネットワーク、固定 port に依存するテストは integration test に限定する。

各 test ID の normative setup、operation、oracle、test owner は
`docs/synccore-integration/in-repository-test-spec.md` で定義する。
本書の表は requirement traceability の索引であり、両文書が矛盾する場合は
requirement と期待結果は本書、test execution detail は test specification を
優先する。

### Compatibility fixture baseline

現行外部実装との互換性入力は
`app/src/test/resources/synccore/legacy-v1/` に固定する。

| Fixture | Purpose |
|---|---|
| `clock-command-payload.json` | `clock.command.v1` field/value baseline |
| `clock-result-payload.json` | `clock.result.v1` field/value baseline |
| `command-envelope.json` | legacy envelope field names and enum values |
| `command-envelope.canonical.txt` | exact legacy signature input |
| `schema-v1.sql` | Room queue schema before replay persistence |
| `migration-1-2.sql` | additive replay-registry migration |
| `schema-v2.sql` | Room queue schema after replay persistence |

`SyncCoreLegacyFixtureTest` は通常の `:app:testDebugUnitTest` でfixtureの
field、canonical form、additive schema migrationを検証する。内製decoderと
Room migration testはこのfixtureを複製せず直接読み込む。

### Architecture tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| ARC-01 | AR-01, AR-04 | external checkout/artifact なしで required build | 全 gate 成功 |
| ARC-02 | AR-02 | `:sync-core` dependency inspection | Android SDK/Room/app 依存なし |
| ARC-03 | AR-02, AR-06 | public API inspection | `api` package 以外は external access 不可 |
| ARC-04 | AR-05 | Android/JVM compile and common tests | 両 target 成功 |
| ARC-05 | AR-03 | app dependency graph | domain/UI は core internal type 非依存 |
| ARC-06 | AR-06, QR-01 | common engine tests | Android runtime/socket/実DBなしで成功 |

### Engine unit tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| ENG-01 | FR-01 | valid message submit | durable pending 1件、成功はwrite後 |
| ENG-02 | FR-01 | same id/same content submit | idempotent success、queue 1件 |
| ENG-03 | FR-01 | same id/different content | `MESSAGE_ID_CONFLICT`、既存row不変 |
| ENG-04 | FR-01, SR-05 | invalid field/payload limit | 保存せずtyped reject |
| ENG-05 | FR-03 | dispatcher accepted | `dispatching -> acked` |
| ENG-06 | FR-03 | dispatcher rejected | retryせず `rejected` |
| ENG-07 | FR-04 | retryable failure | attempt とjitter込みnext time更新 |
| ENG-08 | FR-04 | before/at next time | 直前は未送信、時刻到達で再送 |
| ENG-09 | FR-04 | each HTTP/error class | retryable/terminal分類が表通り |
| ENG-10 | FR-04 | attempt 10 failure | `failed/RETRY_EXHAUSTED` |
| ENG-11 | FR-05 | expired before dispatch | transport未呼出、`expired` |
| ENG-12 | FR-05 | expires during attempt | 応答処理後に追加retryなし |
| ENG-13 | FR-06 | all valid transitions | event metadata とstateが一致 |
| ENG-14 | FR-06 | invalid transition | store不変、typed error |
| ENG-15 | FR-09 | repeated start/stop | worker/listener 最大1つ |
| ENG-16 | FR-10 | concurrent flush | 同一message同時dispatchなし |
| ENG-17 | FR-11 | same peer three messages | FIFO、先頭retry中は追越しなし |
| ENG-18 | FR-11 | different peers | peerごと1、全体最大4で並列 |
| ENG-19 | FR-12 | restart with expired lease | pending回収後に再送、metric増加 |
| ENG-20 | FR-12 | restart with live lease | lease expiry前は再送しない |
| ENG-21 | FR-13 | manual retry failed message | same id、attempt reset、event追加 |
| ENG-22 | FR-13 | cancel pending/retry/dispatching | 規定のcancel behavior |
| ENG-23 | FR-14 | paged query/filter | payload parseなしで正しいpage |
| ENG-24 | FR-15 | write/state update failure | 成功を返さずmemoryだけ進めない |
| ENG-25 | FR-16 | slow/failed observer | dispatch継続、event loss方針通り |
| ENG-26 | FR-17 | processing lease expiry | incomingを再公開 |
| ENG-27 | FR-18 | Off/Standard/Active transitions | worker周期とlistenerが規定通り |
| ENG-28 | OR-01 | mixed outcomes | 全metricがstore結果と一致 |
| ENG-29 | SR-05 | global/per-peer queue limit | pendingを消さず新規submit拒否 |
| ENG-30 | QR-03, QR-04 | cancellation/concurrent calls | cancellation伝播、state破損なし |
| ENG-31 | OR-02 | each failure category | stable typed error codeとsanitized detail |
| ENG-32 | OR-03 | delivery/metrics observation | UI向けtyped model、log parse不要 |

### Ingress and security tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| ING-01 | FR-07 | verified new envelope | durable inbox commit後にsuccess |
| ING-02 | FR-07 | crash before consumer ack | restart後に再公開 |
| ING-03 | FR-07, SR-02 | same sender/id/content replay | success、再公開なし |
| ING-04 | FR-07, SR-02 | same sender/id/different content | `REPLAY_CONFLICT` |
| ING-05 | FR-17 | processed/rejected/retry_later ack | terminal/再公開が規定通り |
| ING-06 | FR-08 | restart before result | route復元、元peerへsubmit |
| ING-07 | FR-08 | missing route | `RESULT_ROUTE_NOT_FOUND` |
| ING-08 | FR-19 | each ingress outcome | status/retry semanticsが表通り |
| ING-09 | FR-20 | supported/unsupported topic by role | 保存前にpolicy判定 |
| SEC-01 | SR-01 | trusted valid ES256 signature | inboxへ受理 |
| SEC-02 | SR-01 | unknown/revoked peer | `PEER_NOT_TRUSTED` |
| SEC-03 | SR-01 | invalid signature/payload tamper | `SIGNATURE_INVALID` |
| SEC-04 | SR-03 | envelope timestamps at/outside boundary | 境界内受理、外側拒否 |
| SEC-05 | SR-04 | cleartext endpoint | network前に拒否 |
| SEC-06 | SR-04 | matching/mismatching certificate pin | matchのみ接続 |
| SEC-07 | SR-05 | encoded/decoded size boundaries | 上限内受理、超過拒否 |
| SEC-08 | SR-05 | 120/121 requests per minute | 120受理、121はrate limit |
| SEC-09 | SR-05 | inbox full | active rowを消さずretryable busy |
| SEC-10 | SR-06, OR-04 | logs/DB/error detail inspection | secret/full payloadなし、512文字以下 |
| SEC-11 | SR-07 | reordered JSON fields | canonical signature verification成功 |
| SEC-12 | SR-07, CR-01 | legacy/new schema fixtures | versionごとに正しく検証 |
| SEC-13 | SR-08 | viewer sends mutation topic | `PEER_NOT_AUTHORIZED` |
| SEC-14 | SR-01 | release/debug unsigned endpoint access | release不存在、debugもloopback+opt-inのみ |
| SEC-15 | SR-03 | old domain time with fresh signed envelope | transport受理、domain validationへ委譲 |

### Persistence and retention tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| DB-01 | FR-02 | reopen database | message/state/attempt/errorを復元 |
| DB-02 | FR-04 | reopen during retry wait | next attemptを保持 |
| DB-03 | FR-07 | reopen with unprocessed inbox | messageとrouteを復元 |
| DB-04 | CR-02 | migrate Room v1 fixture | 全既存row保持 |
| DB-05 | CR-02 | migrate Room v2 fixture | 全fieldとreplay key保持 |
| DB-06 | CR-02 | forced migration failure | old DB不変、`MIGRATION_FAILED` |
| DB-07 | DR-01, DR-02 | retention boundary/max rows | expired terminalのみ規定通り削除 |
| DB-08 | DR-02 | cleanup with active rows | pending/inbox rowを削除しない |
| DB-09 | DR-02 | cleanup failure | delivery継続、error metric増加 |
| DB-10 | DR-03 | peer revoke | credential無効、queueはrejected履歴化 |
| DB-11 | SR-05 | capacity under concurrent writes | limit超過せずactive data損失なし |

### Identity and pairing migration tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| IDN-01 | SR-01, SR-06 | legacy HTTPS credential value | transport credentialとして分類し署名鍵に使用しない |
| IDN-02 | SR-01 | legacy Ed25519 public key | optional/legacy signing keyとして保持し `signingAlg=Ed25519` を記録 |
| IDN-03 | SR-01 | invitation/malformed legacy value | incompleteとしてcommand ingressを許可しない |
| IDN-04 | SR-01, SR-04 | pairing v2 exchange | 両hostが相手の署名鍵とtransport credentialを分離保存 |
| IDN-05 | SR-08 | requested role reduction | hostは権限を縮小可能、暗黙昇格不可 |
| IDN-06 | SR-01 | peer signing key changes | repair拒否、re-pairを要求 |
| IDN-07 | SR-01, DR-03 | peer revoke | signing trustとtransport credentialを両方無効化 |
| IDN-08 | SR-06 | credential storage migration | protected storageへ移し旧plaintext duplicateを削除 |

### Compatibility and integration tests

| ID | Requirement | Scenario | Expected result |
|---|---|---|---|
| INT-01 | CR-01 | existing command golden fixture receive | 同じmessage/topic/payloadとして解釈 |
| INT-02 | CR-01 | existing result golden fixture send | legacy peerがdecode可能 |
| INT-03 | CR-01, SR-07 | unknown optional/required version | optional無視、unknown version拒否 |
| INT-04 | FR-08 | command receive then result | restartを挟んでも元peerへresult |
| INT-05 | FR-09, FR-18 | runtime start/stop/mode switch | listener/workerが重複せず追従 |
| INT-06 | CR-04, FR-15 | sync DB unavailable | local clock成功、sync typed error |
| INT-07 | CR-05 | external/internal client contract suite | observable behavior一致 |
| INT-08 | CR-03 | integration disabled | local UI/notification/file behavior不変 |
| INT-09 | CR-06 | unsupported topic/schema | stable reject、process継続 |
| INT-10 | AR-04 | no external repo/artifact | Android/desktop required Gradle gate成功 |
| INT-11 | FR-05 | new command/result submit | 24h/7d expirationをadapterが設定 |

### Quality and inspection checks

| ID | Requirement | Check | Pass condition |
|---|---|---|---|
| QUA-01 | QR-01 | test source scan and execution | core testに実時間sleep/実socketなし |
| QUA-02 | QR-02 | queue/validation/store benchmark | threshold内、環境情報記録 |
| QUA-03 | QR-03 | cancellation fault injection | cancellationをfailureへ変換しない |
| QUA-04 | QR-04 | concurrency stress test | duplicate in-flight/state corruptionなし |
| QUA-05 | QR-05 | requirement/test mapping audit | 全numbered requirementに証跡あり |
| INS-01 | SR-06 | Android keystore inspection | private key export/DB保存なし |
| INS-02 | SR-06 | Desktop key storage inspection | permission/encryption要件を満たす |
| INS-03 | AR-01 | repository/dependency search | external sync-core参照なし |
| INS-04 | OR-04 | log capture with synthetic secrets | prohibited dataが出力されない |

### Existing regression suite

`docs/synccore-integration/test-acceptance.md` の `CV`, `AD`, `RS`, `ID`, `RG` シナリオは本計画の一部としてすべて維持する。

## 13. Traceability

各 requirement group の必須証跡:

| Requirement group | Primary evidence |
|---|---|
| AR | `ARC-*`, `INT-10`, `INS-03` |
| FR-01..06 | `ENG-01..14` |
| FR-07..08, FR-17 | `ING-*`, `DB-03`, `INT-04` |
| FR-09..16, FR-18..20 | `ENG-15..32`, `ING-08..09`, `INT-05..07` |
| SR | `SEC-*`, `IDN-*`, `INS-01`, `INS-02`, `INS-04`, security-loop |
| DR | `DB-07..10` |
| OR | `ENG-28`, `SEC-10`, `INS-04` |
| CR | `DB-04..06`, `INT-*` |
| QR | `QUA-*`, `ARC-06` |

Requirement を追加・変更するPRは、同じPRで対応test IDとtraceabilityを更新しなければならない。

## 14. Migration Gates

### Gate 1: Requirements reviewed

- [x] 本文書を `Requirements Baseline v1` として技術レビュー・固定済み
- [x] retry、ordering、delivery guarantee、resource limit、retention の値を固定済み
- [x] legacy wire golden fixture を保存済み
- [x] Room v1/v2 schema と migration fixture を保存済み
- [x] fixture validation test が通常のAndroid unit-test suiteでgreen
- [x] 全acceptance IDのsetup、operation、oracleをtest specificationに定義済み
- [x] requirement/test traceability verifierがgreen

### Gate 2: Core engine complete

- [x] `ARC-*`, `ENG-*`, `DB-*`, `QUA-*` がgreen
- [x] Android/JVM targetで同じcommon testがgreen
- [x] public API reviewでAndroid/host依存がない

### Gate 3: Security complete

- [x] `SEC-*`, `ING-*`, `INS-*` がgreen
- [x] `security-loop/run.sh --module sync-core-transport-lan --iterations 3` 相当の `verifySyncCoreSecurityLoop` が完走
- [x] unresolved high/critical findingがゼロ

### Gate 4: Integration switch

- [x] `INT-*` と既存acceptance suiteがgreen
- [x] internal client contract suiteがgreen
- [x] composition rootを内製実装へ切替
- [x] sync disabled regressionを通常 unit/connected smoke で確認

### Gate 5: External dependency removal

- [x] `SYNC_CORE_DIR`, `synccore.dir`, external Maven coordinates、composite buildを削除
- [x] clean checkout相当の `INT-10` / `verifyNoExternalSyncCoreBuild` がgreen
- [x] code/docs/CIに外部sync-core前提が残っていない

## 15. Resolved Design Decisions

1. **Module**: 単一のKotlin Multiplatform `:sync-core` moduleを作り、`api`/`engine` packageで境界を分ける。初期段階では複数artifactに分割しない。
2. **Delivery**: at-least-once、peer単位FIFO、peerごと1 in-flight、全体4並列とする。
3. **Retry**: 1秒開始、2倍、最大60秒、0-20% jitter、合計10 attempt。limit後のmanual retryを許可する。
4. **Capacity**: outgoing 2,000件、peerごと500件、unprocessed inbox 2,000件。active dataを自動evictしない。
5. **Retention**: replay/result route/terminal outgoingは7日、processed command idは90日、delivery eventは30日。row上限は`DR-01`に従う。
6. **Durability**: remote successはdurable inbox commit後のみ返す。in-memory bufferだけで受理しない。
7. **Platform order**: coreはAndroid/JVMを同時対応し、外部dependency削除はAndroid command-sync parityを基準にする。Desktop既存transport置換は別段階。
8. **Security**: TLS certificate pinningとES256 envelope signatureを両方必須とする。Ed25519は対応端末/legacy peerのoptional互換としてのみ許可する。
9. **Compatibility**: `OrgSyncCoreClient`、legacy wire format、Room v1/v2 dataをmigration期間中維持する。

## 16. Deferred Decisions

以下はv1実装の開始を妨げない。

- iOS targetへの`:sync-core`展開時期
- event/template transportを汎用message APIへ移す時期
- capability negotiation protocol
- API/engineの複数artifact化
- ordering keyのpublic API追加
