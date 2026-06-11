# QRペアリングのセキュリティ

QRコードは恒久的な同期credentialを含みません。内容はDesktopのLAN endpoint、Desktop device ID、自己署名TLS証明書のSHA-256 fingerprint、短期の一回限り招待token、有効期限です。

## 保護

- 招待tokenの有効期間は2分
- 最初の正常な交換で即時失効し、再利用を拒否
- 期限切れQRをAndroid側とDesktop側の両方で拒否
- HTTPSで交換し、QR内fingerprintで証明書をpinning
- 交換後に端末固有の256-bit credentialを発行
- 永続credentialはQRへ表示しない
- Desktopはcredentialと送信元device IDの対応を検証
- 不正token、証明書不一致、送信元不一致を拒否
- Androidのcleartext HTTPは引き続き禁止
- DesktopのTLS秘密鍵はorgルート外の端末ローカル領域に保存し、対応OSでは所有者限定権限を設定

## 残るリスク

攻撃者が表示中のQRを取得し、正規端末より先に2分以内で交換すると、その1台として登録される可能性があります。公共空間ではQRを表示したまま離席せず、登録後にDesktopのtrusted peer一覧を確認してください。不明なpeerはrevokeしてください。

QRはパスワードとして保存・共有しないでください。スクリーンショットを送る運用は想定していません。
