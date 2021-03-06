/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.modelclient.model;

import java.util.List;

import com.emc.storageos.db.client.model.Cf;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Name;
import com.emc.storageos.db.client.model.Relation;

/**
 * @author cgarber
 * 
 */
@Cf("VirtualArray")
public class VirtualArray extends DataObject {

    private List<StorageSystem> storageSystems;

    private List<Volume> volumes;

    /**
     * @return the storageSystems
     */
    @Relation(type = StorageSystem.class, mappedBy = "varray")
    @Name("storageSystems")
    public List<StorageSystem> getStorageSystems() {
        return storageSystems;
    }

    /**
     * @param storageSystems the storageSystems to set
     */
    public void setStorageSystems(List<StorageSystem> storageSystems) {
        this.storageSystems = storageSystems;
    }

    @Relation(type = Volume.class, mappedBy = "varray")
    @Name("volumes")
    public List<Volume> getVolumes() {
        return volumes;
    }

    public void setVolumes(List<Volume> volumes) {
        this.volumes = volumes;
    }

}
