/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.computesystemcontroller.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.NamedElementQueryResultList;
import com.emc.storageos.db.client.constraint.NamedElementQueryResultList.NamedElement;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject.RegistrationStatus;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.FileExport;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.IpInterface;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.VcenterDataCenter;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.DataObjectUtils;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.model.NamedRelatedResourceRep;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.volumecontroller.placement.PlacementException;
import com.google.common.collect.Lists;
import com.vmware.vim25.HostService;

public class ComputeSystemHelper {

    private static final Logger _log = LoggerFactory.getLogger(ComputeSystemHelper.class);

    /**
     * This function is to retrieve the children of a given class.
     * 
     * @param id the URN of parent
     * @param clzz the child class
     * @param nameField the name of the field of the child class that will be displayed as
     *            name in {@link NamedRelatedResourceRep}. Note this field should be a required
     *            field because, objects for which this field is null will not be returned by
     *            this function.
     * @param linkField the name of the field in the child class that stored the parent id
     * @return a list of children of tenant for the given class
     */
    protected static <T extends DataObject> List<NamedElementQueryResultList.NamedElement> listChildren(DbClient dbClient, URI id,
            Class<T> clzz,
            String nameField, String linkField) {
        @SuppressWarnings("deprecation")
        List<URI> uris = dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getContainedObjectsConstraint(id, clzz, linkField));
        if (uris != null && !uris.isEmpty()) {
            List<T> dataObjects = dbClient.queryObjectField(clzz, nameField, uris);
            List<NamedElementQueryResultList.NamedElement> elements =
                    new ArrayList<NamedElementQueryResultList.NamedElement>(dataObjects.size());
            for (T dataObject : dataObjects) {
                Object name = DataObjectUtils.getPropertyValue(clzz, dataObject, nameField);
                elements.add(NamedElementQueryResultList.NamedElement.createElement(
                        dataObject.getId(), name == null ? "" : name.toString()));
            }
            return elements;
        } else {
            return new ArrayList<NamedElementQueryResultList.NamedElement>();
        }
    }

    /**
     * Utility function to recursively deactivate all the hosts interfaces
     * 
     * @param host the host to deactivate
     */
    public static void doDeactivateHost(DbClient dbClient, Host host) {
        List<IpInterface> hostInterfaces = queryIpInterfaces(dbClient, host.getId());
        for (IpInterface hostInterface : hostInterfaces) {
            hostInterface.setRegistrationStatus(RegistrationStatus.UNREGISTERED.toString());
            dbClient.markForDeletion(hostInterface);
        }
        List<Initiator> initiators = queryInitiators(dbClient, host.getId());
        for (Initiator initiator : initiators) {
            initiator.setRegistrationStatus(RegistrationStatus.UNREGISTERED.toString());
            dbClient.markForDeletion(initiator);
        }
        host.setRegistrationStatus(RegistrationStatus.UNREGISTERED.toString());
        host.setProvisioningStatus(Host.ProvisioningJobStatus.COMPLETE.toString());
        dbClient.persistObject(host);
        _log.info("marking host for deletion: {} {}", host.getLabel(), host.getId());
        dbClient.markForDeletion(host);
    }

    /**
     * Returns the Initiator of a host
     * 
     * @param id the URN of a ViPR initiator
     * @return the Initiator of a host
     */
    public static List<Initiator> queryInitiators(DbClient dbClient, URI id) {
        List<Initiator> initiators = new ArrayList<Initiator>();
        URIQueryResultList initiatorUris = new URIQueryResultList();
        dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getContainedObjectsConstraint(id,
                        Initiator.class, "host"), initiatorUris);
        if (initiatorUris.iterator().hasNext()) {
            for (URI initiatorUri : initiatorUris) {
                Initiator initiator = dbClient.queryObject(Initiator.class,
                        initiatorUri);
                if (initiators != null && !initiator.getInactive()) {
                    initiators.add(initiator);
                }
            }
        }
        return initiators;
    }

    /**
     * Utility function to recursively deactivate all the hosts / clusters from datacenter
     * 
     * @param host the host to deactivate
     */
    public static void doDeactivateVcenterDataCenter(DbClient dbClient, VcenterDataCenter dataCenter) {
        List<NamedElementQueryResultList.NamedElement> hostUris = listChildren(dbClient, dataCenter.getId(),
                Host.class, "label", "vcenterDataCenter");
        Set<URI> doNotDeleteclusters = new HashSet<URI>();
        for (NamedElementQueryResultList.NamedElement hostUri : hostUris) {
            Host host = dbClient.queryObject(Host.class, hostUri.getId());
            if (host != null && !host.getInactive()) {
                if (NullColumnValueGetter.isNullURI(host.getComputeElement())) {
                    doDeactivateHost(dbClient, host);
                }
                else {
                    // do not delete hosts that have compute elements, simply break DC link
                    host.setVcenterDataCenter(NullColumnValueGetter.getNullURI());
                    dbClient.persistObject(host);
                    if (!NullColumnValueGetter.isNullURI(host.getCluster())) {
                        doNotDeleteclusters.add(host.getCluster());
                    }
                }
            }
        }
        List<NamedElementQueryResultList.NamedElement> clustersUris = listChildren(dbClient, dataCenter.getId(),
                Cluster.class, "label", "vcenterDataCenter");
        for (NamedElementQueryResultList.NamedElement clusterUri : clustersUris) {
            Cluster cluster = dbClient.queryObject(Cluster.class, clusterUri.getId());
            if (cluster != null && !cluster.getInactive()) {
                if (doNotDeleteclusters.contains(cluster.getId())) {
                    // do not delete clusters if hosts are not deleted, simply break DC link
                    cluster.setVcenterDataCenter(NullColumnValueGetter.getNullURI());
                    cluster.setExternalId(NullColumnValueGetter.getNullStr());
                    dbClient.persistObject(cluster);
                }
                else {
                    dbClient.markForDeletion(cluster);
                }
            }
        }
        _log.info("marking DC for deletion: {} {}", dataCenter.getLabel(), dataCenter.getId());
        dbClient.markForDeletion(dataCenter);
    }

    /**
     * Utility function to dissociate a host and its initiator from its cluster
     * 
     * @param host the host to dissociate
     */
    public static void removeClusterFromHost(DbClient dbClient, Host host) {
        List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, host.getId());
        for (Initiator initiator : initiators) {
            initiator.setClusterName("");
            dbClient.persistObject(initiator);
        }

        host.setCluster(NullColumnValueGetter.getNullURI());
        dbClient.persistObject(host);
    }

    /**
     * Utility function to recursively deactivate cluster
     * Removes all references to the cluster from host.
     * 
     * @param cluster the cluster to deactivate
     */
    public static void doDeactivateCluster(DbClient dbClient, Cluster cluster) {
        List<NamedElementQueryResultList.NamedElement> hostUris = listChildren(dbClient, cluster.getId(),
                Host.class, "label", "cluster");
        for (NamedElementQueryResultList.NamedElement hostUri : hostUris) {
            Host host = dbClient.queryObject(Host.class, hostUri.getId());
            if (host != null && !host.getInactive()) {
                removeClusterFromHost(dbClient, host);
            }
        }
        _log.info("marking cluster for deletion: {} {}", cluster.getLabel(), cluster.getId());
        dbClient.markForDeletion(cluster);
    }

    /**
     * Returns the IP interface of a given host after it validates that the IP interface
     * exists, and belongs to the parent host.
     * 
     * @param hostId the parent host URI
     * @param initiatorId the initiator URI
     * @param checkInactive when set to true, the function will also validates
     *            that the initiator is active.
     * @return the host initiator
     */
    public static List<IpInterface> queryIpInterfaces(DbClient dbClient, URI id) {
        List<IpInterface> ipInterfaces = new ArrayList<IpInterface>();
        URIQueryResultList ipInterfaceUris = new URIQueryResultList();
        dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getContainedObjectsConstraint(id,
                        IpInterface.class, "host"), ipInterfaceUris);
        if (ipInterfaceUris.iterator().hasNext()) {
            for (URI ipInterfaceUri : ipInterfaceUris) {
                IpInterface ipInterface = dbClient.queryObject(IpInterface.class,
                        ipInterfaceUri);
                if (ipInterface != null && !ipInterface.getInactive()) {
                    ipInterfaces.add(ipInterface);
                }
            }
        }
        return ipInterfaces;
    }

    /**
     * Checks if the host has initiators in use by export groups or ip interfaces in use
     * by file exports.
     * 
     * @param hostId the host to be checked
     * @return true if the host has has initiators in use by export groups or ip interfaces in use
     *         by file exports.
     */
    public static boolean isHostInUse(DbClient dbClient, URI hostId) {
        List<Initiator> initiators =
                CustomQueryUtility.queryActiveResourcesByConstraint(dbClient, Initiator.class,
                        ContainmentConstraint.Factory.getContainedObjectsConstraint(hostId, Initiator.class, "host"));
        for (Initiator initiator : initiators) {
            if (isInitiatorInUse(dbClient, initiator.getId().toString())) {
                return true;
            }
        }
        List<String> ipEndpoints = getIpInterfaceEndpoints(dbClient, hostId);
        if (isHostIpInterfacesInUse(dbClient, ipEndpoints, hostId)) {
            return true;
        }

        return !findExportsByHost(dbClient, hostId.toString()).isEmpty();
    }

    /**
     * Checks if the cluster has any export
     * 
     * @param cluster the cluster to be checked
     * @return true if one or more of the cluster hosts is in use
     * @see HostService#isHostInUse(URI)
     */
    public static boolean isClusterInExport(DbClient dbClient, URI cluster) {
        List<ExportGroup> exportGroups = CustomQueryUtility.queryActiveResourcesByConstraint(
                dbClient, ExportGroup.class,
                AlternateIdConstraint.Factory.getConstraint(
                        ExportGroup.class, "clusters", cluster.toString()));
        return !exportGroups.isEmpty();
    }

    /**
     * Checks if an initiator in use by an export groups
     * 
     * @param iniId the initiator URI
     * @return true if the initiator in use by export groups
     */
    public static boolean isInitiatorInUse(DbClient dbClient, String iniId) {
        List<ExportGroup> exportGroups = CustomQueryUtility.queryActiveResourcesByConstraint(
                dbClient, ExportGroup.class,
                AlternateIdConstraint.Factory.getConstraint(
                        ExportGroup.class, "initiators", iniId));
        return !exportGroups.isEmpty();
    }

    /**
     * Checks if an vcenter is in use by an export groups
     * 
     * @param dbClient
     * @param vcenterURI the vcenter URI
     * @return true if the vcenter is in used by an export group.
     */
    public static boolean isVcenterInUse(DbClient dbClient, URI vcenterURI) {
        List<NamedElementQueryResultList.NamedElement> datacenterUris = listChildren(dbClient, vcenterURI,
                VcenterDataCenter.class, "label", "vcenter");
        for (NamedElementQueryResultList.NamedElement datacenterUri : datacenterUris) {
            if (isDataCenterInUse(dbClient, datacenterUri.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if an datacenter is in use by an export groups
     * 
     * @param dbClient
     * @param datacenterURI the datacenter URI
     * @return true if the datacenter is in used by an export group.
     */
    public static boolean isDataCenterInUse(DbClient dbClient, URI datacenterURI) {
        VcenterDataCenter dataCenter = dbClient.queryObject(VcenterDataCenter.class, datacenterURI);
        if (dataCenter != null && !dataCenter.getInactive()) {
            List<NamedElementQueryResultList.NamedElement> hostUris = listChildren(dbClient, dataCenter.getId(),
                    Host.class, "label", "vcenterDataCenter");
            for (NamedElementQueryResultList.NamedElement hostUri : hostUris) {
                Host host = dbClient.queryObject(Host.class, hostUri.getId());
                // ignore provisioned hosts
                if (host != null && !host.getInactive() && NullColumnValueGetter.isNullURI(host.getComputeElement())
                        && isHostInUse(dbClient, host.getId())) {
                    return true;
                }
            }
            List<NamedElementQueryResultList.NamedElement> clustersUris = listChildren(dbClient, dataCenter.getId(),
                    Cluster.class, "label", "vcenterDataCenter");
            for (NamedElementQueryResultList.NamedElement clusterUri : clustersUris) {
                Cluster cluster = dbClient.queryObject(Cluster.class, clusterUri.getId());
                if (cluster != null && !cluster.getInactive() && isClusterInExport(dbClient, clusterUri.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A utility function that return a list of ip endpoints for a given host. Used
     * to mre efficiently check for host ip interfaces in use.
     * 
     * @param hostId the host URI
     * @return list of ip endpoints for a given host
     */
    public static List<String> getIpInterfaceEndpoints(DbClient dbClient, URI hostId) {
        List<IpInterface> ipInterfaces =
                CustomQueryUtility.queryActiveResourcesByConstraint(dbClient, IpInterface.class,
                        ContainmentConstraint.Factory.getContainedObjectsConstraint(hostId, IpInterface.class, "host"));
        List<String> endpoints = new ArrayList<String>();
        for (IpInterface ipInterface : ipInterfaces) {
            endpoints.add(ipInterface.getIpAddress());
        }
        return endpoints;
    }

    /**
     * Checks if an ipInterface in use by a file export
     * 
     * @param ipAddress the interface IP address
     * @return true if the ipInterface in use by a file export
     */
    public static boolean isHostIpInterfacesInUse(DbClient dbClient, List<String> endpoints, URI hostId) {
        if (endpoints == null || endpoints.isEmpty()) {
            return false;
        }
        Host host = dbClient.queryObject(Host.class, hostId);
        List<FileShare> fileShares = null;
        if (!NullColumnValueGetter.isNullURI(host.getProject())) {
            fileShares = CustomQueryUtility.queryActiveResourcesByRelation(
                    dbClient, host.getProject(), FileShare.class, "project");
        } else {
            fileShares = CustomQueryUtility.queryActiveResourcesByRelation(
                    dbClient, host.getTenant(), FileShare.class, "tenant");
        }
        if (fileShares == null || fileShares.isEmpty()) {
            return false;
        }
        for (FileShare fileShare : fileShares) {
            if (fileShare != null && fileShare.getFsExports() != null) {
                for (FileExport fileExport : fileShare.getFsExports().values()) {
                    if (fileExport != null && fileExport.getClients() != null) {
                        for (String endpoint : endpoints) {
                            if (fileExport.getClients().contains(endpoint)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    public static List<ExportGroup> findExportsByHost(DbClient dbClient, String id) {
        List<ExportGroup> exportGroups = CustomQueryUtility.queryActiveResourcesByConstraint(
                dbClient, ExportGroup.class,
                AlternateIdConstraint.Factory.getConstraint(
                        ExportGroup.class, "hosts", id));
        return exportGroups;
    }

    public static List<ExportGroup> findExportsByInitiator(DbClient dbClient, String id) {
        List<ExportGroup> exportGroups = CustomQueryUtility.queryActiveResourcesByConstraint(
                dbClient, ExportGroup.class,
                AlternateIdConstraint.Factory.getConstraint(
                        ExportGroup.class, "initiators", id));
        return exportGroups;
    }

    public static List<FileShare> getFileSharesByHost(DbClient dbClient, URI hostId) {
        Host host = dbClient.queryObject(Host.class, hostId);

        if (!NullColumnValueGetter.isNullURI(host.getProject())) {
            return CustomQueryUtility.queryActiveResourcesByRelation(
                    dbClient, host.getProject(), FileShare.class, "project");
        } else {
            return CustomQueryUtility.queryActiveResourcesByRelation(
                    dbClient, host.getTenant(), FileShare.class, "tenant");
        }
    }

    /**
     * Check if initiators have connectivity to a storage port.
     * 
     * @param dbClient -- DbClient for database access
     * @param exportGroup -- ExportGroup
     * @param initiators -- List of Initiator objects
     * @return validInitiators that have connectivity to the StorageSystem
     */
    public static List<Initiator> validatePortConnectivity(DbClient dbClient, ExportGroup exportGroup, List<Initiator> initiators) {
        Map<URI, Map<URI, Integer>> storageMap = getStorageToVolumeMap(
                dbClient, exportGroup, false);
        List<Initiator> validInitiators = Lists.newArrayList();
        // we want to make sure the initiator can access each storage
        for (URI storage : storageMap.keySet()) {
            StorageSystem storageSystem = dbClient.queryObject(
                    StorageSystem.class, storage);
            List<URI> varrays = new ArrayList<URI>();
            varrays.add(exportGroup.getVirtualArray());
            // If VPLEX, we need to add the potential HA varrays
            if (storageSystem.getSystemType().equals(DiscoveredDataObject.Type.vplex.name())) {
                List<URI> haVarrays = ExportUtils.getVarraysForStorageSystemVolumes(exportGroup, storage, dbClient);
                varrays.addAll(haVarrays);
            }
            for (Initiator initiator : initiators) {
                // check the initiator has connectivity
                if (hasConnectivityToSystem(storageSystem, varrays, initiator, dbClient)) {
                    validInitiators.add(initiator);
                }
            }
        }
        return validInitiators;
    }

    /**
     * Checks if an initiator has connectivity to a storage system in a varray.
     * 
     * @param dbClient
     * @param storageSystem the storage system where connectivity is needed
     * @param List<URI> varrays for possible connectivity
     * @param initiator the initiator
     * @param dbClient -- The dbClient that should be used
     * @return true if at least one port is found
     */
    private static boolean hasConnectivityToSystem(StorageSystem storageSystem,
            List<URI> varrays, Initiator initiator, DbClient dbClient) {
        try {
            return ConnectivityUtil.isInitiatorConnectedToStorageSystem(initiator, storageSystem, varrays, dbClient);
        } catch (PlacementException ex) {
            _log.info(String.format("Initiator %s (%s) has no connectivity to StorageSystem %s (%s) in varray %s",
                    initiator.getInitiatorPort(), initiator.getId(), storageSystem.getNativeGuid(), storageSystem.getId(), varrays));
            return false;
        } catch (Exception ex) {
            throw APIException.badRequests.errorVerifyingInitiatorConnectivity(
                    initiator.toString(), storageSystem.getNativeGuid(), ex.getMessage());
        }
    }

    private static Map<URI, Map<URI, Integer>> getStorageToVolumeMap(DbClient dbClient, ExportGroup exportGroup, boolean protection) {
        Map<URI, Map<URI, Integer>> map = new HashMap<URI, Map<URI, Integer>>();

        StringMap volumes = exportGroup.getVolumes();

        if (volumes == null) {
            return map;
        }

        for (String uriString : volumes.keySet()) {
            URI blockURI = URI.create(uriString);
            BlockObject block = BlockObject.fetch(dbClient, blockURI);
            // If this is an RP-based Block Snapshot, use the protection controller instead of the underlying block controller
            URI storage = (block.getProtectionController() != null && protection && block.getId().toString().contains("BlockSnapshot")) ?
                    block.getProtectionController() : block.getStorageController();

            if (map.get(storage) == null) {
                map.put(storage, new HashMap<URI, Integer>());
            }
            map.get(storage).put(blockURI, Integer.valueOf(volumes.get(uriString)));
        }

        return map;
    }

    protected static List<URI> toURIs(List<NamedElement> namedElements) {
        List<URI> out = Lists.newArrayList();
        if (namedElements != null) {
            for (NamedElement namedElement : namedElements) {
                out.add(namedElement.getId());
            }
        }
        return out;
    }

    /**
     * This function is to retrieve the uri of the static children of a given class.
     * 
     * @param dbClient - Database client
     * @param id the URN of the parent
     * @param clzz the child class
     * @param linkField the name of the field in the child class that stored the parent id
     * @return a list of children of tenant for the given class
     */
    public static <T extends DataObject> List<URI> getChildrenUris(DbClient dbClient, URI id, Class<T> clzz, String linkField) {
        List<URI> uris = dbClient.queryByConstraint(
                ContainmentConstraint.Factory.getContainedObjectsConstraint(id, clzz, linkField));
        return uris;
    }

    public static void updateInitiatorClusterName(DbClient dbClient, URI clusterURI, URI hostURI) {
        Cluster cluster = dbClient.queryObject(Cluster.class, clusterURI);
        List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, hostURI);
        for (Initiator initiator : initiators) {
            initiator.setClusterName(cluster != null ? cluster.getLabel() : "");
        }
        dbClient.persistObject(initiators);
    }

    public static void updateInitiatorHostName(DbClient dbClient, Host host) {
        List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, host.getId());
        for (Initiator initiator : initiators) {
            initiator.setHostName(host.getHostName());
        }
        dbClient.persistObject(initiators);
    }
}
