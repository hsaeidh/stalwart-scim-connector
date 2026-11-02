package org.stalwart.identity.provisioning.connector.scim;

import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.h2.jdbcx.JdbcDataSource;
import org.wso2.carbon.identity.provisioning.ProvisioningEntity;
import org.wso2.carbon.identity.provisioning.ProvisioningEntityType;
import org.wso2.carbon.identity.provisioning.ProvisioningOperation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executable factory/configuration smoke test outside the OSGi runtime.
 * This does not claim to verify Declarative Services activation in WSO2.
 * It intentionally runs with the Maven test classpath because the connector
 * depends on WSO2 APIs that are not embedded in the distributable bundle.
 */
public final class StalwartSCIMFactorySmokeTest {

    private StalwartSCIMFactorySmokeTest() {
    }

    public static void main(String[] args) {
        StalwartSCIMProvisioningConnectorFactory factory =
                new StalwartSCIMProvisioningConnectorFactory();

        if (!StalwartSCIMProvisioningConnectorFactory.CONNECTOR_TYPE.equals(factory.getConnectorType())) {
            throw new IllegalStateException("Unexpected connector type: " + factory.getConnectorType());
        }

        List<Property> properties = factory.getConfigurationProperties();
        if (properties == null || properties.size() != 19) {
            throw new IllegalStateException("Expected 19 configuration properties, got: "
                    + (properties == null ? "null" : properties.size()));
        }

        Map<String, Property> byName = new HashMap<>();
        for (Property property : properties) {
            if (property == null || property.getName() == null || property.getName().isBlank()) {
                throw new IllegalStateException("Factory returned a null/unnamed configuration property.");
            }
            if (byName.put(property.getName(), property) != null) {
                throw new IllegalStateException("Duplicate configuration property: " + property.getName());
            }
        }

        String[] expectedNames = {
                StalwartSCIMProvisioningConnectorConstants.BASE_URL,
                StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN,
                StalwartSCIMProvisioningConnectorConstants.USERSTORE_DOMAIN,
                StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING,
                StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.DISPLAY_NAME_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.ENABLE_PROFILE_UPDATES,
                StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL,
                StalwartSCIMProvisioningConnectorConstants.MAX_CONCURRENT_REQUESTS,
                StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI,
                StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM,
                StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP,
                StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE,
                StalwartSCIMProvisioningConnectorConstants.BULK_FALLBACK_TO_INDIVIDUAL,
                StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS,
                StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES
        };
        for (String name : expectedNames) {
            if (!byName.containsKey(name)) {
                throw new IllegalStateException("Missing configuration property: " + name);
            }
        }

        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BASE_URL,
                "https://mail.example.com/scim/v2");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.ENABLE_CLAIM_MAPPING, "true");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.USERNAME_CLAIM_URI,
                "http://wso2.org/claims/emailaddress");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.EXTERNAL_ID_CLAIM_URI,
                "http://wso2.org/claims/username");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.GIVEN_NAME_CLAIM_URI,
                "http://wso2.org/claims/givenname");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.FAMILY_NAME_CLAIM_URI,
                "http://wso2.org/claims/lastname");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.DISPLAY_NAME_CLAIM_URI,
                "http://wso2.org/claims/displayname");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.ENABLE_PROFILE_UPDATES, "true");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL, "");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.MAX_CONCURRENT_REQUESTS, "8");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.INVERT_ACTIVE_CLAIM, "false");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.EMAIL_MEMBERSHIP_GROUP, "Email");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE, "INDIVIDUAL");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_FALLBACK_TO_INDIVIDUAL, "true");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS, "30000");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES, "900000");
        // Outbox settings are intentionally absent from the UI: the connector
        // uses WSO2's standard jdbc/WSO2CarbonDB datasource automatically.

        Property baseUrl = new Property();
        baseUrl.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
        baseUrl.setValue("https://mail.example.com/scim/v2");
        Property token = new Property();
        token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
        token.setValue("smoke-test-token");

        Property invalidMode = new Property();
        invalidMode.setName(StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE);
        invalidMode.setValue("ASYNC");
        boolean invalidModeRejected = false;
        try {
            new StalwartSCIMProvisioningConnector().init(new Property[]{baseUrl, token, invalidMode});
        } catch (org.wso2.carbon.identity.provisioning.IdentityProvisioningException expected) {
            invalidModeRejected = true;
        }
        if (!invalidModeRejected) {
            throw new IllegalStateException("Unsupported provisioning mode must be rejected.");
        }

        assertBulkTransportRequest();
        assertStableEntityNameReconciliationWithoutEmailClaim();
        assertUsernameCollisionReconcilesOnlySameExternalId();
        assertDurableOutboxCoalescingAndLease();
        assertDurableBulkOutboxSurvivesReopen();
        assertDurableBulkOutboxBatchesMultipleOperations();
        assertHybridSchedulerSendsMultiOperationBulkPatch();
        assertIdentityAndEmailOnlyUpdatesAreIgnored();
        assertConnectorDescriptionsAreEnglish(properties);

        System.out.println("Connector type: " + factory.getConnectorType());
        System.out.println("Properties count: " + properties.size());
        System.out.println("Required claim mapping defaults: emailaddress -> userName, username -> externalId");
        System.out.println("SMOKE TEST PASSED v2.0.5");
    }

    private static void assertBulkTransportRequest() {
        HttpServer server = null;
        try {
            AtomicReference<String> capturedRequest = new AtomicReference<>();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/scim/v2/Bulk", exchange -> {
                capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                JsonObject operationResult = new JsonObject();
                operationResult.addProperty("status", "201");
                JsonObject resource = new JsonObject();
                resource.addProperty("id", "smoke-user-id");
                resource.addProperty("userName", "bulk-smoke@example.com");
                resource.addProperty("externalId", "bulk-smoke-id");
                operationResult.add("response", resource);
                com.google.gson.JsonArray operationResults = new com.google.gson.JsonArray();
                operationResults.add(operationResult);
                JsonObject bulkResponse = new JsonObject();
                com.google.gson.JsonArray schemas = new com.google.gson.JsonArray();
                schemas.add("urn:ietf:params:scim:api:messages:2.0:BulkResponse");
                bulkResponse.add("schemas", schemas);
                bulkResponse.add("Operations", operationResults);
                byte[] bytes = bulkResponse.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            Property baseUrl = new Property();
            baseUrl.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
            baseUrl.setValue(base);
            Property token = new Property();
            token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
            token.setValue("smoke-test-token");
            Property mode = new Property();
            mode.setName(StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE);
            mode.setValue("BULK");
            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:stalwart_bulk_transport;DB_CLOSE_DELAY=-1");
            setField(connector, "baseUrl", base);
            setField(connector, "bearerToken", "smoke-test-token");
            setField(connector, "provisioningMode", "BULK");
            setField(connector, "bulkRequestTimeoutMs", 30000);
            setField(connector, "bulkMaxPayloadBytes", 900000);
            setField(connector, "bulkFallbackToIndividual", true);
            setField(connector, "scimOperationOutbox", new ScimOperationOutbox(dataSource));
            setField(connector, "scimOperationScope", "smoke-scope");

            JsonObject user = new JsonObject();
            user.addProperty("userName", "bulk-smoke@example.com");
            user.addProperty("externalId", "bulk-smoke-id");
            Method requestAt = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                    "requestAt", String.class, String.class, String.class, JsonObject.class);
            requestAt.setAccessible(true);
            JsonObject result = (JsonObject) requestAt.invoke(connector, base, "POST", "/Users", user);

            if (!"smoke-user-id".equals(result.get("id").getAsString())) {
                throw new IllegalStateException("Bulk operation response resource was not parsed correctly.");
            }
            JsonObject sent = JsonParser.parseString(capturedRequest.get()).getAsJsonObject();
            if (!"urn:ietf:params:scim:api:messages:2.0:BulkRequest"
                    .equals(sent.getAsJsonArray("schemas").get(0).getAsString())) {
                throw new IllegalStateException("Incorrect SCIM BulkRequest schema.");
            }
            JsonObject operation = sent.getAsJsonArray("Operations").get(0).getAsJsonObject();
            if (!"POST".equals(operation.get("method").getAsString())
                    || !"/Users".equals(operation.get("path").getAsString())
                    || operation.get("bulkId") == null
                    || operation.get("bulkId").getAsString().isBlank()
                    || !"bulk-smoke-id".equals(operation.getAsJsonObject("data").get("externalId").getAsString())) {
                throw new IllegalStateException("SCIM Bulk POST must include bulkId and preserve the expected payload.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("SCIM Bulk transport smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void assertStableEntityNameReconciliationWithoutEmailClaim() {
        HttpServer server = null;
        try {
            AtomicInteger getCount = new AtomicInteger();
            AtomicInteger deleteCount = new AtomicInteger();
            AtomicInteger postCount = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/scim/v2/Users", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                    getCount.incrementAndGet();
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query == null || !query.contains("externalId")) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", "existing-id");
                    resource.addProperty("externalId", "1234567890");
                    com.google.gson.JsonArray resources = new com.google.gson.JsonArray();
                    resources.add(resource);
                    JsonObject list = new JsonObject();
                    list.addProperty("totalResults", 1);
                    list.add("Resources", resources);
                    byte[] bytes = list.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                    return;
                }
                if ("DELETE".equals(exchange.getRequestMethod())
                        && "/scim/v2/Users/existing-id".equals(path)) {
                    deleteCount.incrementAndGet();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    postCount.incrementAndGet();
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            Property baseUrl = new Property();
            baseUrl.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
            baseUrl.setValue(base);
            Property token = new Property();
            token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
            token.setValue("smoke-test-token");
            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            connector.init(new Property[]{baseUrl, token});

            // A WSO2 retry may arrive as POST with no mapped claims. Under the
            // default username -> externalId mapping, entityName must reconcile
            // the existing account without demanding an email address.
            ProvisioningEntity repeatedPost = new ProvisioningEntity(
                    ProvisioningEntityType.USER, "1234567890", ProvisioningOperation.POST, Map.of());
            connector.provision(repeatedPost);

            // DELETE commonly omits claim attributes. It must resolve by the
            // stable local username/externalId and must not require userName email.
            ProvisioningEntity delete = new ProvisioningEntity(
                    ProvisioningEntityType.USER, "1234567890", ProvisioningOperation.DELETE, Map.of());
            connector.provision(delete);

            if (getCount.get() != 1 || deleteCount.get() != 1 || postCount.get() != 0) {
                throw new IllegalStateException("Stable entityName reconciliation failed: GET="
                        + getCount.get() + ", DELETE=" + deleteCount.get() + ", POST=" + postCount.get());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Stable externalId reconciliation smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void assertUsernameCollisionReconcilesOnlySameExternalId() {
        HttpServer server = null;
        try {
            AtomicInteger postCount = new AtomicInteger();
            AtomicInteger userResourceReads = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/scim/v2/Users", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("GET".equals(method) && "/scim/v2/Users".equals(path)) {
                    String query = exchange.getRequestURI().getRawQuery();
                    JsonObject list = new JsonObject();
                    com.google.gson.JsonArray resources = new com.google.gson.JsonArray();
                    if (query != null && query.contains("userName")) {
                        JsonObject resource = new JsonObject();
                        resource.addProperty("id", "existing-id");
                        resources.add(resource);
                    }
                    list.addProperty("totalResults", resources.size());
                    list.add("Resources", resources);
                    byte[] bytes = list.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                    return;
                }
                if ("GET".equals(method) && "/scim/v2/Users/existing-id".equals(path)) {
                    userResourceReads.incrementAndGet();
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", "existing-id");
                    resource.addProperty("userName", "same@example.com");
                    resource.addProperty("externalId", "1234567890");
                    byte[] bytes = resource.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                    return;
                }
                if ("POST".equals(method)) postCount.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            Property baseUrl = new Property();
            baseUrl.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
            baseUrl.setValue(base);
            Property token = new Property();
            token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
            token.setValue("smoke-test-token");
            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            connector.init(new Property[]{baseUrl, token});

            ClaimMapping email = ClaimMapping.build(
                    "http://wso2.org/claims/emailaddress", null, null, false);
            ClaimMapping externalId = ClaimMapping.build(
                    "http://wso2.org/claims/username", null, null, false);
            Map<ClaimMapping, List<String>> attributes = Map.of(
                    email, List.of("same@example.com"),
                    externalId, List.of("1234567890"));
            ProvisioningEntity retry = new ProvisioningEntity(
                    ProvisioningEntityType.USER, "1234567890", ProvisioningOperation.POST, attributes);
            connector.provision(retry);

            if (userResourceReads.get() != 1 || postCount.get() != 0) {
                throw new IllegalStateException("A matching userName/externalId collision was not reconciled safely.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Username/externalId conflict reconciliation smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void assertDurableOutboxCoalescingAndLease() {
        try {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:stalwart_outbox_smoke;DB_CLOSE_DELAY=-1");
            HybridProfileOutbox outbox = new HybridProfileOutbox(dataSource);

            outbox.enqueue("carbon.super|PRIMARY", "1234567890", "Connector snapshot", "CONNECTOR", 1);
            outbox.enqueue("carbon.super|PRIMARY", "1234567890", "Authoritative WSO2 name", "EVENT_NAMEPARTS", 2);
            outbox.enqueue("carbon.super|PRIMARY", "1234567890", "Stale connector snapshot", "CONNECTOR", 1);

            List<HybridProfileOutbox.Entry> first = outbox.claimBatch(
                    "carbon.super|PRIMARY", 100, "node-1", 60_000);
            if (first.size() != 1 || !"Authoritative WSO2 name".equals(first.get(0).displayName())) {
                throw new IllegalStateException("Outbox did not coalesce to the highest-priority current snapshot.");
            }
            List<HybridProfileOutbox.Entry> competing = outbox.claimBatch(
                    "carbon.super|PRIMARY", 100, "node-2", 60_000);
            if (!competing.isEmpty()) {
                throw new IllegalStateException("A second scheduler claimed a row while the first lease was active.");
            }

            outbox.markSent(first.get(0).key(), "node-1");
            outbox.enqueue("carbon.super|PRIMARY", "1234567890", "Next profile change", "CONNECTOR", 1);
            List<HybridProfileOutbox.Entry> second = outbox.claimBatch(
                    "carbon.super|PRIMARY", 100, "node-2", 60_000);
            if (second.size() != 1 || !"Next profile change".equals(second.get(0).displayName())) {
                throw new IllegalStateException("A new update was not re-enqueued after the previous one was sent.");
            }
            outbox.markRetry(second.get(0), "node-2", "simulated transient failure", 1);
            List<HybridProfileOutbox.Entry> dead = outbox.claimBatch(
                    "carbon.super|PRIMARY", 100, "node-3", 60_000);
            if (!dead.isEmpty()) {
                throw new IllegalStateException("A terminally failed outbox entry was not moved out of the pending queue.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Durable hybrid outbox smoke test failed.", e);
        }
    }

    private static void assertDurableBulkOutboxSurvivesReopen() {
        try {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:stalwart_bulk_restart;DB_CLOSE_DELAY=-1");
            ScimOperationOutbox firstProcess = new ScimOperationOutbox(dataSource);
            JsonObject patch = new JsonObject();
            patch.addProperty("marker", "persisted-before-network");
            String id = firstProcess.enqueue("restart-scope", "https://mail.example.com/scim/v2",
                    "PATCH", "/Users/user-1", patch.toString());

            // A new outbox instance represents a newly initialized connector
            // after a WSO2 process restart. It must see the committed pending row.
            ScimOperationOutbox restartedProcess = new ScimOperationOutbox(dataSource);
            List<ScimOperationOutbox.Entry> recovered = restartedProcess.claimBatch(
                    "restart-scope", 10, "restarted-node", 60_000L);
            if (recovered.size() != 1 || !id.equals(recovered.get(0).id())
                    || !"PATCH".equals(recovered.get(0).method())
                    || !"/Users/user-1".equals(recovered.get(0).path())
                    || !patch.toString().equals(recovered.get(0).dataJson())) {
                throw new IllegalStateException("A committed Bulk operation was not recovered after outbox re-open.");
            }
            restartedProcess.markSent(id, "restarted-node", null);
            if (!"SENT".equals(restartedProcess.getResult(id).status())) {
                throw new IllegalStateException("Recovered Bulk operation was not acknowledged.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Durable Bulk outbox restart smoke test failed.", e);
        }
    }

    private static void assertDurableBulkOutboxBatchesMultipleOperations() {
        HttpServer server = null;
        try {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:stalwart_bulk_batch;DB_CLOSE_DELAY=-1");
            ScimOperationOutbox outbox = new ScimOperationOutbox(dataSource);
            JsonObject patchOne = new JsonObject();
            patchOne.addProperty("schemas", "urn:ietf:params:scim:api:messages:2.0:PatchOp");
            JsonObject patchTwo = new JsonObject();
            patchTwo.addProperty("schemas", "urn:ietf:params:scim:api:messages:2.0:PatchOp");

            AtomicReference<String> payload = new AtomicReference<>();
            AtomicInteger requestCount = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/scim/v2/Bulk", exchange -> {
                requestCount.incrementAndGet();
                payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                JsonObject response = new JsonObject();
                com.google.gson.JsonArray results = new com.google.gson.JsonArray();
                for (int i = 0; i < 2; i++) {
                    JsonObject result = new JsonObject();
                    result.addProperty("status", "204");
                    results.add(result);
                }
                response.add("Operations", results);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            ScimOperationOutbox activeOutbox = new ScimOperationOutbox(dataSource);
            String activeScope = "active-bulk-batch-scope";
            String activeOne = activeOutbox.enqueue(activeScope, endpoint, "PATCH", "/Users/user-1",
                    patchOne.toString());
            String activeTwo = activeOutbox.enqueue(activeScope, endpoint, "PATCH", "/Users/user-2",
                    patchTwo.toString());

            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            setField(connector, "baseUrl", endpoint);
            setField(connector, "bearerToken", "smoke-test-token");
            setField(connector, "provisioningMode", "BULK");
            setField(connector, "scimOperationOutbox", activeOutbox);
            setField(connector, "scimOperationScope", activeScope);
            setField(connector, "bulkRequestTimeoutMs", 30000);
            setField(connector, "bulkMaxPayloadBytes", 900000);
            setField(connector, "bulkFallbackToIndividual", true);

            Method flush = StalwartSCIMProvisioningConnector.class.getDeclaredMethod("flushScimOperationOutbox");
            flush.setAccessible(true);
            flush.invoke(connector);

            JsonObject sent = JsonParser.parseString(payload.get()).getAsJsonObject();
            if (requestCount.get() != 1 || sent.getAsJsonArray("Operations").size() != 2) {
                throw new IllegalStateException("Pending durable operations were not combined into one Bulk request.");
            }
            if (!"SENT".equals(activeOutbox.getResult(activeOne).status())
                    || !"SENT".equals(activeOutbox.getResult(activeTwo).status())) {
                throw new IllegalStateException("Bulk operation results were not acknowledged individually.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Durable multi-operation Bulk batching smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void assertHybridSchedulerSendsMultiOperationBulkPatch() {
        HttpServer server = null;
        try {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:stalwart_hybrid_bulk_smoke;DB_CLOSE_DELAY=-1");
            HybridProfileOutbox outbox = new HybridProfileOutbox(dataSource);
            String scope = "email.irisadc.ir|carbon.super|PRIMARY";
            outbox.enqueue(scope, "external-1", "Updated One", "EVENT_NAMEPARTS", 2);
            outbox.enqueue(scope, "external-2", "Updated Two", "EVENT_DISPLAYNAME", 2);

            AtomicInteger bulkCount = new AtomicInteger();
            AtomicReference<String> bulkPayload = new AtomicReference<>();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/scim/v2/Users", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                    String query = exchange.getRequestURI().getRawQuery();
                    String external = query != null && query.contains("external-1") ? "external-1" : "external-2";
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", "external-1".equals(external) ? "user-1" : "user-2");
                    com.google.gson.JsonArray resources = new com.google.gson.JsonArray();
                    resources.add(resource);
                    JsonObject list = new JsonObject();
                    list.addProperty("totalResults", 1);
                    list.add("Resources", resources);
                    byte[] bytes = list.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                    return;
                }
                if ("GET".equals(exchange.getRequestMethod())
                        && ("/scim/v2/Users/user-1".equals(path) || "/scim/v2/Users/user-2".equals(path))) {
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", path.endsWith("user-1") ? "user-1" : "user-2");
                    resource.addProperty("externalId", path.endsWith("user-1") ? "external-1" : "external-2");
                    resource.addProperty("displayName", "Old value");
                    JsonObject meta = new JsonObject();
                    meta.addProperty("version", path.endsWith("user-1") ? "etag-1" : "etag-2");
                    resource.add("meta", meta);
                    byte[] bytes = resource.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                    return;
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.createContext("/scim/v2/Bulk", exchange -> {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                bulkCount.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bulkPayload.set(body);
                JsonObject response = new JsonObject();
                com.google.gson.JsonArray operations = new com.google.gson.JsonArray();
                for (int i = 0; i < 2; i++) {
                    JsonObject operation = new JsonObject();
                    operation.addProperty("status", 204);
                    operations.add(operation);
                }
                response.add("Operations", operations);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();

            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            setField(connector, "baseUrl", base);
            setField(connector, "bearerToken", "smoke-test-token");
            setField(connector, "provisioningMode", "HYBRID");
            setField(connector, "hybridScopeId", scope);
            setField(connector, "hybridProfileOutbox", outbox);
            setField(connector, "bulkRequestTimeoutMs", 30000);
            setField(connector, "bulkMaxPayloadBytes", 900000);
            setField(connector, "bulkFallbackToIndividual", true);

            Method flush = StalwartSCIMProvisioningConnector.class.getDeclaredMethod("flushHybridProfileOutbox");
            flush.setAccessible(true);
            flush.invoke(connector);

            if (bulkCount.get() != 1 || bulkPayload.get() == null) {
                throw new IllegalStateException("HYBRID scheduler did not send one Bulk request.");
            }
            JsonObject sent = JsonParser.parseString(bulkPayload.get()).getAsJsonObject();
            com.google.gson.JsonArray operations = sent.getAsJsonArray("Operations");
            if (operations.size() != 2) {
                throw new IllegalStateException("HYBRID scheduler did not batch multiple profile updates.");
            }
            for (int i = 0; i < operations.size(); i++) {
                JsonObject operation = operations.get(i).getAsJsonObject();
                if (!"PATCH".equals(operation.get("method").getAsString())
                        || operation.has("bulkId")
                        || !operation.has("version")) {
                    throw new IllegalStateException("HYBRID Bulk PATCH did not preserve the SCIM operation contract.");
                }
            }
            if (!outbox.claimBatch(scope, 10, "verification-node", 60_000).isEmpty()) {
                throw new IllegalStateException("Successful Bulk operations were not acknowledged in the durable outbox.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("HYBRID multi-operation Bulk scheduler smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    /**
     * Primary email, aliases and WSO2 local username are intentionally not
     * synchronized. These update-only events must return without any SCIM call.
     */
    private static void assertIdentityAndEmailOnlyUpdatesAreIgnored() {
        try {
            ClaimMapping primaryEmail = ClaimMapping.build(
                    "http://wso2.org/claims/emailaddress", null, null, false);
            ClaimMapping localUsername = ClaimMapping.build(
                    "http://wso2.org/claims/username", null, null, false);
            ClaimMapping aliases = ClaimMapping.build(
                    "http://wso2.org/claims/emailaliases", null, null, false);
            ClaimMapping emails = ClaimMapping.build(
                    "http://wso2.org/claims/emails", null, null, false);

            List<Map<ClaimMapping, List<String>>> ignoredUpdates = List.of(
                    Map.of(primaryEmail, List.of("new-primary@example.com")),
                    Map.of(aliases, List.of("alias-added@example.com")),
                    Map.of(aliases, List.of()),
                    Map.of(emails, List.of("alias-removed@example.com")),
                    Map.of(localUsername, List.of("new-local-username"))
            );
            for (String mode : List.of("INDIVIDUAL", "BULK", "HYBRID")) {
                StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
                setField(connector, "baseUrl", "http://127.0.0.1:1/scim/v2");
                setField(connector, "bearerToken", "smoke-test-token");
                setField(connector, "provisioningMode", mode);
                setField(connector, "enableProfileUpdates", true);
                setField(connector, "activeClaimUri", "");
                for (Map<ClaimMapping, List<String>> attributes : ignoredUpdates) {
                    ProvisioningEntity update = new ProvisioningEntity(
                            ProvisioningEntityType.USER, "1234567890", ProvisioningOperation.PATCH, attributes);
                    connector.provision(update);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Primary email, alias, or WSO2 username-only update attempted a Stalwart operation.", e);
        }
    }

    private static void assertConnectorDescriptionsAreEnglish(List<Property> properties) {
        for (Property property : properties) {
            String description = property.getDescription();
            if (description != null && description.matches("(?s).*[^\\x00-\\x7F].*")) {
                throw new IllegalStateException(
                        "Connector UI description must be English/ASCII: " + property.getName());
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertDefault(Map<String, Property> properties, String name, String expected) {
        String actual = properties.get(name).getDefaultValue();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Unexpected default for " + name + ": " + actual);
        }
    }
}
