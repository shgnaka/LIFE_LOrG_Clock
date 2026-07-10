CREATE TABLE IF NOT EXISTS `sync_incoming_replay` (
  `senderDeviceId` TEXT NOT NULL,
  `commandId` TEXT NOT NULL,
  `registeredAtEpochMs` INTEGER NOT NULL,
  PRIMARY KEY(`senderDeviceId`, `commandId`)
);

CREATE INDEX IF NOT EXISTS `index_sync_incoming_replay_registeredAtEpochMs`
ON `sync_incoming_replay` (`registeredAtEpochMs`);
