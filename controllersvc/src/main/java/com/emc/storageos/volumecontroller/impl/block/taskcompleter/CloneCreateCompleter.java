/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block.taskcompleter;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

public class CloneCreateCompleter extends VolumeTaskCompleter {
    private static final Logger _log = LoggerFactory.getLogger(CloneCreateCompleter.class);

    public CloneCreateCompleter(URI fullCopyVolumeURI, String task) {
        super(Volume.class, fullCopyVolumeURI, task);

        setNotifyWorkflow(true);
    }

    public CloneCreateCompleter(List<URI> fullCopyVolumeURIs, String task) {
        super(Volume.class, fullCopyVolumeURIs, task);
    }

    @Override
    protected void complete(DbClient dbClient, Operation.Status status, ServiceCoded coded) {
        _log.info("START FullCopyVolumeCreateCompleter complete");

        try {
            for (URI clone : getIds()) {
                switch (status) {
                    case error:
                        setErrorOnDataObject(dbClient, Volume.class, clone, coded);
                        break;
                    default:
                        setReadyOnDataObject(dbClient, Volume.class, clone);
                }

            }
            if (isNotifyWorkflow()) {
                super.updateWorkflowStatus(status, coded);
            }
            recordBlockVolumeOperation(dbClient, OperationTypeEnum.CREATE_VOLUME_FULL_COPY, status,
                    coded != null ? coded.getMessage() : "");

        } catch (Exception e) {
            String msg = String.format("Failed updating status: FullCopy: %s, Task: %s", getId(), getOpId());
            _log.error(msg, e);
        }
    }
}
