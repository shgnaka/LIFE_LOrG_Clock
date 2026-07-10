CREATE TABLE IF NOT EXISTS `sync_outgoing_queue` (
  `commandId` TEXT NOT NULL,
  `topic` TEXT NOT NULL,
  `payloadJson` TEXT NOT NULL,
  `targetPeerId` TEXT NOT NULL,
  `createdAtEpochMs` INTEGER NOT NULL,
  `expiresAtEpochMs` INTEGER,
  `state` TEXT NOT NULL,
  `retryCount` INTEGER NOT NULL,
  `nextRetryAtEpochMs` INTEGER NOT NULL,
  `updatedAtEpochMs` INTEGER NOT NULL,
  `lastErrorCode` TEXT,
  `lastErrorMessage` TEXT,
  PRIMARY KEY(`commandId`)
);

CREATE TABLE IF NOT EXISTS `sync_processed_results` (
  `commandId` TEXT NOT NULL,
  `status` TEXT NOT NULL,
  `errorCode` TEXT,
  `errorMessage` TEXT,
  `appliedAtEpochMs` INTEGER,
  `recordedAtEpochMs` INTEGER NOT NULL,
  PRIMARY KEY(`commandId`)
);

CREATE TABLE IF NOT EXISTS `sync_delivery_events` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `commandId` TEXT NOT NULL,
  `peerId` TEXT NOT NULL,
  `state` TEXT NOT NULL,
  `occurredAtEpochMs` INTEGER NOT NULL,
  `errorCode` TEXT,
  `detail` TEXT
);
