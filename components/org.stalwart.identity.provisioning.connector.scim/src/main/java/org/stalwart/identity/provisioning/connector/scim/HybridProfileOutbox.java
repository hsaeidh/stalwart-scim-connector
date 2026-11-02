/*
 * Copyright (c) 2026, IRISA.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-backed durable profile outbox. The WSO2 shared datasource must be used
 * in clustered deployments; no in-memory queue is used for accepted updates.
 */
public final class HybridProfileOutbox {

    public static final String DEFAULT_JNDI_NAME = "jdbc/WSO2CarbonDB";
    private static final String TABLE = "STALWART_SCIM_PROFILE_OUTBOX";

    private final DataSource dataSource;

    public record Entry(String key, String scope, String externalId, String displayName,
                        String source, int priority, int attempts) { }

    public static HybridProfileOutbox lookup(String jndiName) throws Exception {
        String name = jndiName == null || jndiName.isBlank() ? DEFAULT_JNDI_NAME : jndiName.trim();
        DataSource dataSource;
        InitialContext context = new InitialContext();
        try {
            Object value = context.lookup(name);
            if (!(value instanceof DataSource)) {
                throw new IllegalStateException("JNDI resource " + name + " is not a JDBC DataSource.");
            }
            dataSource = (DataSource) value;
        } catch (NamingException e) {
            throw new IllegalStateException("Unable to resolve HYBRID outbox JNDI DataSource " + name + ".", e);
        } finally {
            try {
                context.close();
            } catch (NamingException ignored) {
                // Closing a lookup context must not hide the primary lookup result.
            }
        }
        return new HybridProfileOutbox(dataSource);
    }

    public HybridProfileOutbox(DataSource dataSource) throws SQLException {
        if (dataSource == null) throw new IllegalArgumentException("DataSource is required.");
        this.dataSource = dataSource;
        ensureSchema();
    }

