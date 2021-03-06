/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.processor;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.AutoTieringPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to provide some common code for use in creating AutoTieringPolicies
 */
public class AutoTieringPolicyProcessorHelper {
    private final static Logger logger = LoggerFactory.getLogger(AutoTieringPolicyProcessorHelper.class);

    /**
     * Look up AutoTieringPolicy object by the given policyID. If found, return it, otherwise return null
     * 
     * @param policyID [in] - Identifier for the policy
     * @param dbClient [in] - Database client
     * @return
     */
    public static AutoTieringPolicy getAutoTieringPolicyByNameFromDB(String policyID, DbClient dbClient) {
        AutoTieringPolicy policy = null;
        try {
            URIQueryResultList result = new URIQueryResultList();
            dbClient.queryByConstraint(
                    AlternateIdConstraint.Factory.getAutoTieringPolicyByNativeGuidConstraint(policyID),
                    result);
            while (result.iterator().hasNext()) {
                policy = dbClient.queryObject(AutoTieringPolicy.class, result.iterator()
                        .next());
            }
        } catch (Exception e) {
            logger.error("AutoTieringPolicy retrieval from DB failed", e);
        }
        return policy;
    }

}
