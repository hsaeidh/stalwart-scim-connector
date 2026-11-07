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
import org.wso2.carbon.context.PrivilegedCarbonContext;
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
        // CarbonContext initialization expects the standard WSO2 carbon.home property.
        // CI is not running inside WSO2, so provide an isolated temporary home first.
        if (System.getProperty("carbon.home") == null) {
            System.setProperty("carbon.home", Files.createTempDirectory("stalwart-scim-carbon-home").toString());
        }
        StalwartSCIMProvisioningConnectorFactory factory = new StalwartSCIMProvisioningConnectorFactory();
        List<Property> properties = factory.getConfigurationProperties();
        if (!"stalwart-scim".equals(factory.getConnectorType()) || properties.size() != 19) {
            throw new IllegalStateException("Unexpected connector factory metadata.");
        }
        assertTomlConfiguration();
        assertBulkMicroBatch();
        assertCreateRequiresEmailGroup();
        assertAbsentAndEmptyGroupClaimsDiffer();
        assertGroupDeltaControlsAccess();
        assertAuthoritativeGroupSnapshotOverridesDelta();
        assertGroupAddLoadsMissingEmailFromUserStore();
        assertCreate409ReconcilesByExternalId();
        assertProfileNameDeltasReadFullSnapshot();
        assertActiveClaimInvalidAndMissingFailClosed();
        assertTenantScopedResourceCache();
        assertBulkRetriesOnlyFailedOperation();
        assertProfileDisplayNameCanBeCleared();
        assertIdentityClaimsAreNotUpdated();
        assertProfileUpdateCannotChangeOrigin();
        assertNameCompositionAllowsMissingParts();
        assertDeleteDisablesAccount();
        System.out.println("SMOKE TEST PASSED v2.0.9");
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

            Map<ClaimMapping, List<String>> disabled = claims(
                    "http://wso2.org/claims/emailaddress", "disabled@example.test",
                    "http://wso2.org/claims/username", "stable-disabled",
                    "http://wso2.org/claims/accountEnabled", "false",
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, "Email");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-disabled",
                    ProvisioningOperation.POST, disabled));
            if (creates.size() != 3 || creates.get(2).get("active").getAsBoolean()) {
                throw new IllegalStateException("A false Active Claim must disable an otherwise Email-entitled account.");
            }
        } finally {
            server.stop(0);
        }
    }


    private static void assertAbsentAndEmptyGroupClaimsDiffer() throws Exception {
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
            Map<ClaimMapping, List<String>> absent = claims(
                    "http://wso2.org/claims/emailaddress", "unknown@example.test",
                    "http://wso2.org/claims/username", "stable-unknown");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-unknown",
                    ProvisioningOperation.POST, absent));
            if (!patches.isEmpty()) {
                throw new IllegalStateException("An absent full-group claim must not revoke an existing user's access.");
            }

            Map<ClaimMapping, List<String>> empty = claims(
                    "http://wso2.org/claims/emailaddress", "empty@example.test",
                    "http://wso2.org/claims/username", "stable-empty",
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, "");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-empty",
                    ProvisioningOperation.POST, empty));
            if (patches.size() != 1
                    || patches.get(0).getAsJsonArray("Operations").get(0).getAsJsonObject()
                    .get("value").getAsBoolean()) {
                throw new IllegalStateException("A present but empty group snapshot must disable email access.");
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
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, "Email",
                    IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI, "Email");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER, "stable-existing",
                    ProvisioningOperation.PATCH, add));
            Map<ClaimMapping, List<String>> remove = claims(
                    "http://wso2.org/claims/username", "stable-existing",
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, "Other",
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
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/scim/v2/Bulk", exchange -> {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            received.set(request);
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
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
            JsonArray patchOperations = new JsonArray();
            JsonObject replace = new JsonObject();
            replace.addProperty("op", "replace");
            replace.addProperty("path", "displayName");
            replace.addProperty("value", "One User");
            patchOperations.add(replace);
            patch.add("Operations", patchOperations);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
                    return invoke(request, connector, base, "POST", "/Users", user);
                });
                Future<?> second = pool.submit(() -> {
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
                    return invoke(request, connector, base, "PATCH", "/Users/u-2", patch);
                });
                first.get(); second.get();
            } finally { pool.shutdownNow(); }
            if (received.get() == null || received.get().getAsJsonArray("Operations").size() != 2) {
                throw new IllegalStateException("Concurrent changes were not combined into one Bulk request.");
            }
            if (attempts.get() != 2) {
                throw new IllegalStateException("A retry-safe Bulk request must retry a transient HTTP 429 response.");
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


    private static void assertIdentityClaimsAreNotUpdated() throws Exception {
        StalwartSCIMProvisioningConnector connector = newConnector(
                "http://127.0.0.1:18081/scim/v2", "INDIVIDUAL");
        Map<ClaimMapping, List<String>> attributes = claims(
                "http://wso2.org/claims/emailaddress", "changed@example.test",
                "http://wso2.org/claims/username", "changed-local-id",
                "http://wso2.org/claims/aliases", "alias@example.test");
        Method method = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                "hasSupportedUpdate", Map.class);
        method.setAccessible(true);
        if ((Boolean) method.invoke(connector, attributes)) {
            throw new IllegalStateException("Email, alias and local identity changes must not trigger Stalwart updates.");
        }
    }


    private static void assertProfileUpdateCannotChangeOrigin() throws Exception {
        Property url = new Property();
        url.setName(StalwartSCIMProvisioningConnectorConstants.BASE_URL);
        url.setValue("https://mail.example.test/scim/v2");
        Property token = new Property();
        token.setName(StalwartSCIMProvisioningConnectorConstants.BEARER_TOKEN);
        token.setValue("smoke-token");
        Property profile = new Property();
        profile.setName(StalwartSCIMProvisioningConnectorConstants.PROFILE_UPDATE_SCIM_URL);
        profile.setValue("https://attacker.example.test/scim/v2");
        try {
            new StalwartSCIMProvisioningConnector().init(new Property[]{url, token, profile});
            throw new IllegalStateException("A different profile-update host must be rejected.");
        } catch (org.wso2.carbon.identity.provisioning.IdentityProvisioningException expected) {
            // Expected: the connector must not forward its bearer token to another origin.
        }
    }

    private static void assertNameCompositionAllowsMissingParts() throws Exception {
        Method method = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                "joinName", String.class, String.class);
        method.setAccessible(true);
        if (!"OnlyFirst".equals(method.invoke(null, "OnlyFirst", ""))
                || !"OnlyLast".equals(method.invoke(null, "", "OnlyLast"))
                || !"".equals(method.invoke(null, "", ""))) {
            throw new IllegalStateException("Name composition must tolerate a missing first or last name.");
        }
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

    private static void assertAuthoritativeGroupSnapshotOverridesDelta() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Integer> userCreates = new AtomicReference<>(0);
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/.search")) {
                JsonObject empty = new JsonObject();
                empty.addProperty("totalResults", 0);
                empty.add("Resources", new JsonArray());
                byte[] bytes = empty.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                userCreates.updateAndGet(value -> value + 1);
                exchange.sendResponseHeaders(201, -1);
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
            Map<ClaimMapping, List<String>> attributes = new java.util.HashMap<>(claims(
                    "http://wso2.org/claims/username", "snapshot-conflict-user"));
            attributes.put(ClaimMapping.build(IdentityProvisioningConstants.GROUP_CLAIM_URI,
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, null, false), List.of());
            attributes.put(ClaimMapping.build(IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI,
                    IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI, null, false), List.of("Email"));
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "snapshot-conflict-user", ProvisioningOperation.PATCH, attributes));
            if (userCreates.get() != 0) {
                throw new IllegalStateException("An authoritative empty group snapshot must override a stale Email-group add delta.");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertBulkRetriesOnlyFailedOperation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Integer> bulkCalls = new AtomicReference<>(0);
        AtomicReference<Integer> patchRetries = new AtomicReference<>(0);
        AtomicReference<Integer> postRetries = new AtomicReference<>(0);
        server.createContext("/scim/v2/Bulk", exchange -> {
            bulkCalls.updateAndGet(value -> value + 1);
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray results = new JsonArray();
            for (var element : request.getAsJsonArray("Operations")) {
                JsonObject submitted = element.getAsJsonObject();
                JsonObject result = new JsonObject();
                result.addProperty("method", submitted.get("method").getAsString());
                result.addProperty("path", submitted.get("path").getAsString());
                if ("POST".equalsIgnoreCase(submitted.get("method").getAsString())) {
                    result.addProperty("status", "201");
                    JsonObject resource = new JsonObject();
                    resource.addProperty("id", "created-in-bulk");
                    resource.addProperty("userName", "bulk@example.test");
                    resource.addProperty("externalId", "bulk-user");
                    result.add("response", resource);
                } else {
                    result.addProperty("status", "429");
                }
                results.add(result);
            }
            JsonObject response = new JsonObject();
            response.add("Operations", results);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/scim+json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/scim/v2/Users/u-2", exchange -> {
            if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                patchRetries.updateAndGet(value -> value + 1);
                exchange.sendResponseHeaders(204, -1);
            } else {
                postRetries.updateAndGet(value -> value + 1);
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.createContext("/scim/v2/Users", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                postRetries.updateAndGet(value -> value + 1);
                exchange.sendResponseHeaders(201, -1);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "BULK");
            Method request = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                    "requestAt", String.class, String.class, String.class, JsonObject.class);
            request.setAccessible(true);
            JsonObject user = new JsonObject();
            user.addProperty("userName", "bulk@example.test");
            user.addProperty("externalId", "bulk-user");
            JsonObject patch = new JsonObject();
            patch.addProperty("schemas", "urn:ietf:params:scim:api:messages:2.0:PatchOp");
            JsonArray patchOperations = new JsonArray();
            JsonObject replace = new JsonObject();
            replace.addProperty("op", "replace");
            replace.addProperty("path", "displayName");
            replace.addProperty("value", "Updated User");
            patchOperations.add(replace);
            patch.add("Operations", patchOperations);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = pool.submit(() -> {
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
                    return invoke(request, connector, base, "POST", "/Users", user);
                });
                Future<?> second = pool.submit(() -> {
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
                    return invoke(request, connector, base, "PATCH", "/Users/u-2", patch);
                });
                first.get();
                second.get();
            } finally {
                pool.shutdownNow();
            }
            if (bulkCalls.get() != 1 || patchRetries.get() != 1 || postRetries.get() != 0) {
                throw new IllegalStateException("Bulk partial failure must retry only the transiently failed safe operation.");
            }
        } finally {
            server.stop(0);
        }
    }


    private static void assertGroupAddLoadsMissingEmailFromUserStore() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonObject> created = new AtomicReference<>();
        AtomicReference<JsonObject> activePatch = new AtomicReference<>();
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/.search")) {
                JsonObject empty = new JsonObject();
                empty.addProperty("totalResults", 0);
                empty.add("Resources", new JsonArray());
                byte[] bytes = empty.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                created.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                JsonObject response = new JsonObject();
                response.addProperty("id", "created-from-group-delta");
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod()) && path.endsWith("/created-from-group-delta")) {
                activePatch.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject());
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        Object serviceProxy = null;
        try {
            Map<String, String> currentClaims = Map.of(
                    "http://wso2.org/claims/emailaddress", "from-store@example.test",
                    "http://wso2.org/claims/givenname", "StoreFirst",
                    "http://wso2.org/claims/lastname", "StoreLast",
                    "http://wso2.org/claims/displayname", "Store Display");
            Object managerProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.api.UserStoreManager.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.api.UserStoreManager.class},
                    (proxy, method, args) -> "getUserClaimValues".equals(method.getName()) ? currentClaims : null);
            Object realmProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.api.UserRealm.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.api.UserRealm.class},
                    (proxy, method, args) -> "getUserStoreManager".equals(method.getName()) ? managerProxy : null);
            serviceProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.core.service.RealmService.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.core.service.RealmService.class},
                    (proxy, method, args) -> "getTenantUserRealm".equals(method.getName()) ? realmProxy : null);
            Wso2UserClaimSnapshot.bind((org.wso2.carbon.user.core.service.RealmService) serviceProxy);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "INDIVIDUAL");
            Map<ClaimMapping, List<String>> attributes = new java.util.HashMap<>(claims(
                    "http://wso2.org/claims/username", "stable-group-add-user"));
            attributes.put(ClaimMapping.build(IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI,
                    IdentityProvisioningConstants.NEW_GROUP_CLAIM_URI, null, false), List.of("Email"));
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "stable-group-add-user", ProvisioningOperation.PATCH, attributes));
            if (created.get() == null
                    || !"from-store@example.test".equals(created.get().get("userName").getAsString())
                    || !"stable-group-add-user".equals(created.get().get("externalId").getAsString())
                    || !"Store Display".equals(created.get().get("displayName").getAsString())
                    || !created.get().get("active").getAsBoolean()
                    || activePatch.get() == null) {
                throw new IllegalStateException("Group Add must load missing email/profile claims from the active WSO2 UserStore.");
            }
        } finally {
            if (serviceProxy != null) {
                Wso2UserClaimSnapshot.unbind((org.wso2.carbon.user.core.service.RealmService) serviceProxy);
            }
            server.stop(0);
        }
    }


    private static void assertCreate409ReconcilesByExternalId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Boolean> resourceExists = new AtomicReference<>(false);
        AtomicReference<Integer> createAttempts = new AtomicReference<>(0);
        AtomicReference<JsonObject> activePatch = new AtomicReference<>();
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/.search")) {
                JsonObject response = new JsonObject();
                JsonArray resources = new JsonArray();
                if (resourceExists.get()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("id", "race-user-id");
                    user.addProperty("externalId", "stable-race-user");
                    resources.add(user);
                }
                response.addProperty("totalResults", resources.size());
                response.add("Resources", resources);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/scim/v2/Users".equals(path)) {
                createAttempts.updateAndGet(value -> value + 1);
                resourceExists.set(true); // Simulate another concurrent request winning the create race.
                exchange.sendResponseHeaders(409, -1);
                exchange.close();
                return;
            }
            if ("PATCH".equals(exchange.getRequestMethod()) && path.endsWith("/race-user-id")) {
                activePatch.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
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
            Map<ClaimMapping, List<String>> attributes = new java.util.HashMap<>(claims(
                    "http://wso2.org/claims/emailaddress", "race@example.test",
                    "http://wso2.org/claims/username", "stable-race-user"));
            attributes.put(ClaimMapping.build(IdentityProvisioningConstants.GROUP_CLAIM_URI,
                    IdentityProvisioningConstants.GROUP_CLAIM_URI, null, false), List.of("Email"));
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "stable-race-user", ProvisioningOperation.POST, attributes));
            if (createAttempts.get() != 1 || activePatch.get() == null) {
                throw new IllegalStateException("HTTP 409 create race must reconcile by externalId without creating a duplicate.");
            }
        } finally {
            server.stop(0);
        }
    }

    private static void assertProfileNameDeltasReadFullSnapshot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonObject> patches = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/scim/v2", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/.search")) {
                JsonObject user = new JsonObject();
                user.addProperty("id", "profile-user-id");
                user.addProperty("externalId", "profile-user");
                JsonArray resources = new JsonArray(); resources.add(user);
                JsonObject response = new JsonObject();
                response.addProperty("totalResults", 1); response.add("Resources", resources);
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
        Object serviceProxy = null;
        try {
            Map<String, String> currentClaims = Map.of(
                    "http://wso2.org/claims/givenname", "CurrentFirst",
                    "http://wso2.org/claims/lastname", "CurrentLast");
            Object managerProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.api.UserStoreManager.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.api.UserStoreManager.class},
                    (proxy, method, args) -> "getUserClaimValues".equals(method.getName()) ? currentClaims : null);
            Object realmProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.api.UserRealm.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.api.UserRealm.class},
                    (proxy, method, args) -> "getUserStoreManager".equals(method.getName()) ? managerProxy : null);
            serviceProxy = java.lang.reflect.Proxy.newProxyInstance(
                    org.wso2.carbon.user.core.service.RealmService.class.getClassLoader(),
                    new Class[]{org.wso2.carbon.user.core.service.RealmService.class},
                    (proxy, method, args) -> "getTenantUserRealm".equals(method.getName()) ? realmProxy : null);
            Wso2UserClaimSnapshot.bind((org.wso2.carbon.user.core.service.RealmService) serviceProxy);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "INDIVIDUAL");
            Map<ClaimMapping, List<String>> firstOnly = new java.util.HashMap<>(claims(
                    "http://wso2.org/claims/username", "profile-user",
                    "http://wso2.org/claims/givenname", "EventFirst"));
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "profile-user", ProvisioningOperation.PATCH, firstOnly));
            Map<ClaimMapping, List<String>> lastOnly = new java.util.HashMap<>(claims(
                    "http://wso2.org/claims/username", "profile-user",
                    "http://wso2.org/claims/lastname", "EventLast"));
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "profile-user", ProvisioningOperation.PATCH, lastOnly));
            Map<ClaimMapping, List<String>> displayOnly = claims(
                    "http://wso2.org/claims/username", "profile-user",
                    "http://wso2.org/claims/displayname", "New Display");
            connector.provision(new ProvisioningEntity(ProvisioningEntityType.USER,
                    "profile-user", ProvisioningOperation.PATCH, displayOnly));
            if (patches.size() != 3) {
                throw new IllegalStateException("FIRSTNAME, LASTNAME and DISPLAYNAME must each independently trigger a profile PATCH.");
            }
            for (int i = 0; i < 2; i++) {
                JsonObject operation = patches.get(i).getAsJsonArray("Operations").get(0).getAsJsonObject();
                if (!"CurrentFirst CurrentLast".equals(operation.get("value").getAsString())) {
                    throw new IllegalStateException("A first/last-name delta must use the full current WSO2 UserStore snapshot.");
                }
            }
            JsonObject displayOperation = patches.get(2).getAsJsonArray("Operations").get(0).getAsJsonObject();
            if (!"New Display".equals(displayOperation.get("value").getAsString())) {
                throw new IllegalStateException("DISPLAYNAME-only change must be sent independently.");
            }
        } finally {
            if (serviceProxy != null) {
                Wso2UserClaimSnapshot.unbind((org.wso2.carbon.user.core.service.RealmService) serviceProxy);
            }
            server.stop(0);
        }
    }

    private static void assertActiveClaimInvalidAndMissingFailClosed() throws Exception {
        String activeUri = "http://wso2.org/claims/accountEnabled";
        StalwartSCIMProvisioningConnector connector = newConnector(
                "http://127.0.0.1:18082/scim/v2", "INDIVIDUAL", activeUri);
        Method allows = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                "activeClaimAllows", Map.class, String.class);
        allows.setAccessible(true);
        try {
            allows.invoke(connector, claims(activeUri, "sometimes"), "stable-user");
            throw new IllegalStateException("A non-Boolean Active Claim must be rejected.");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof org.wso2.carbon.identity.provisioning.IdentityProvisioningException)) {
                throw expected;
            }
        }
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
        try {
            allows.invoke(connector, Map.of(), "stable-user");
            throw new IllegalStateException("A configured but missing Active Claim must not default to enabled.");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof org.wso2.carbon.identity.provisioning.IdentityProvisioningException)) {
                throw expected;
            }
        }
    }

    private static void assertTenantScopedResourceCache() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<Integer> searches = new AtomicReference<>(0);
        server.createContext("/scim/v2/Users/.search", exchange -> {
            searches.updateAndGet(value -> value + 1);
            JsonObject user = new JsonObject();
            user.addProperty("id", "tenant-resource-" + searches.get());
            JsonArray resources = new JsonArray(); resources.add(user);
            JsonObject response = new JsonObject();
            response.addProperty("totalResults", 1); response.add("Resources", resources);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/scim/v2";
            StalwartSCIMProvisioningConnector connector = newConnector(base, "INDIVIDUAL");
            Method find = StalwartSCIMProvisioningConnector.class.getDeclaredMethod(
                    "findResourceId", String.class, String.class, String.class);
            find.setAccessible(true);
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(1);
            String tenantOne = (String) find.invoke(connector, "Users", "externalId", "same-stable-id");
            // CarbonContext intentionally prevents changing tenant identity within
            // one request thread. Simulate a second WSO2 request on a fresh thread.
            var tenantPool = Executors.newSingleThreadExecutor();
            String tenantTwo;
            try {
                Future<String> secondTenant = tenantPool.submit(() -> {
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantId(2);
                    try {
                        return (String) find.invoke(connector, "Users", "externalId", "same-stable-id");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                tenantTwo = secondTenant.get();
            } finally {
                tenantPool.shutdownNow();
            }
            if (searches.get() != 2 || tenantOne.equals(tenantTwo)) {
                throw new IllegalStateException("SCIM resource ID cache entries must be isolated by WSO2 tenant.");
            }
        } finally {
            server.stop(0);
        }
    }

}
