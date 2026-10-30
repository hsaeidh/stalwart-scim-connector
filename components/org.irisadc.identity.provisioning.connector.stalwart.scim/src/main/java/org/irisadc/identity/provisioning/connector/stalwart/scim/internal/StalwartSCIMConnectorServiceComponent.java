/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.irisadc.identity.provisioning.connector.stalwart.scim.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.identity.provisioning.AbstractProvisioningConnectorFactory;
import org.irisadc.identity.provisioning.connector.stalwart.scim.StalwartSCIMProvisioningConnectorFactory;

/**
 * OSGI Service Component that handles SCIM Connector activation.
 */
@Component(
        name = "org.irisadc.identity.provisioning.connector.stalwart.scim.internal.StalwartSCIMConnectorServiceComponent",
        immediate = true
)
public class StalwartSCIMConnectorServiceComponent {

    private static final Log log = LogFactory.getLog(StalwartSCIMConnectorServiceComponent.class);

    @Activate
    protected void activate(ComponentContext context) {

        if (log.isDebugEnabled()) {
            log.debug("Activating StalwartSCIMConnectorServiceComponent");
        }

        try {
            StalwartSCIMProvisioningConnectorFactory stalwartSCIMProvisioningConnectorFactory = new StalwartSCIMProvisioningConnectorFactory();
            context.getBundleContext().registerService(AbstractProvisioningConnectorFactory.class.getName(), stalwartSCIMProvisioningConnectorFactory, null);
            if (log.isDebugEnabled()) {
                log.debug("Stalwart SCIM Provisioning Connector bundle is activated");
            }
        } catch (Throwable e) {
            log.error(" Error while activating Stalwart SCIM Provisioning Connector ", e);
        }
    }
}
