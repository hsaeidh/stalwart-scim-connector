package org.stalwart.identity.provisioning.connector.scim;

import org.wso2.carbon.identity.application.common.model.Property;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

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
        if (properties == null || properties.size() != 20) {
            throw new IllegalStateException("Expected 20 configuration properties, got: "
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
                StalwartSCIMProvisioningConnectorConstants.BULK_FAIL_ON_ERRORS,
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
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_FAIL_ON_ERRORS, "0");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_REQUEST_TIMEOUT_MS, "30000");
        assertDefault(byName, StalwartSCIMProvisioningConnectorConstants.BULK_MAX_PAYLOAD_BYTES, "900000");

        // Verify the new mode can be initialized without changing the OSGi factory contract.
        Property baseUrl = new Property();
        baseUrl.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
        baseUrl.setValue("https://mail.example.com/scim/v2");
        Property token = new Property();
        token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
        token.setValue("smoke-test-token");
        Property mode = new Property();
        mode.setName(StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE);
        mode.setValue("BULK");
        try {
            new StalwartSCIMProvisioningConnector().init(new Property[]{baseUrl, token, mode});
        } catch (org.wso2.carbon.identity.provisioning.IdentityProvisioningException e) {
            throw new IllegalStateException("Valid BULK configuration was rejected.", e);
        }

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

        System.out.println("Connector type: " + factory.getConnectorType());
        System.out.println("Properties count: " + properties.size());
        System.out.println("Required claim mapping defaults: emailaddress -> userName, username -> externalId");
        System.out.println("SMOKE TEST PASSED v2.0.2");
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
            connector.init(new Property[]{baseUrl, token, mode});

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
                    || !"bulk-smoke-id".equals(operation.getAsJsonObject("data").get("externalId").getAsString())) {
                throw new IllegalStateException("SCIM Bulk operation was serialized incorrectly.");
            }
        } catch (Exception e) {
            throw new IllegalStateException("SCIM Bulk transport smoke test failed.", e);
        } finally {
            if (server != null) server.stop(0);
        }
    }

    private static void assertDefault(Map<String, Property> properties, String name, String expected) {
        String actual = properties.get(name).getDefaultValue();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Unexpected default for " + name + ": " + actual);
        }
    }
}
