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
import java.util.UUID;

/**
 * Durable journal for SCIM Bulk mutations. The operation is committed to the
 * WSO2 Carbon database before any network request is made. Pending work survives
 * process and host restarts; a worker resumes delivery after connector startup.
 */
public final class ScimOperationOutbox {

    public static final String DEFAULT_JNDI_NAME = "jdbc/WSO2CarbonDB";
    private static final String TABLE = "STALWART_SCIM_OPERATION_OUTBOX";
    private final DataSource dataSource;

    public record Entry(String id, String scope, String endpoint, String method, String path,
                        String dataJson, int attempts, String leaseOwner) { }

    public record Result(String status, String responseJson, String lastError) { }

    public static ScimOperationOutbox lookup(String jndiName) throws Exception {
        String name = jndiName == null || jndiName.isBlank() ? DEFAULT_JNDI_NAME : jndiName.trim();
        InitialContext context = new InitialContext();
        try {
            Object value = context.lookup(name);
            if (!(value instanceof DataSource dataSource)) {
                throw new IllegalStateException("JNDI resource " + name + " is not a JDBC DataSource.");
            }
            return new ScimOperationOutbox(dataSource);
        } catch (NamingException e) {
            throw new IllegalStateException("Unable to resolve durable SCIM outbox DataSource " + name + ".", e);
        } finally {
            try { context.close(); } catch (NamingException ignored) { }
        }
    }

    public ScimOperationOutbox(DataSource dataSource) throws SQLException {
        if (dataSource == null) throw new IllegalArgumentException("DataSource is required.");
        this.dataSource = dataSource;
        ensureSchema();
    }

    public String enqueue(String scope, String endpoint, String method, String path, String dataJson)
            throws SQLException {
        if (blank(scope) || blank(endpoint) || blank(method) || blank(path)) {
            throw new IllegalArgumentException("Scope, endpoint, method and path are required.");
        }
        if (scope.length() > 512 || endpoint.length() > 2048 || path.length() > 4096
                || (dataJson != null && dataJson.length() > 1_048_576)) {
            throw new IllegalArgumentException("SCIM operation exceeds the durable outbox field limit.");
        }
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO " + TABLE + " (OP_ID,SCOPE_KEY,ENDPOINT_URL,METHOD,RESOURCE_PATH,"
                + "DATA_JSON,STATUS,ATTEMPTS,NEXT_ATTEMPT_AT,LEASE_OWNER,LEASE_UNTIL,RESPONSE_JSON,"
                + "LAST_ERROR,CREATED_AT,UPDATED_AT) VALUES (?,?,?,?,?,?,'PENDING',0,?,NULL,NULL,NULL,NULL,?,?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, id);
            insert.setString(2, scope);
            insert.setString(3, endpoint);
            insert.setString(4, method.toUpperCase(java.util.Locale.ROOT));
            insert.setString(5, path);
            insert.setString(6, dataJson);
            insert.setLong(7, now);
            insert.setLong(8, now);
            insert.setLong(9, now);
            insert.executeUpdate();
        }
        return id;
    }

