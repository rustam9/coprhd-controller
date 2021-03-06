/*
 * Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.model;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

/**
 * Object service namespace information
 */
@Cf("NamespaceInfo")
@XmlRootElement(name = "namespace_info")
public class NamespaceInfo extends DataObject {

    private NsTenantZoneMap _tenant_Zone_settings = new NsTenantZoneMap();

    private byte[] _headMetaData;

    private URI _defaultProject;

    private URI _defaultVirtualPool;

    private URI _tenant;

    public static URI EMPTY_URI = URI.create("");

    /**
     * The zone's default information for the tenant
     * Each zone's tenant ID must be registered for the namespace
     */
    @XmlElement
    @Name("zones")
    public NsTenantZoneMap getZones() {
        return _tenant_Zone_settings;
    }

    public void setZones(NsTenantZoneMap tenantZoneMap) {
        _tenant_Zone_settings = tenantZoneMap;
        setChanged("zones");
    }

    @XmlElement
    @Name("headMetaData")
    public byte[] getHeadMetadata() {
        return _headMetaData.clone();
    }

    public void setHeadMetadata(byte[] headMetadata) {
        _headMetaData = headMetadata.clone();
        setChanged("headMetaData");
    }

    @XmlElement
    @Name("defaultObjectProject")
    public URI getDefaultObjectProject() {
        return _defaultProject;
    }

    public void setDefaultObjectProject(URI defaultProject) {
        _defaultProject = defaultProject;
        setChanged("defaultObjectProject");
    }

    @XmlElement
    @Name("defaultVirtualPool")
    public URI getDefaultVirtualPool() {
        return _defaultVirtualPool;
    }

    public void setDefaultVirtualPool(URI defaultObjectVirtualPool) {
        _defaultVirtualPool = defaultObjectVirtualPool;
        setChanged("defaultVirtualPool");
    }

    @XmlElement
    @Name("tenant")
    public URI getTenant() {
        return _tenant;
    }

    public void setTenant(URI tenant) {
        _tenant = tenant;
        setChanged("tenant");
    }
}
