-- QuickFIX/J JDBC schema for MySQL
-- Use this when LogToDB=Y and/or UseJdbcStore=Y in quickfixj-server.cfg
-- Create a database and run: mysql -u user -p your_db < quickfixj_mysql_schema.sql

-- Session state (used by JdbcStoreFactory when UseJdbcStore=Y)
CREATE TABLE IF NOT EXISTS sessions (
  beginstring     CHAR(8) NOT NULL,
  sendercompid    VARCHAR(64) NOT NULL,
  sendersubid     VARCHAR(64) NOT NULL,
  senderlocid     VARCHAR(64) NOT NULL,
  targetcompid    VARCHAR(64) NOT NULL,
  targetsubid     VARCHAR(64) NOT NULL,
  targetlocid     VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NOT NULL,
  creation_time   DATETIME NOT NULL,
  incoming_seqnum INT NOT NULL,
  outgoing_seqnum INT NOT NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
               targetcompid, targetsubid, targetlocid, session_qualifier)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Stored messages for resend (used by JdbcStoreFactory when UseJdbcStore=Y)
CREATE TABLE IF NOT EXISTS messages (
  beginstring     CHAR(8) NOT NULL,
  sendercompid    VARCHAR(64) NOT NULL,
  sendersubid    VARCHAR(64) NOT NULL,
  senderlocid    VARCHAR(64) NOT NULL,
  targetcompid   VARCHAR(64) NOT NULL,
  targetsubid    VARCHAR(64) NOT NULL,
  targetlocid    VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) NOT NULL,
  msgseqnum      INT NOT NULL,
  message        TEXT NOT NULL,
  PRIMARY KEY (beginstring, sendercompid, sendersubid, senderlocid,
                targetcompid, targetsubid, targetlocid, session_qualifier,
                msgseqnum)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Incoming/outgoing message log (used by JdbcLogFactory when LogToDB=Y)
CREATE TABLE IF NOT EXISTS messages_log (
  id              INT NOT NULL AUTO_INCREMENT,
  time            DATETIME NOT NULL,
  beginstring     CHAR(8) NOT NULL,
  sendercompid    VARCHAR(64) NOT NULL,
  sendersubid     VARCHAR(64) NOT NULL,
  senderlocid     VARCHAR(64) NOT NULL,
  targetcompid    VARCHAR(64) NOT NULL,
  targetsubid     VARCHAR(64) NOT NULL,
  targetlocid     VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) DEFAULT NULL,
  text            TEXT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_time (time),
  KEY idx_session (beginstring, sendercompid, targetcompid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Event log (used by JdbcLogFactory when LogToDB=Y)
CREATE TABLE IF NOT EXISTS event_log (
  id              INT NOT NULL AUTO_INCREMENT,
  time            DATETIME NOT NULL,
  beginstring     CHAR(8) NOT NULL,
  sendercompid    VARCHAR(64) NOT NULL,
  sendersubid     VARCHAR(64) NOT NULL,
  senderlocid     VARCHAR(64) NOT NULL,
  targetcompid    VARCHAR(64) NOT NULL,
  targetsubid     VARCHAR(64) NOT NULL,
  targetlocid     VARCHAR(64) NOT NULL,
  session_qualifier VARCHAR(64) DEFAULT NULL,
  text            TEXT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_time (time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
