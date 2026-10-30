package org.stalwart.identity.provisioning.connector.scim;

/**
 * Small executable smoke test for factory construction outside the OSGi runtime.
 * Runtime Declarative Services activation is verified separately from the built JAR.
 */
public final class StalwartSCIMFactorySmokeTest {

    private StalwartSCIMFactorySmokeTest() {
    }

    public static void main(String[] args) {
        StalwartSCIMProvisioningConnectorFactory factory =
                new StalwartSCIMProvisioningConnectorFactory();

        if (!"stalwart-scim".equals(factory.getConnectorType())) {
            throw new IllegalStateException("Unexpected connector type: " + factory.getConnectorType());
        }
        int propertyCount = factory.getConfigurationProperties().size();
        if (propertyCount < 1) {
            throw new IllegalStateException("Factory returned no configuration properties.");
        }

        System.out.println("Connector type: " + factory.getConnectorType());
        System.out.println("Properties count: " + propertyCount);
        System.out.println("SMOKE TEST PASSED");
    }
}