    public void enqueue(String scope, String externalId, String displayName, String source, int priority)
            throws SQLException {
        if (scope == null || scope.isBlank() || externalId == null || externalId.isBlank()
                || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Scope, externalId and non-empty displayName are required.");
        }
        if (scope.length() > 512 || externalId.length() > 512 || displayName.length() > 1024) {
            throw new IllegalArgumentException("Hybrid profile outbox field exceeds its configured storage limit.");
        }
        String key = sha256(scope.trim() + "\n" + externalId.trim());
        long now = System.currentTimeMillis();

        for (int attempt = 0; attempt < 3; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    int currentPriority = -1;
                    String currentStatus = null;
                    boolean exists = false;
                    try (PreparedStatement select = connection.prepareStatement(
                            "SELECT PRIORITY,STATUS FROM " + TABLE + " WHERE ENTRY_KEY = ? FOR UPDATE")) {
                        select.setString(1, key);
                        try (ResultSet rs = select.executeQuery()) {
                            if (rs.next()) {
                                exists = true;
                                currentPriority = rs.getInt(1);
                                currentStatus = rs.getString(2);
                            }
                        }
                    }

                    if (exists) {
                        // Event-derived authoritative snapshots outrank connector
                        // callbacks, which may carry only partial claim mappings.
                        if ("SENT".equals(currentStatus) || "DEAD".equals(currentStatus)
                                || priority >= currentPriority) {
                            try (PreparedStatement update = connection.prepareStatement(
                                    "UPDATE " + TABLE + " SET DISPLAY_NAME=?, SOURCE=?, PRIORITY=?, "
                                            + "STATUS='PENDING', ATTEMPTS=0, NEXT_ATTEMPT_AT=?, "
                                            + "LEASE_OWNER=NULL, LEASE_UNTIL=NULL, LAST_ERROR=NULL, UPDATED_AT=? "
                                            + "WHERE ENTRY_KEY=?")) {
                                update.setString(1, displayName.trim());
                                update.setString(2, safeSource(source));
                                update.setInt(3, priority);
                                update.setLong(4, now);
                                update.setLong(5, now);
                                update.setString(6, key);
                                update.executeUpdate();
                            }
                        }
                    } else {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO " + TABLE + " (ENTRY_KEY,SCOPE_KEY,EXTERNAL_ID,DISPLAY_NAME,"
                                        + "SOURCE,PRIORITY,STATUS,ATTEMPTS,NEXT_ATTEMPT_AT,LEASE_OWNER,"
                                        + "LEASE_UNTIL,LAST_ERROR,CREATED_AT,UPDATED_AT) "
                                        + "VALUES (?,?,?,?,?,?,'PENDING',0,?,NULL,NULL,NULL,?,?)")) {
                            insert.setString(1, key);
                            insert.setString(2, scope.trim());
                            insert.setString(3, externalId.trim());
                            insert.setString(4, displayName.trim());
                            insert.setString(5, safeSource(source));
                            insert.setInt(6, priority);
                            insert.setLong(7, now);
                            insert.setLong(8, now);
                            insert.setLong(9, now);
                            insert.executeUpdate();
                        }
                    }
                    connection.commit();
                    return;
                } catch (SQLException e) {
                    connection.rollback();
                    if (!isConstraintViolation(e) || attempt == 2) throw e;
                }
            }
        }
        throw new SQLException("Unable to enqueue hybrid profile update after concurrent insert retries.");
    }

    public List<Entry> claimBatch(String scope, int limit, String leaseOwner, long leaseMillis)
            throws SQLException {
        if (scope == null || scope.isBlank() || leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("Scope and lease owner are required.");
        }
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Retain successful audit rows for 30 days; DEAD rows remain
                // available for operator inspection until explicitly handled.
                try (PreparedStatement purge = connection.prepareStatement(
                        "DELETE FROM " + TABLE + " WHERE STATUS='SENT' AND UPDATED_AT<?")) {
                    purge.setLong(1, now - 30L * 24 * 60 * 60 * 1000);
                    purge.executeUpdate();
                }
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT ENTRY_KEY,SCOPE_KEY,EXTERNAL_ID,DISPLAY_NAME,SOURCE,PRIORITY,ATTEMPTS "
                                + "FROM " + TABLE + " WHERE SCOPE_KEY=? AND STATUS IN ('PENDING','IN_FLIGHT') "
                                + "AND NEXT_ATTEMPT_AT<=? AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL<?) "
                                + "ORDER BY UPDATED_AT FOR UPDATE")) {
                    select.setString(1, scope);
                    select.setLong(2, now);
                    select.setLong(3, now);
                    select.setMaxRows(safeLimit);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) {
                            entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7)));
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET STATUS='IN_FLIGHT',LEASE_OWNER=?,LEASE_UNTIL=?,UPDATED_AT=? "
                                + "WHERE ENTRY_KEY=?")) {
                    for (Entry entry : entries) {
                        update.setString(1, leaseOwner);
                        update.setLong(2, now + Math.max(30_000L, leaseMillis));
                        update.setLong(3, now);
                        update.setString(4, entry.key());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
        return entries;
    }

    public void markSent(String key, String leaseOwner) throws SQLException {
        updateLeaseState(key, leaseOwner,
                "UPDATE " + TABLE + " SET STATUS='SENT',LEASE_OWNER=NULL,LEASE_UNTIL=NULL,"
                        + "LAST_ERROR=NULL,UPDATED_AT=? WHERE ENTRY_KEY=? AND LEASE_OWNER=?",
                null, 0);
    }

    public void markRetry(Entry entry, String leaseOwner, String error, int maxAttempts)
            throws SQLException {
        int attempts = entry.attempts() + 1;
        boolean dead = attempts >= Math.max(1, maxAttempts);
        long delay = Math.min(3_600_000L, 30_000L * (1L << Math.min(10, attempts - 1)));
        String sql = "UPDATE " + TABLE + " SET STATUS=?,ATTEMPTS=?,NEXT_ATTEMPT_AT=?,"
                + "LEASE_OWNER=NULL,LEASE_UNTIL=NULL,LAST_ERROR=?,UPDATED_AT=? "
                + "WHERE ENTRY_KEY=? AND LEASE_OWNER=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            update.setString(1, dead ? "DEAD" : "PENDING");
            update.setInt(2, attempts);
            update.setLong(3, now + delay);
            update.setString(4, abbreviate(error));
            update.setLong(5, now);
            update.setString(6, entry.key());
            update.setString(7, leaseOwner);
            update.executeUpdate();
        }
    }

    public void release(Entry entry, String leaseOwner, String reason) throws SQLException {
        updateLeaseState(entry.key(), leaseOwner,
                "UPDATE " + TABLE + " SET STATUS='PENDING',LEASE_OWNER=NULL,LEASE_UNTIL=NULL,"
                        + "LAST_ERROR=?,UPDATED_AT=? WHERE ENTRY_KEY=? AND LEASE_OWNER=?",
                abbreviate(reason), 0);
    }

    private void updateLeaseState(String key, String leaseOwner, String sql, String error, int ignored)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            int index = 1;
            if (error != null) update.setString(index++, error);
            update.setLong(index++, System.currentTimeMillis());
            update.setString(index++, key);
            update.setString(index, leaseOwner);
            update.executeUpdate();
        }
    }

    private void ensureSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            boolean found = false;
            try (ResultSet tables = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        found = true;
                        break;
                    }
                }
            }
            if (found) return;
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + TABLE + " ("
                        + "ENTRY_KEY VARCHAR(64) NOT NULL PRIMARY KEY,"
                        + "SCOPE_KEY VARCHAR(512) NOT NULL,"
                        + "EXTERNAL_ID VARCHAR(512) NOT NULL,"
                        + "DISPLAY_NAME VARCHAR(1024) NOT NULL,"
                        + "SOURCE VARCHAR(64) NOT NULL,"
                        + "PRIORITY INTEGER NOT NULL,"
                        + "STATUS VARCHAR(16) NOT NULL,"
                        + "ATTEMPTS INTEGER NOT NULL,"
                        + "NEXT_ATTEMPT_AT DECIMAL(19,0) NOT NULL,"
                        + "LEASE_OWNER VARCHAR(128),"
                        + "LEASE_UNTIL DECIMAL(19,0),"
                        + "LAST_ERROR VARCHAR(2000),"
                        + "CREATED_AT DECIMAL(19,0) NOT NULL,"
                        + "UPDATED_AT DECIMAL(19,0) NOT NULL)");
            } catch (SQLException e) {
                // Another WSO2 node may have created the shared table concurrently.
                boolean tableNowExists = false;
                try (ResultSet tables = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (tables.next()) {
                        if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                            tableNowExists = true;
                            break;
                        }
                    }
                }
                if (!tableNowExists) throw e;
            }
        }
    }

    private static String safeSource(String source) {
        if (source == null || source.isBlank()) return "CONNECTOR";
        return source.length() > 64 ? source.substring(0, 64) : source;
    }

    private static boolean isConstraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) return true;
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() > 1900 ? value.substring(0, 1900) : value;
    }
}