    public List<Entry> claimBatch(String scope, int limit, String owner, long leaseMillis) throws SQLException {
        if (blank(scope) || blank(owner)) throw new IllegalArgumentException("Scope and lease owner are required.");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        long now = System.currentTimeMillis();
        List<Entry> entries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT OP_ID,SCOPE_KEY,ENDPOINT_URL,METHOD,RESOURCE_PATH,DATA_JSON,ATTEMPTS "
                                + "FROM " + TABLE + " WHERE SCOPE_KEY=? AND STATUS IN ('PENDING','IN_FLIGHT') "
                                + "AND NEXT_ATTEMPT_AT<=? AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL<?) "
                                + "ORDER BY CREATED_AT FOR UPDATE")) {
                    select.setString(1, scope);
                    select.setLong(2, now);
                    select.setLong(3, now);
                    select.setMaxRows(safeLimit);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) {
                            entries.add(new Entry(rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), owner));
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TABLE + " SET STATUS='IN_FLIGHT',LEASE_OWNER=?,LEASE_UNTIL=?,UPDATED_AT=? "
                                + "WHERE OP_ID=? AND STATUS IN ('PENDING','IN_FLIGHT')")) {
                    for (Entry entry : entries) {
                        update.setString(1, owner);
                        update.setLong(2, now + Math.max(30_000L, leaseMillis));
                        update.setLong(3, now);
                        update.setString(4, entry.id());
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

    public void markSent(String id, String owner, String responseJson) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET STATUS='SENT',RESPONSE_JSON=?,LAST_ERROR=NULL,"
                + "LEASE_OWNER=NULL,LEASE_UNTIL=NULL,UPDATED_AT=? WHERE OP_ID=? AND LEASE_OWNER=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, responseJson);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, id);
            update.setString(4, owner);
            update.executeUpdate();
        }
    }

    public void markRetry(Entry entry, String error, int maxAttempts) throws SQLException {
        int attempts = entry.attempts() + 1;
        boolean dead = attempts >= Math.max(1, maxAttempts);
        long delay = Math.min(3_600_000L, 30_000L * (1L << Math.min(10, attempts - 1)));
        String sql = "UPDATE " + TABLE + " SET STATUS=?,ATTEMPTS=?,NEXT_ATTEMPT_AT=?,LAST_ERROR=?,"
                + "LEASE_OWNER=NULL,LEASE_UNTIL=NULL,UPDATED_AT=? WHERE OP_ID=? AND LEASE_OWNER=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            update.setString(1, dead ? "DEAD" : "PENDING");
            update.setInt(2, attempts);
            update.setLong(3, now + delay);
            update.setString(4, abbreviate(error));
            update.setLong(5, now);
            update.setString(6, entry.id());
            update.setString(7, entry.leaseOwner());
            update.executeUpdate();
        }
    }

    public void markDead(Entry entry, String error) throws SQLException {
        String sql = "UPDATE " + TABLE + " SET STATUS='DEAD',LAST_ERROR=?,LEASE_OWNER=NULL,"
                + "LEASE_UNTIL=NULL,UPDATED_AT=? WHERE OP_ID=? AND LEASE_OWNER=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, abbreviate(error));
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, entry.id());
            update.setString(4, entry.leaseOwner());
            update.executeUpdate();
        }
    }

    public Result getResult(String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT STATUS,RESPONSE_JSON,LAST_ERROR FROM " + TABLE + " WHERE OP_ID=?")) {
            query.setString(1, id);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? new Result(rs.getString(1), rs.getString(2), rs.getString(3)) : null;
            }
        }
    }

    public void purgeSent(int retentionDays) throws SQLException {
        long cutoff = System.currentTimeMillis() - Math.max(1, retentionDays) * 24L * 60 * 60 * 1000;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE STATUS='SENT' AND UPDATED_AT<?")) {
            delete.setLong(1, cutoff);
            delete.executeUpdate();
        }
    }

    private void ensureSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (tableExists(metadata)) {
                ensureIndex(connection, metadata);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + TABLE + " ("
                        + "OP_ID VARCHAR(36) NOT NULL PRIMARY KEY,"
                        + "SCOPE_KEY VARCHAR(512) NOT NULL,"
                        + "ENDPOINT_URL VARCHAR(2048) NOT NULL,"
                        + "METHOD VARCHAR(8) NOT NULL,"
                        + "RESOURCE_PATH VARCHAR(4096) NOT NULL,"
                        + "DATA_JSON CLOB,"
                        + "STATUS VARCHAR(16) NOT NULL,"
                        + "ATTEMPTS INTEGER NOT NULL,"
                        + "NEXT_ATTEMPT_AT DECIMAL(19,0) NOT NULL,"
                        + "LEASE_OWNER VARCHAR(128),"
                        + "LEASE_UNTIL DECIMAL(19,0),"
                        + "RESPONSE_JSON CLOB,"
                        + "LAST_ERROR VARCHAR(2000),"
                        + "CREATED_AT DECIMAL(19,0) NOT NULL,"
                        + "UPDATED_AT DECIMAL(19,0) NOT NULL)");
            } catch (SQLException e) {
                if (!tableExists(metadata)) throw e;
            }
            ensureIndex(connection, metadata);
        }
    }

    private boolean tableExists(DatabaseMetaData metadata) throws SQLException {
        try (ResultSet tables = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private void ensureIndex(Connection connection, DatabaseMetaData metadata) throws SQLException {
        String name = "STALWART_SCIM_OP_OUTBOX_IDX";
        try (ResultSet indexes = metadata.getIndexInfo(null, null, TABLE, false, false)) {
            while (indexes.next()) if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + name + " ON " + TABLE + " (SCOPE_KEY,STATUS,NEXT_ATTEMPT_AT)");
        } catch (SQLException e) {
            try (ResultSet indexes = metadata.getIndexInfo(null, null, TABLE, false, false)) {
                while (indexes.next()) if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
            }
            throw e;
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() > 1900 ? value.substring(0, 1900) : value;
    }
}
