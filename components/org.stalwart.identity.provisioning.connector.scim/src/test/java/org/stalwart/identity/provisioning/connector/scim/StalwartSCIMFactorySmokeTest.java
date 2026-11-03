package org.stalwart.identity.provisioning.connector.scim;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.provisioning.IdentityProvisioningConstants;
import org.wso2.carbon.identity.provisioning.ProvisioningEntity;
import org.wso2.carbon.identity.provisioning.ProvisioningEntityType;
import org.wso2.carbon.identity.provisioning.ProvisioningOperation;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Executable smoke tests; this does not replace testing in a real WSO2/Stalwart deployment. */
public final class StalwartSCIMFactorySmokeTest {
    private StalwartSCIMFactorySmokeTest() { }

    public static void main(String[] args) throws Exception {
        StalwartSCIMProvisioningConnectorFactory factory = new StalwartSCIMProvisioningConnectorFactory();
        List<Property> properties = factory.getConfigurationProperties();
        if (!"stalwart-scim".equals(factory.getConnectorType()) || properties.size() != 19) {
            throw new IllegalStateException("Unexpected connector factory metadata.");
        }
        assertTomlConfiguration();
        assertBulkMicroBatch();
        assertCreateRequiresEmailGroup();
        assertGroupDeltaControlsAccess();
        assertProfileDisplayNameCanBeCleared();
        assertDeleteDisablesAccount();
        System.out.println("SMOKE TEST PASSED v2.0.7");
    }

