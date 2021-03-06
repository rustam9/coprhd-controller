/*
 * Copyright (c) 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vmware.block.tasks;

import com.emc.sa.service.vmware.VMwareUtils;
import com.emc.sa.service.vmware.tasks.RetryableTask;
import com.iwave.ext.vmware.HostStorageAPI;
import com.iwave.ext.vmware.VMWareException;
import com.vmware.vim25.HostConfigFault;
import com.vmware.vim25.HostScsiDisk;
import com.vmware.vim25.HostVmfsVolume;
import com.vmware.vim25.mo.Datastore;
import com.vmware.vim25.mo.HostSystem;

public class CreateVmfsDatastore extends RetryableTask<Datastore> {
    private HostStorageAPI hostStorageAPI;
    private HostScsiDisk disk;
    private String datastoreName;

    public CreateVmfsDatastore(HostSystem host, HostScsiDisk disk, String datastoreName) {
        this.hostStorageAPI = new HostStorageAPI(host);
        this.disk = disk;
        this.datastoreName = datastoreName;
        provideDetailArgs(datastoreName, host.getName(), disk.getDisplayName());
        provideNameArgs(datastoreName);
        setDelay(30000);
    }

    @Override
    protected Datastore tryExecute() {
        Datastore datastore = hostStorageAPI.createVmfsDatastore(disk, datastoreName);
        HostVmfsVolume vmfs = VMwareUtils.getHostVmfsVolume(datastore);
        if (vmfs != null) {
            logInfo("create.vmfs.datastore", datastore.getName(), vmfs.getVersion());
        }
        return datastore;
    }

    @Override
    protected boolean canRetry(VMWareException e) {
        return (e.getCause() instanceof HostConfigFault);
    }
}
