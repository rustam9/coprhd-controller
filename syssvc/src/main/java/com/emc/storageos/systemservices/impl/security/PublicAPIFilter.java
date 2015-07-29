/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.systemservices.impl.security;

import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.security.authentication.AbstractAuthenticationFilter;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.systemservices.impl.upgrade.CoordinatorClientExt;

/**
 * Filter class for for public APIs on non-control node.
 */
public class PublicAPIFilter extends AbstractAuthenticationFilter {

    private static final Logger _log = LoggerFactory.getLogger(PublicAPIFilter.class);

    @Autowired
    private CoordinatorClientExt _coordinatorclientext;

    // Unauthorizing public APIs for non-control nodes.
    @Override
    protected AbstractRequestWrapper authenticate(final ServletRequest servletRequest) {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        _log.debug("Is node control node? " + _coordinatorclientext.isControlNode());
        if (!_coordinatorclientext.isControlNode()) {
            _log.info("URI is not allowed: " + req.getRequestURI());
            throw APIException.unauthorized.methodNotAllowedOnThisNode();
        }
        return null;
    }

}