    private static void assertTomlConfiguration() throws Exception {
        Path home = Files.createTempDirectory("stalwart-scim-config-test");
        Path conf = home.resolve("repository/conf");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("stalwart-scim-connector.toml"),
                "[stalwart-scim]\n" +
                "scim-base-url = \"https://mail.example.test/scim/v2\"\n" +
                "scim-bearer-token = \"toml-token\"\n" +
                "provisioning-mode = \"BULK\"\n" +
                "bulk-request-timeout-ms = 45000\n");
        String previous = System.getProperty("carbon.home");
        System.setProperty("carbon.home", home.toString());
        try {
            Property url = new Property(); url.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL); url.setValue("");
            Property token = new Property(); token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN); token.setValue("");
            StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
            connector.init(new Property[]{url, token});
            if (!"https://mail.example.test/scim/v2".equals(field(connector, "baseUrl"))
                    || !"toml-token".equals(field(connector, "bearerToken"))
                    || !"BULK".equals(field(connector, "provisioningMode"))
                    || !Integer.valueOf(45000).equals(field(connector, "bulkRequestTimeoutMs"))) {
                throw new IllegalStateException("TOML values were not loaded and applied.");
            }
        } finally {
            if (previous == null) System.clearProperty("carbon.home");
            else System.setProperty("carbon.home", previous);
        }
    }


    private static Map<ClaimMapping, List<String>> claims(String... pairs) {
        Map<ClaimMapping, List<String>> attributes = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ClaimMapping mapping = ClaimMapping.build(pairs[i], pairs[i], null, false);
            attributes.put(mapping, List.of(pairs[i + 1]));
        }
        return attributes;
    }

    private static void assertCreateRequiresEmailGroup() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> creates = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<String> searchMethod = new AtomicReference<>();
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/.search")) {
                searchMethod.set(exchange.getRequestMethod());
                JsonObject empty = new JsonObject();
                empty.addProperty("totalResults", 0);
                empty.add("Resources", new JsonArray());
                byte[] bytes = empty.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                creates.add(body);
                JsonObject response = new JsonObject();
                response.addProperty("id", "created-" + creates.size());
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "INDIVIDUAL",
                    "http://wso2.org/claims/accountEnabled");
            Map<ClaimMapping, List<String>> withoutGroup = claims(
                    "http://wso2.org/claims/emailaddress", "no-group@example.test",
                    "http://wso2.org/claims/username", "stable-no-group",
                    "http://wso2.org/claims/accountEnabled", "true");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-no-group",
                    ProvisioningOperation.POST, withoutGroup));
            if (creates.size() != 1 || creates.get(0).get("active").getAsBoolean()) {
                throw new IllegalStateException("An active claim must not activate a user outside the Email group.");
            }
            if (!"POST".equals(searchMethod.get())) {
                throw new IllegalStateException("SCIM lookup must use POST .search, not a URL filter.");
            }

            Map<ClaimMapping, List<String>> withGroup = claims(
                    "http://wso2.org/claims/emailaddress", "member@example.test",
                    "http://wso2.org/claims/username", "stable-member",
                    "http://wso2.org/claims/accountEnabled", "true",
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, "Email");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-member",
                    ProvisioningOperation.POST, withGroup));
            if (creates.size() != 2 || !creates.get(1).get("active").getAsBoolean()) {
                throw new IllegalStateException("A user with Email membership and an allowing active claim should be active.");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertGroupDeltaControlsAccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> patches = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/.search")) {
                JsonObject user = new JsonObject();
                user.addProperty("id", "existing-user");
                JsonArray resources = new JsonArray();
                resources.add(user);
                JsonObject response = new JsonObject();
                response.addProperty("totalResults", 1);
                response.add("Resources", resources);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod())) {
                patches.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "INDIVIDUAL");
            Map<ClaimMapping, List<String>> add = claims(
                    "http://wso2.org/claims/username", "stable-existing",
                    IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI, "Email");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-existing",
                    ProvisioningOperation.PATCH, add));
            Map<ClaimMapping, List<String>> remove = claims(
                    "http://wso2.org/claims/username", "stable-existing",
                    IdentityProvisioningConstants.DELETED_GROUP_CLAIM_URI, "Email");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-existing",
                    ProvisioningOperation.PATCH, remove));
            if (patches.size() != 2
                    || !patches.get(0).getAsJsonArray("Operations").get(0).getAsJsonObject()
                    .get("value").getAsBoolean()
                    || patches.get(1).getAsJsonArray("Operations").get(0).getAsJsonObject()
                    .get("value").getAsBoolean()) {
                throw new IllegalStateException("Email group add/remove must enable/disable Stalwart access.");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertProfileDisplayNameCanBeCleared() throws Exception {
        StalwartSCIMProvisioningConnector connector = newConnector(
                "http://127.0.0.1:18080/scim/v2", "INDIVIDUAL");
        Map<ClaimMapping, List<String>> attributes =
                claims("http://wso2.org/claims/displayname", "");
        Method method = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                "userPatch", Map.class, boolean.class, String.class);
        method.setAccessible(true);
        JsonObject patch = (JsonObject) method.invoke(connector, attributes, true, "stable-user");
        JsonObject operation = patch.getAsJsonArray("Operations").get(0).getAsJsonObject();
        if (!"displayName".equals(operation.get("path").getAsString())
                || !operation.get("value").getAsString().isEmpty()) {
            throw new IllegalStateException("An explicitly empty displayName must clear the Stalwart value.");
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertBulkMicroBatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> received = new AtomicReference<>();
        server.createContext("/scim/v2/Bulk", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            received.set(request);
            JsonArray results = new JsonArray();
            JsonArray submitted = request.getAsJsonArray("Operations");
            for (int i = 0; i < submitted.size(); i++) {
                JsonObject item = new JsonObject();
                item.addProperty("status", "POST".equals(submitted.get(i).getAsJsonObject().get("method").getAsString()) ? "201" : "204");
                if ("POST".equals(submitted.get(i).getAsJsonObject().get("method").getAsString())) {
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", "bulk-created");
                    resource.addProperty("userName", "one@example.test");
                    resource.addProperty("externalId", "one");
                    item.add("response", resource);
                }
                results.add(item);
            }
            JsonObject response = new JsonObject();
            response.add("Operations", results);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "BULK");
            Method request = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                    "requestAt", String.class, String.class, String.class, JsonObject.class);
            request.setAccessible(true);
            JsonObject user = new JsonObject();
            user.addProperty("userName", "one@example.test");
            user.addProperty("externalId", "one");
            JsonObject patch = new JsonObject();
            patch.addProperty("schemas", "urn:ietf:params:scim:api:messages:2.0:PatchOp");
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> invoke(request, connector, base, "POST", "/Users", user));
                Future<?> second = pool.submit(() -> invoke(request, connector, base, "PATCH", "/Users/u-2", patch));
                first.get(); second.get();
            } finally { pool.shutdownNow(); }
            if (received.get() == null || received.get().getAsJsonArray("Operations").size() != 2) {
                throw new IllegalStateException("Concurrent changes were not combined into one Bulk request.");
            }
        } finally { server.stop(0); }
    }

    private static Object invoke(Method method, StalwartSCIMProvisioningConnector target,
                                 String endpoint, String verb, String path, JsonObject body) {
        try { return method.invoke(target, endpoint, verb, path, body); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void assertDeleteDisablesAccount() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> patch = new AtomicReference<>();
        server.createContext("/scim/v2/Users", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/scim/v2/Users/.search".equals(path)) {
                JsonObject user = new JsonObject();
                user.addProperty("id", "user-1");
                user.addProperty("externalId", "123");
                JsonArray resources = new JsonArray(); resources.add(user);
                JsonObject response = new JsonObject();
                response.addProperty("totalResults", 1); response.add("Resources", resources);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod())) {
                patch.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                exchange.sendResponseHeaders(204, -1); exchange.close(); return;
            }
            exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            newConnector(base, "INDIVIDUAL").provision(new ProvisioningEntity(
                    ProvisioningEntityType.USER, "123", ProvisioningOperation.DELETE, Map.of()));
            JsonObject operation = patch.get().getAsJsonArray("Operations").get(0).getAsJsonObject();
            if (!"active".equals(operation.get("path").getAsString())
                    || operation.get("value").getAsBoolean()) {
                throw new IllegalStateException("WSO2 delete must become SCIM PATCH active=false.");
            }
        } finally { server.stop(0); }
    }

    private static StalwartSCIMProvisioningConnector newConnector(String base, String mode) throws Exception {
        return newConnector(base, mode, null);
    }

    private static StalwartSCIMProvisioningConnector newConnector(String base, String mode, String activeClaimUri)
            throws Exception {
        Property url = new Property(); url.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL); url.setValue(base);
        Property token = new Property(); token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN); token.setValue("smoke-token");
        Property provisioningMode = new Property(); provisioningMode.setName(StalwartSCIMProvisioningConnectorConstants.PROVISIONING_MODE); provisioningMode.setValue(mode);
        StalwartSCIMProvisioningConnector connector = new StalwartSCIMProvisioningConnector();
        if (activeClaimUri != null) {
            Property active = new Property();
            active.setName(StalwartSCIMProvisioningConnectorConstants.ACTIVE_CLAIM_URI);
            active.setValue(activeClaimUri);
            connector.init(new Property[]{url, token, provisioningMode, active});
        } else {
            connector.init(new Property[]{url, token, provisioningMode});
        }
        return connector;
    }
}
