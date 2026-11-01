package org.stalwart.identity.provisioning.connector.scim;

import org.wso2.carbon.identity.application.common.model.Property;

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

        System.out.println("Connector type: " + factory.getConnectorType());
        System.out.println("Properties count: " + properties.size());
        System.out.println("Required claim mapping defaults: emailaddress -> userName, username -> externalId");
        System.out.println("SMOKE TEST PASSED v2.0.2");
    }

    private static void assertDefault(Map<String, Property> properties, String name, String expected) {
        String actual = properties.get(name).getDefaultValue();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Unexpected default for " + name + ": " + actual);
        }
    }
}
