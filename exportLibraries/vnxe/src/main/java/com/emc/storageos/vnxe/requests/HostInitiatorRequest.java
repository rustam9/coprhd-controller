/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.vnxe.requests;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.vnxe.VNXeConstants;
import com.emc.storageos.vnxe.models.HostInitiatorCreateParam;
import com.emc.storageos.vnxe.models.VNXeCommandResult;
import com.emc.storageos.vnxe.models.VNXeHostInitiator;

public class HostInitiatorRequest extends KHRequests<VNXeHostInitiator> {

    private static final Logger _logger = LoggerFactory.getLogger(HostInitiatorRequest.class);
    private static final String URL = "/api/instances/hostInitiator/";
    private static final String URL_ALL = "/api/types/hostInitiator/instances";

    public HostInitiatorRequest(KHClient client) {
        super(client);

    }

    public VNXeHostInitiator get(String id) {
        _url = URL + id;
        return getDataForOneObject(VNXeHostInitiator.class);

    }

    public VNXeHostInitiator getByIQNorWWN(String initiatorId) {
        _url = URL_ALL;
        setFilter(VNXeConstants.INITIATORID_FILTER + initiatorId);
        VNXeHostInitiator result = null;
        List<VNXeHostInitiator> initList = getDataForObjects(VNXeHostInitiator.class);
        // it should just return 1
        if (initList != null && !initList.isEmpty()) {
            result = initList.get(0);
        } else {
            _logger.info("No HostInitiator found using iqn: {}", initiatorId);
        }
        return result;
    }

    public VNXeCommandResult createHostInitiator(HostInitiatorCreateParam param) {
        _url = URL_ALL;
        return postRequestSync(param);
    }
}
