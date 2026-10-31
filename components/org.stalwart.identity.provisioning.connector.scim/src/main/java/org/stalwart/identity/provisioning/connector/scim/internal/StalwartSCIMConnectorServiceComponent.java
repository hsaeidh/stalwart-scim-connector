/*
 * Copyright (c) 2014, WSO2 Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package org.stalwart.identity.provisioning.connector.scim.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.stalwart.identity.provisioning.connector.scim.StalwartSCIMProvisioningConnectorFactory;

@Component(
        name = "org.stalwart.identity.provisioning.connector.scim.internal.StalwartSCIMConnectorServiceComponent",
        immediate = true
)
public class StalwartSCIMConnectorServiceComponent {

    private static final Log LOG;

    /*
     * Keep class initialization deliberately small. The first marker proves
     * that the JVM reached this class initializer; the second proves that
     * commons-logging initialization completed.
     */
    static {
        System.err.println(">>> STALWART-SCIM STATIC INIT ENTERING v2.0.0");
        LOG = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);
        System.err.println(">>> STALWART-SCIM STATIC INIT DONE v2.0.0 log=" + LOG);
    }

    private ServiceRegistration<?> factoryRegistration;

    public StalwartSCIMConnectorServiceComponent() {
        super();
        System.err.println(">>> STALWART-SCIM CONSTRUCTOR v2.0.0");
    }

    @Activate
    protected void activate(ComponentContext context) {
        System.err.println(">>> STALWART-SCIM ACTIVATE START v2.0.0");
        try {
            if (context == null) {
                throw new IllegalStateException("DS supplied a null ComponentContext.");
            }

            BundleContext bundleContext = context.getBundleContext();
            System.err.println(">>> STALWART-SCIM BundleContext: " + bundleContext);
            if (bundleContext == null) {
                throw new IllegalStateException("ComponentContext returned a null BundleContext.");
            }

            StalwartSCIMProvisioningConnectorFactory factory =
                    new StalwartSCIMProvisioningConnectorFactory();
            System.err.println(">>> STALWART-SCIM Factory created: " + factory);
            System.err.println(">>> STALWART-SCIM Connector type: " + factory.getConnectorType());

            factoryRegistration = bundleContext.registerService(
                    AbstractProvisioningConnectorFactory.class.getName(), factory, null);

            System.err.println(">>> STALWART-SCIM Service registered: " + factoryRegistration);
            if (factoryRegistration == null) {
                throw new IllegalStateException("OSGi registerService returned null.");
            }

            System.err.println(">>> STALWART-SCIM ACTIVATE END v2.0.0 — READY");
            LOG.info("Stalwart SCIM connector activated; service registration=" + factoryRegistration);
        } catch (Throwable t) {
            System.err.println(">>> STALWART-SCIM ACTIVATE FAILED v2.0.0: " + t);
            t.printStackTrace(System.err);
            LOG.error("Stalwart SCIM connector activation failed.", t);

            if (t instanceof Error) {
                throw (Error) t;
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new IllegalStateException("Stalwart SCIM connector activation failed.", t);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        System.err.println(">>> STALWART-SCIM DEACTIVATE v2.0.0");
        if (factoryRegistration != null) {
            try {
                factoryRegistration.unregister();
                System.err.println(">>> STALWART-SCIM service unregistered");
            } catch (Throwable t) {
                System.err.println(">>> STALWART-SCIM unregister failed: " + t);
                t.printStackTrace(System.err);
                LOG.warn("Stalwart SCIM service unregistration failed.", t);
            } finally {
                factoryRegistration = null;
            }
        }
    }
}
