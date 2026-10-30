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

    private static final Log LOG = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);
    private ServiceRegistration<?> registration;

    @Activate
    protected void activate(ComponentContext context) {
        LOG.info(">>> STALWART-SCIM ACTIVATE START v1.0.5");
        try {
            BundleContext bundleContext = context.getBundleContext();
            LOG.info(">>> STALWART-SCIM BundleContext: " + bundleContext);

            StalwartSCIMProvisioningConnectorFactory factory =
                    new StalwartSCIMProvisioningConnectorFactory();
            LOG.info(">>> STALWART-SCIM Factory created: " + factory);
            LOG.info(">>> STALWART-SCIM Connector type: " + factory.getConnectorType());

            registration = bundleContext.registerService(
                    AbstractProvisioningConnectorFactory.class.getName(), factory, null);
            LOG.info(">>> STALWART-SCIM Service registered: " + registration);
        } catch (RuntimeException | Error e) {
            LOG.error(">>> STALWART-SCIM ACTIVATE FAILED v1.0.5", e);
            throw e;
        }
        LOG.info(">>> STALWART-SCIM ACTIVATE END v1.0.5 — READY");
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        LOG.info(">>> STALWART-SCIM DEACTIVATE v1.0.5");
        if (registration != null) {
            try {
                registration.unregister();
                LOG.info(">>> STALWART-SCIM service unregistered.");
            } catch (IllegalStateException e) {
                LOG.warn(">>> STALWART-SCIM service was already unregistered.", e);
            } finally {
                registration = null;
            }
        }
    }
}
