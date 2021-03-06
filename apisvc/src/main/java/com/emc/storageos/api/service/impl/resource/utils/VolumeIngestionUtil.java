/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.utils;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.resource.ArgValidator;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.BlockIngestOrchestrator;
import com.emc.storageos.api.service.impl.resource.blockingestorchestration.IngestionException;
import com.emc.storageos.api.service.impl.resource.utils.PropertySetterUtil.VolumeObjectProperties;
import com.emc.storageos.computesystemcontroller.impl.ComputeSystemHelper;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.PrefixConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.impl.DbModelClientImpl;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.Cluster;
import com.emc.storageos.db.client.model.DataObject.Flag;
import com.emc.storageos.db.client.model.DiscoveredDataObject.RegistrationStatus;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.HostInterface;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.RemoteDirectorGroup.SupportedCopyModes;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.StringSetMap;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.model.ZoneInfoMap;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedExportMask;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeCharacterstics;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.db.client.util.CommonTransformerFunctions;
import com.emc.storageos.db.client.util.CustomQueryUtility;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.ResourceAndUUIDNameGenerator;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.util.ConnectivityUtil;
import com.emc.storageos.util.VPlexUtil;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.processor.detailedDiscovery.RemoteMirrorObject;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.volumecontroller.placement.BlockStorageScheduler;
import com.emc.storageos.volumecontroller.placement.ExportPathParams;
import com.emc.storageos.vplexcontroller.VPlexControllerUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;

public class VolumeIngestionUtil {
    private static Logger _logger = LoggerFactory.getLogger(VolumeIngestionUtil.class);
    public static final String UNMANAGEDVOLUME = "UNMANAGEDVOLUME";
    public static final String VOLUME = "VOLUME";
    public static final String FALSE = "false";
    public static final String TRUE = "true";

    /**
     * Validation Steps 1. validate PreExistingVolume uri. 2. Check PreExistingVolume is under
     * Bourne Management already. 3. Check whether given vPool is present in the PreExistingVolumes
     * Supported vPool List
     * 
     * @param unManagedVolumes
     * @param vPool
     * @throws Exception
     */
    public static void checkIngestionRequestValidForUnManagedVolumes(
            List<URI> unManagedVolumes, VirtualPool vPool, DbClient dbClient)
            throws Exception {

        for (URI unManagedVolumeUri : unManagedVolumes) {
            UnManagedVolume unManagedVolume = dbClient.queryObject(UnManagedVolume.class,
                    unManagedVolumeUri);

            checkUnmanagedVolumePartiallyDiscovered(unManagedVolume, unManagedVolumeUri);

            StringSetMap unManagedVolumeInformation = unManagedVolume.getVolumeInformation();

            // a VPLEX volume and snapshot will not have an associated pool
            if (!isVplexVolume(unManagedVolume) && !isSnapshot(unManagedVolume)) {
                checkStoragePoolValidForUnManagedVolumeUri(unManagedVolumeInformation,
                        dbClient, unManagedVolumeUri);
            }

            checkVPoolValidForGivenUnManagedVolumeUris(unManagedVolumeInformation, unManagedVolume,
                    vPool.getId());
        }
    }

    /**
     * Checks that the Unmanaged Volume is compatible with the Virtual Pool's protection settings.
     * 
     * @param vpool the Virtual Pool
     * @param unManagedVolume the Unmanaged Volume
     * @param dbClient an instance of the database client
     * 
     * @throws IngestionException
     */
    public static void checkVPoolValidForUnManagedVolumeInProtectedMode(
            VirtualPool vpool, UnManagedVolume unManagedVolume, DbClient dbClient) throws IngestionException {

        // cannot ingest volumes other than VPlex virtual volumes into VPlex virtual pools.
        boolean haEnabledVpool = VirtualPool.vPoolSpecifiesHighAvailability(vpool);
        boolean isVplexVolume = VolumeIngestionUtil.isVplexVolume(unManagedVolume);
        if (haEnabledVpool && !isVplexVolume) {
            throw IngestionException.exceptions.cannotIngestNonVplexVolumeIntoVplexVpool(unManagedVolume.getLabel());
        }

        boolean remoteProtectionEnabled = VirtualPool.vPoolSpecifiesSRDF(vpool);
        _logger.debug("Remote protection enabled {}, {}", remoteProtectionEnabled, unManagedVolume.getId());
        StringSetMap unManagedVolumeInformation = unManagedVolume.getVolumeInformation();
        StringSet copyModes = unManagedVolumeInformation.get(SupportedVolumeInformation.REMOTE_COPY_MODE.toString());
        String remoteMirrorEnabledInVolume = unManagedVolume.getVolumeCharacterstics().get(
                SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString());
        String type = PropertySetterUtil.extractValueFromStringSet(SupportedVolumeInformation.REMOTE_VOLUME_TYPE.toString(),
                unManagedVolumeInformation);
        _logger.debug("UnManaged Volume Remote mirror Enabled {}", remoteMirrorEnabledInVolume);
        if (remoteProtectionEnabled) {
            // Usecase where VPool is remote protection enabled and want to ingest replicas into the same VPool.
            if (VolumeIngestionUtil.isParentSRDFProtected(unManagedVolume, dbClient)) {
                _logger.info("Found a Replica {} and its source volume is SRDF protected.", unManagedVolume.getId());
                return;
            }
            if (RemoteMirrorObject.Types.TARGET.toString().equalsIgnoreCase(type)) {
                _logger.warn(
                        "UnManaged Volume {} is a SRDF Target, hence cannot be associated with VPool which contains SRDF remote settings configured. Skipping Ingestion",
                        unManagedVolume.getId());
                throw IngestionException.exceptions.unmanagedVolumeIsAnSrdfTarget(unManagedVolume.getLabel());
            }
            if (null == copyModes) {
                throw IngestionException.exceptions.unmanagedVolumeWithoutSRDFProtection(unManagedVolume.getLabel());
            }
            String copyMode = null;
            for (String cMode : copyModes) {
                copyMode = cMode;
                break;
            }
            _logger.debug("UnManaged Volume Copy Mode {}", copyMode);
            if (!Boolean.parseBoolean(unManagedVolume.getVolumeCharacterstics().get(
                    SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString()))) {
                _logger.warn(
                        "UnManaged Volume {} is not remote protected, hence cannot be associated with VPool with remote protection configured. Skipping Ingestion",
                        unManagedVolume.getId(), copyMode);
                throw IngestionException.exceptions.srdfVpoolRemoteProtectionCopyModeMismatch(unManagedVolume.getLabel(), copyMode);
            } else {
                Map<String, List<String>> groupCopyModesByVPools = VirtualPool.groupRemoteCopyModesByVPool(vpool, dbClient);
                Set<String> supportedVPoolCopyModes = VolumeIngestionUtil
                        .getSupportedCopyModesFromGivenRemoteSettings(groupCopyModesByVPools);
                if (null == copyMode
                        || (!SupportedCopyModes.ALL.toString().equalsIgnoreCase(copyMode) && !supportedVPoolCopyModes.contains(copyMode))) {
                    _logger.warn(
                            "UnManaged Volume {} is remote protected via {}, hence cannot be associated "
                                    + "with VPool which doesn't contain this mode in at least one of its remote settings. Skipping Ingestion",
                            unManagedVolume.getId(), copyMode);
                    throw IngestionException.exceptions.srdfVolumeRemoteProtectionCopyModeMismatch(unManagedVolume.getLabel(), copyMode);
                }
            }
        } else if (RemoteMirrorObject.Types.SOURCE.toString().equalsIgnoreCase(type)
                &&
                Boolean.parseBoolean(unManagedVolume.getVolumeCharacterstics().get(
                        SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString()))) {
            _logger.warn(
                    "UnManaged Volume {} is remote protected, hence cannot be associated with VPool without remote protection configured. Skipping Ingestion",
                    unManagedVolume.getId());
            throw IngestionException.exceptions.srdfVolumeRemoteProtectionMismatch(unManagedVolume.getLabel());
        }
    }

    /**
     * Verify whether unManagedVolume is a replica of a source volume which is SRDF protected.
     * 
     * 1. Verify whether sourceVolume is a UnManagedVolume.
     * 2. If it is UnManagedVolume, then check whether its REMOTE_MIRRORING enabled or not.
     * 3. If it is a ingested Volume, then check whether its personality is set or not.
     * 
     * @param unManagedVolume
     * @param dbClient
     * @return
     */
    private static boolean isParentSRDFProtected(UnManagedVolume unManagedVolume, DbClient dbClient) {
        boolean isParentSRDFProtected = false;
        String parentVolumeNativeGuid = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME.toString(),
                unManagedVolume.getVolumeInformation());
        if (null != parentVolumeNativeGuid) {
            StringSet umvSet = new StringSet();
            umvSet.add(parentVolumeNativeGuid);
            List<URI> unManagedvolumeList = getUnManagedVolumeUris(umvSet, dbClient);
            if (!unManagedvolumeList.isEmpty()) {
                for (URI unManagedVolumeURI : unManagedvolumeList) {
                    _logger.debug("Found a replica {} source volume in unmanagedVolumes.", unManagedVolume.getId());
                    UnManagedVolume parentUnManagedVolume = dbClient.queryObject(UnManagedVolume.class, unManagedVolumeURI);
                    String remoteMirrorEnabledOnParent = parentUnManagedVolume.getVolumeCharacterstics().get(
                            SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString());
                    isParentSRDFProtected = (null != remoteMirrorEnabledOnParent && Boolean.valueOf(remoteMirrorEnabledOnParent));
                    break;
                }
            } else {
                StringSet ingestedVolumeNativeGuids = getListofVolumeIds(umvSet);
                List<URI> volumeURIs = getVolumeUris(ingestedVolumeNativeGuids, dbClient);
                if (!volumeURIs.isEmpty()) {
                    _logger.debug("Found a ingested volume of a replica {} source", unManagedVolume.getId());
                    List<Volume> volumeList = dbClient.queryObject(Volume.class, volumeURIs);
                    for (Volume volume : volumeList) {
                        isParentSRDFProtected = (null != volume.getPersonality());
                        break;
                    }
                }
            }
        }
        return isParentSRDFProtected;
    }

    /**
     * Checks that the Virtual Pool has a protocols setting that is
     * compatible with the UnManagedVolume. Does not apply to UnManagedVolumes
     * that are not exported.
     * 
     * @param vpool the virtual pool requested
     * @param unManagedVolume the unmanaged volume being ingested
     * @param dbClient database client
     * @return true if the virtual pool is compatible with
     *         the unmaanged volume's export's initiators' protocols
     */
    public static boolean checkVPoolValidForExportInitiatorProtocols(
            VirtualPool vpool, UnManagedVolume unManagedVolume, DbClient dbClient) {
        if (unManagedVolume.getInitiatorUris().isEmpty()) {
            _logger.info("unmanaged volume {} has no initiators, so no need to verify vpool protocols",
                    unManagedVolume.getNativeGuid());
            return true;
        }

        _logger.info("checking validity of virtual pool {} protocols for unmanaged volume {}",
                vpool.getLabel(), unManagedVolume.getNativeGuid());
        Set<String> initiatorProtocols = new HashSet<String>();

        for (String initUri : unManagedVolume.getInitiatorUris()) {
            Initiator init = dbClient.queryObject(Initiator.class, URI.create(initUri));
            if (init != null) {
                initiatorProtocols.add(init.getProtocol());
            }
        }

        _logger.info("this unmanaged volume's export's initiators' protocols are {}",
                Joiner.on(",").join(initiatorProtocols));
        _logger.info("the requested virtual pool's protocols are {}",
                Joiner.on(",").join(vpool.getProtocols()));

        boolean atLeastOneProtocolIsSatisfied = false;
        for (String protocol : initiatorProtocols) {
            if (vpool.getProtocols().contains(protocol)) {
                _logger.info("at least one protocol matches between the volume and virtual pool");
                atLeastOneProtocolIsSatisfied = true;
                break;
            }
        }

        if (!atLeastOneProtocolIsSatisfied) {
            _logger.warn("no protocol overlap found between unmanaged volume and "
                    + "virtual pool. ingestion will be skipped.");
        }
        return atLeastOneProtocolIsSatisfied;
    }

    public static StringSet getListofVolumeIds(StringSet targets) {
        StringSet targetVolumeIds = new StringSet();
        for (String target : targets) {
            targetVolumeIds.add(target.replace(VolumeIngestionUtil.UNMANAGEDVOLUME, VolumeIngestionUtil.VOLUME));
        }
        return targetVolumeIds;
    }

    public static List<URI> getVolumeUris(StringSet targets, DbClient dbClient) {
        List<URI> targetUriList = new ArrayList<URI>();
        for (String targetId : targets) {
            List<URI> targetUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeNativeGuidConstraint(targetId));
            if (null != targetUris && !targetUris.isEmpty()) {
                targetUriList.addAll(targetUris);
            } else {
                _logger.info("Volume not ingested yet {}", targetId);
            }
        }
        return targetUriList;
    }

    public static List<BlockObject> getCloneObjects(StringSet targets, Map<String, BlockObject> createdObjectMap, DbClient dbClient) {
        List<BlockObject> targetUriList = new ArrayList<BlockObject>();
        for (String targetId : targets) {
            List<URI> targetUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeNativeGuidConstraint(targetId));
            if (null != targetUris && !targetUris.isEmpty()) {
                targetUriList.add((BlockObject) dbClient.queryObject(targetUris.get(0)));
            } else {
                _logger.info("Clone not ingested yet {}. Checking in the created object map", targetId);
                // check in the created object map
                BlockObject blockObject = createdObjectMap.get(targetId);
                if (blockObject != null) {
                    _logger.info("Found the clone in the created object map");
                    targetUriList.add(blockObject);
                }
            }
        }
        return targetUriList;
    }

    public static List<BlockObject> getMirrorObjects(StringSet targets, Map<String, BlockObject> createdObjectMap, DbClient dbClient) {
        List<BlockObject> targetUriList = new ArrayList<BlockObject>();
        for (String targetId : targets) {
            List<URI> targetUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getMirrorByNativeGuid(targetId));
            if (null != targetUris && !targetUris.isEmpty()) {
                targetUriList.add((BlockObject) dbClient.queryObject(targetUris.get(0)));
            } else {
                _logger.info("Mirror not ingested yet {}", targetId);
                // check in the created object map
                BlockObject blockObject = createdObjectMap.get(targetId);
                if (blockObject != null) {
                    _logger.info("Found the mirror in the created object map");
                    targetUriList.add(blockObject);
                }
            }
        }
        return targetUriList;
    }

    public static List<BlockObject> getSnapObjects(StringSet targets, Map<String, BlockObject> createdObjectMap, DbClient dbClient) {
        List<BlockObject> targetUriList = new ArrayList<BlockObject>();
        for (String targetId : targets) {
            List<URI> targetUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getBlockSnapshotsByNativeGuid(targetId));
            if (null != targetUris && !targetUris.isEmpty()) {
                targetUriList.add((BlockObject) dbClient.queryObject(targetUris.get(0)));
            } else {
                _logger.info("Snap not ingested yet {}", targetId);
                // check in the created object map
                BlockObject blockObject = createdObjectMap.get(targetId);
                if (blockObject != null) {
                    _logger.info("Found the snap in the created object map");
                    targetUriList.add(blockObject);
                }
            }
        }
        return targetUriList;
    }

    public static StringSet addSRDFTargetsToSet(List<URI> targetUris) {
        StringSet targetVolumeUris = new StringSet();
        for (URI uri : targetUris) {
            targetVolumeUris.add(uri.toString());
        }
        return targetVolumeUris;
    }

    public static List<URI> getUnManagedVolumeUris(StringSet targets, DbClient dbClient) {
        List<URI> targetUriList = new ArrayList<URI>();
        for (String targetId : targets) {
            List<URI> targetUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeInfoNativeIdConstraint(targetId));
            if (null != targetUris && !targetUris.isEmpty()) {
                targetUriList.addAll(targetUris);
            } else {
                _logger.info("UnManagedVolume not yet ingested {}", targetId);
            }
        }
        return targetUriList;
    }

    public static void checkUnManagedResourceIsRecoverPointEnabled(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String isRecoverPointEnabled = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.IS_RECOVERPOINT_ENABLED.toString());
        if (null != isRecoverPointEnabled && Boolean.parseBoolean(isRecoverPointEnabled)) {
            throw IngestionException.exceptions.unmanagedVolumeIsRecoverpointEnabled(unManagedVolume.getLabel());
        }

        return;
    }

    public static boolean checkUnManagedResourceAddedToConsistencyGroup(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String isVolumeAddedToConsistencyGroup = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.IS_VOLUME_ADDED_TO_CONSISTENCYGROUP.toString());
        if (null != isVolumeAddedToConsistencyGroup && Boolean.parseBoolean(isVolumeAddedToConsistencyGroup)) {
            return true;
        }
        return false;
    }

    public static boolean checkUnManagedVolumeHasReplicas(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String volumeHasReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.HAS_REPLICAS.toString());
        String volumeHasRemoteReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString());
        if (null != volumeHasReplicas
                && Boolean.parseBoolean(volumeHasReplicas)
                || (null != volumeHasRemoteReplicas && Boolean
                        .parseBoolean(volumeHasRemoteReplicas))) {
            return true;
        }
        return false;
    }

    /**
     * check if unManagedVolume is already exported to Host
     * 
     * @param unManagedVolume
     * @throws Exception
     */
    public static boolean checkUnManagedResourceAlreadyExported(
            UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String isVolumeExported = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.IS_VOLUME_EXPORTED.toString());
        if (null != isVolumeExported && Boolean.parseBoolean(isVolumeExported)) {
            return true;
        }
        return false;
    }

    public static void checkUnManagedResourceIngestable(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume
                .getVolumeCharacterstics();
        String isVolumeIngestable = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.IS_INGESTABLE.toString());
        if (null != isVolumeIngestable && Boolean.parseBoolean(isVolumeIngestable)) {
            return;
        }

        throw IngestionException.exceptions.unmanagedVolumeNotIngestable(unManagedVolume.getLabel());
    }

    /**
     * validate Host IO limits
     * 
     * @param vpool
     * @param unManagedVolume
     * @return
     */
    public static boolean validateHostIOLimit(VirtualPool vpool, UnManagedVolume unManagedVolume) {
        Set<String> hostIoBws = PropertySetterUtil.extractValuesFromStringSet(
                SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH.name(), unManagedVolume.getVolumeInformation());
        Set<String> hostIoPs = PropertySetterUtil.extractValuesFromStringSet(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.name(),
                unManagedVolume.getVolumeInformation());
        String vPoolBw = "0";
        if (vpool.getHostIOLimitBandwidth() != null) {
            vPoolBw = String.valueOf(vpool.getHostIOLimitBandwidth());
        }

        String vPoolIoPs = "0";
        if (vpool.getHostIOLimitIOPs() != null) {
            vPoolIoPs = String.valueOf(vpool.getHostIOLimitIOPs());
        }
        _logger.info("Volume's bw {} and iops {} --> Virtual Pool's bw {} and iops {}", new Object[] { Joiner.on(",").join(hostIoBws),
                Joiner.on(",").join(hostIoPs), vPoolBw, vPoolIoPs });
        // values [0,2000] hence if size is > 1 then we need to explicitly return false, if vpool value is 0.
        if (hostIoBws.size() > 1) {
            if ("0".equalsIgnoreCase(vPoolBw)) {
                return false;
            }
        }

        if (hostIoPs.size() > 1) {
            if ("0".equalsIgnoreCase(vPoolIoPs)) {
                return false;
            }
        }
        return hostIoBws.contains(vPoolBw) && hostIoPs.contains(vPoolIoPs);

    }

    /**
     * Check if valid storage Pool is associated with UnManaged Volume Uri is valid.
     * 
     * @param unManagedVolumeInformation
     * @param dbClient
     * @param unManagedVolumeUri
     * @throws Exception
     */
    private static void checkStoragePoolValidForUnManagedVolumeUri(
            StringSetMap unManagedVolumeInformation, DbClient dbClient,
            URI unManagedVolumeUri) throws Exception {
        String pool = PropertySetterUtil.extractValueFromStringSet(VolumeObjectProperties.STORAGE_POOL.toString(),
                unManagedVolumeInformation);
        if (null == pool) {
            throw APIException.internalServerErrors.storagePoolError("", "Volume", unManagedVolumeUri);
        }
        StoragePool poolObj = dbClient.queryObject(StoragePool.class, URI.create(pool));
        if (null == poolObj) {
            throw APIException.internalServerErrors.noStoragePool(pool, "Volume", unManagedVolumeUri);
        }
    }

    /**
     * Get Supported vPool from PreExistingVolume Storage Pools. Verify if the given vPool is part of
     * the supported vPool List.
     * 
     * @param preExistVolumeInformation
     * @param vpoolUri
     */
    private static void checkVPoolValidForGivenUnManagedVolumeUris(
            StringSetMap preExistVolumeInformation, UnManagedVolume unManagedVolume, URI vpoolUri) {
        StringSet supportedVPoolUris = preExistVolumeInformation
                .get(SupportedVolumeInformation.SUPPORTED_VPOOL_LIST
                        .toString());
        if (null == supportedVPoolUris) {
            if (isVplexVolume(unManagedVolume)) {
                throw APIException.internalServerErrors.noMatchingVplexVirtualPool(unManagedVolume.getLabel(), unManagedVolume.getId());
            }

            throw APIException.internalServerErrors.storagePoolNotMatchingVirtualPool("Volume", unManagedVolume.getId());
        }
        if (!supportedVPoolUris.contains(vpoolUri.toString())) {
            throw APIException.internalServerErrors.virtualPoolNotMatchingStoragePool(vpoolUri, "Volume", unManagedVolume.getId(),
                    Joiner.on("\t").join(supportedVPoolUris));
        }
    }

    /**
     * Gets and verifies the vPool passed in the request.
     * 
     * @param project
     *            A reference to the project.
     * @return A reference to the vPool.
     */
    public static VirtualPool getVirtualPoolForVolumeCreateRequest(
            Project project, URI vPoolUri, PermissionsHelper permissionsHelper,
            DbClient dbClient) {
        ArgValidator.checkUri(vPoolUri);
        VirtualPool vPool = dbClient.queryObject(VirtualPool.class, vPoolUri);
        ArgValidator.checkEntity(vPool, vPoolUri, false);
        if (!VirtualPool.Type.block.name().equals(vPool.getType())) {
            throw APIException.badRequests.virtualPoolNotForFileBlockStorage(VirtualPool.Type.block.name());
        }
        return vPool;
    }

    /**
     * Gets and verifies that the varray passed in the request is accessible to the tenant.
     * 
     * @param project
     *            A reference to the project.
     * @return A reference to the varray.
     */
    public static VirtualArray getVirtualArrayForVolumeCreateRequest(
            Project project, URI neighborhoodUri, PermissionsHelper permissionsHelper,
            DbClient dbClient) {
        VirtualArray neighborhood = dbClient.queryObject(VirtualArray.class,
                neighborhoodUri);
        ArgValidator.checkEntity(neighborhood, neighborhoodUri, false);
        permissionsHelper.checkTenantHasAccessToVirtualArray(project.getTenantOrg().getURI(), neighborhood);
        return neighborhood;
    }

    public static Set<String> getSupportedCopyModesFromGivenRemoteSettings(Map<String, List<String>> groupCopyModesByVPools) {
        Set<String> copyModes = new HashSet<String>();
        if (groupCopyModesByVPools != null) {
            for (Entry<String, List<String>> entry : groupCopyModesByVPools.entrySet()) {
                copyModes.addAll(entry.getValue());
            }
        }
        return copyModes;
    }

    public static long getTotalUnManagedVolumeCapacity(DbClient dbClient,
            List<URI> unManagedVolumeUris) {
        BigInteger totalUnManagedVolumeCapacity = new BigInteger("0");
        try {
            Iterator<UnManagedVolume> unManagedVolumes = dbClient.queryIterativeObjects(UnManagedVolume.class,
                    unManagedVolumeUris);

            while (unManagedVolumes.hasNext()) {
                UnManagedVolume unManagedVolume = unManagedVolumes.next();
                StringSetMap unManagedVolumeInfo = unManagedVolume
                        .getVolumeInformation();
                if (null == unManagedVolumeInfo) {
                    continue;
                }
                String unManagedVolumeCapacity = PropertySetterUtil
                        .extractValueFromStringSet(SupportedVolumeInformation.PROVISIONED_CAPACITY
                                .toString(), unManagedVolumeInfo);
                if (null != unManagedVolumeCapacity && !unManagedVolumeCapacity.isEmpty()) {
                    totalUnManagedVolumeCapacity = totalUnManagedVolumeCapacity
                            .add(new BigInteger(unManagedVolumeCapacity));
                }

            }
        } catch (Exception e) {
            throw APIException.internalServerErrors.capacityComputationFailed();
        }
        return totalUnManagedVolumeCapacity.longValue();
    }

    /**
     * Returns true if the UnManagedVolume represents a VPLEX virtual volume.
     * 
     * @param volume the UnManagedVolume in question
     * @return true if the volume is a VPLEX virtual volume
     */
    public static boolean isVplexVolume(UnManagedVolume volume) {
        if (null == volume.getVolumeCharacterstics()) {
            return false;
        }

        String status = volume.getVolumeCharacterstics()
                .get(SupportedVolumeCharacterstics.IS_VPLEX_VOLUME.toString());
        return TRUE.equals(status);
    }

    public static boolean isSnapshot(UnManagedVolume volume) {
        if (null == volume.getVolumeCharacterstics()) {
            return false;
        }

        String status = volume.getVolumeCharacterstics()
                .get(SupportedVolumeCharacterstics.IS_SNAP_SHOT.toString());
        return TRUE.equals(status);
    }

    public static boolean isMirror(UnManagedVolume volume) {
        if (null == volume.getVolumeCharacterstics()) {
            return false;
        }

        String status = volume.getVolumeCharacterstics()
                .get(SupportedVolumeCharacterstics.IS_LOCAL_MIRROR.toString());
        return TRUE.equals(status);
    }

    public static boolean isFullCopy(UnManagedVolume volume) {
        if (null == volume.getVolumeCharacterstics()) {
            return false;
        }

        String status = volume.getVolumeCharacterstics()
                .get(SupportedVolumeCharacterstics.IS_FULL_COPY.toString());
        return TRUE.equals(status);
    }

    /**
     * Determines if the varray specified in the ingestion request is valid for
     * the volume being ingested. Principally applies to VPLEX volumes, which
     * can reside on one or both cluster of the VPLEX system, where each cluster
     * is in a different virtual array.
     * 
     * @param unmanagedVolume The unmanaged volume to be ingested.
     * @param varrayURI The URI of the varray into which it will be ingested.
     * @param dbClient A reference to a DB client.
     * 
     * @return true if the unmanaged volume can be ingested into the varray, false otherwise.
     */
    public static boolean isValidVarrayForUnmanagedVolume(UnManagedVolume unmanagedVolume, URI varrayURI,
            Map<String, String> clusterIdToNameMap, Map<String, String> varrayToClusterIdMap, DbClient dbClient) {
        if (isVplexVolume(unmanagedVolume)) {
            StringSet unmanagedVolumeClusters = unmanagedVolume.getVolumeInformation().get(
                    SupportedVolumeInformation.VPLEX_CLUSTER_IDS.toString());
            if (unmanagedVolumeClusters == null) {
                _logger.warn("Unmanaged VPLEX volume {} has no cluster info", unmanagedVolume.getLabel());
                return false;
            }

            String varrayClusterId = varrayToClusterIdMap.get(varrayURI.toString());
            if (null == varrayClusterId) {
                varrayClusterId = ConnectivityUtil.getVplexClusterForVarray(varrayURI, unmanagedVolume.getStorageSystemUri(), dbClient);
                varrayToClusterIdMap.put(varrayURI.toString(), varrayClusterId);
                _logger.debug("added {} to varrayToClusterIdMap cache", varrayClusterId);
            }

            if (varrayClusterId.equals(ConnectivityUtil.CLUSTER_UNKNOWN)) {
                _logger.info("Virtual array {} is not associated with either cluster of VPLEX {}", varrayURI, null);
                return false;
            }

            String varrayClusterName = clusterIdToNameMap.get(varrayClusterId);
            if (null == varrayClusterName) {
                varrayClusterName = VPlexControllerUtils.getClusterNameForId(
                        varrayClusterId, unmanagedVolume.getStorageSystemUri(), dbClient);
                clusterIdToNameMap.put(varrayClusterId, varrayClusterName);
                _logger.debug("added {} to clusterIdToNameMap cache", varrayClusterName);
            }

            if (null == varrayClusterName) {
                _logger.info("Unmanaged VPLEX volume {} cannot be ingested; "
                        + "couldn't find VPLEX cluster name for id {}", unmanagedVolume.getLabel(), varrayClusterId);
                return false;
            }
            if (!unmanagedVolumeClusters.contains(varrayClusterName)) {
                _logger.info("Unmanaged VPLEX volume {} cannot be ingested to varray {}", unmanagedVolume.getLabel(), varrayURI);
                return false;
            }
        }

        return true;
    }

    /**
     * Checks whether an unmanaged volume is inactive.
     * 
     * @param unManagedVolume the unmanaged volume to check
     */
    public static void checkUnmanagedVolumeInactive(UnManagedVolume unManagedVolume) throws IngestionException {
        if (unManagedVolume.getInactive()) {
            _logger.warn("UnManaged Volume {} is in inactive state. Skipping Ingestion", unManagedVolume.getLabel());
            throw IngestionException.exceptions.unmanagedVolumeIsInactive(unManagedVolume.getLabel());
        }
    }

    /**
     * Checks if an unManagedVolume is partially discovered
     * 
     * @param unManagedVolume
     * @param unManagedVolumeUri
     * @throws IngestionException
     */
    public static void checkUnmanagedVolumePartiallyDiscovered(
            UnManagedVolume unManagedVolume, URI unManagedVolumeUri)
            throws IngestionException {

        if (null == unManagedVolume
                || null == unManagedVolume.getVolumeCharacterstics()
                || null == unManagedVolume.getVolumeInformation()) {

            _logger.warn("UnManaged Volume {} partially discovered, hence not enough "
                    + "information available to validate neither "
                    + "virtualPool nor other criterias.  Skipping Ingestion",
                    unManagedVolumeUri);

            throw IngestionException.exceptions
                    .unmanagedVolumePartiallyIngested(unManagedVolumeUri.toString());
        }
    }

    /**
     * Returns a BlockConsistencyGroup URI. If an existing group matches
     * the name, project, tenant, and varray, then it can be re-used.
     * Otherwise, a new BlockConsistencyGroup will be created in ViPR.
     * 
     * @param unManagedVolume the unmanaged virtual volume object
     * @param vpool the VirtualPool for the Volume
     * @param projectUri the Project URI
     * @param tenantUri the Tenant URI
     * @param varrayUri the VirtualArray URI
     * @param _dbClient the ViPR database client
     * @return a BlockConsistencyGroup URI, or null if none could be found or created
     */
    public static URI getVplexConsistencyGroup(UnManagedVolume unManagedVolume, VirtualPool vpool,
            URI projectUri, URI tenantUri, URI varrayUri, DbClient _dbClient) {

        String cgName = PropertySetterUtil.extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_CONSISTENCY_GROUP_NAME.toString(),
                unManagedVolume.getVolumeInformation());

        if (cgName != null) {
            _logger.info("VPLEX UnManagedVolume {} is added to consistency group {}",
                    unManagedVolume.getLabel(), cgName);

            if (!vpool.getMultivolumeConsistency()) {
                _logger.warn("The requested Virtual Pool {} does not have "
                        + "the Multi-Volume Consistency flag set, and this volume "
                        + "is part of a consistency group.", vpool.getLabel());
                throw IngestionException.exceptions
                        .unmanagedVolumeVpoolConsistencyGroupMismatch(vpool.getLabel(), unManagedVolume.getLabel());
            } else {

                List<BlockConsistencyGroup> groups = CustomQueryUtility
                        .queryActiveResourcesByConstraint(_dbClient,
                                BlockConsistencyGroup.class, PrefixConstraint.Factory
                                        .getFullMatchConstraint(
                                                BlockConsistencyGroup.class, "label",
                                                cgName));

                StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class,
                        unManagedVolume.getStorageSystemUri());

                if (!groups.isEmpty()) {
                    for (BlockConsistencyGroup cg : groups) {
                        // need to check for several matching properties
                        URI storageControllerUri = cg.getStorageController();
                        URI virtualArrayUri = cg.getVirtualArray();
                        if (null != storageControllerUri && null != virtualArrayUri) {
                            if (storageControllerUri.equals(storageSystem.getId()) &&
                                    virtualArrayUri.equals(varrayUri) &&
                                    cg.getProject().getURI().equals(projectUri) &&
                                    cg.getTenant().getURI().equals(tenantUri)) {
                                _logger.info("Found a matching BlockConsistencyGroup {} "
                                        + "for virtual volume {}.", cgName, unManagedVolume.getLabel());
                                return cg.getId();
                            }
                        }
                    }
                }

                _logger.info("Did not find an existing Consistency Group named {} that is associated "
                        + "already with the requested VPLEX device and Virtual Array. "
                        + "ViPR will create a new one.", cgName);

                // create a new consistency group
                BlockConsistencyGroup cg = new BlockConsistencyGroup();
                cg.setId(URIUtil.createId(BlockConsistencyGroup.class));
                cg.setLabel(cgName);
                cg.setProject(new NamedURI(projectUri, cgName));
                cg.setTenant(new NamedURI(tenantUri, cgName));
                cg.addConsistencyGroupTypes(Types.VPLEX.name());
                cg.setStorageController(storageSystem.getId());
                cg.setVirtualArray(varrayUri);

                _dbClient.createObject(cg);

                _logger.info("Created new BlockConsistencyGroup {} with id {}", cgName, cg.getId());
                return cg.getId();
            }
        }

        return null;
    }

    /**
     * Find List of Export Masks already available in DB
     * 
     * @param masks
     * @param dbClient
     * @return
     */
    public static List<UnManagedExportMask> getExportsMaskAlreadyIngested(List<UnManagedExportMask> masks,
            DbClient dbClient) {
        List<UnManagedExportMask> unManagedMasks = new ArrayList<UnManagedExportMask>();
        for (UnManagedExportMask mask : masks) {
            @SuppressWarnings("deprecation")
            List<URI> maskUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getExportMaskByNameConstraint(mask.getLabel()));
            if (null == maskUris || maskUris.isEmpty()) {
                unManagedMasks.add(mask);
            } else {
                _logger.info("Export Mask {} already ingested ", mask.getLabel());
            }
        }
        return unManagedMasks;
    }

    /**
     * Find Export Masks already available in DB
     * 
     * @param mask
     * @param dbClient
     * @return
     */
    public static ExportMask getExportsMaskAlreadyIngested(UnManagedExportMask mask, DbClient dbClient) {
        ExportMask exportMask = null;
        @SuppressWarnings("deprecation")
        List<URI> maskUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getExportMaskByNameConstraint(mask.getMaskName()));
        if (null != maskUris && !maskUris.isEmpty()) {
            return dbClient.queryObject(ExportMask.class, maskUris.get(0));
        }

        return exportMask;
    }

    /**
     * initialize Export Group
     * 
     * @param project
     * @param type
     * @param vArray
     * @param label
     * @param dbClient
     * @param nameGenerator
     * @param tenantOrg
     * @return
     */
    public static ExportGroup initializeExportGroup(Project project, String type, URI vArray, String label,
            DbClient dbClient, ResourceAndUUIDNameGenerator nameGenerator, TenantOrg tenantOrg) {
        ExportGroup exportGroup = new ExportGroup();
        exportGroup.setLabel(label);

        exportGroup.setType(type);
        exportGroup.setId(URIUtil.createId(ExportGroup.class));
        exportGroup.setProject(new NamedURI(project.getId(), exportGroup.getLabel()));
        exportGroup.setVirtualArray(vArray);
        exportGroup.setTenant(new NamedURI(project.getTenantOrg().getURI(), exportGroup.getLabel()));

        String generatedName = nameGenerator.generate(tenantOrg.getLabel(), exportGroup.getLabel(),
                exportGroup.getId().toString(), '_', 56);
        exportGroup.setGeneratedName(generatedName);
        return exportGroup;
    }

    /**
     * Create Export Mask in ViPR DB.
     * 
     * @param eligibleMask
     * @param system
     * @param unManagedVolume
     * @param exportGroup
     * @param volume
     * @param dbClient
     * @param host
     * @param cluster
     * @throws Exception
     */
    public static <T extends BlockObject> void createExportMask(UnManagedExportMask eligibleMask, StorageSystem system,
            UnManagedVolume unManagedVolume,
            ExportGroup exportGroup, T volume, DbClient dbClient, List<Host> hosts, Cluster cluster) throws Exception {
        _logger.info("Creating ExportMask for unManaged Mask {}", eligibleMask.getMaskName());
        List<URI> initiatorUris = new ArrayList<URI>(Collections2.transform(
                eligibleMask.getKnownInitiatorUris(), CommonTransformerFunctions.FCTN_STRING_TO_URI));
        List<Initiator> allInitiators = dbClient.queryObject(Initiator.class, initiatorUris);

        List<Initiator> userAddedInis = VolumeIngestionUtil.findUserAddedInisFromExistingIniListInMask(allInitiators, eligibleMask.getId(),
                dbClient);

        List<URI> storagePortUris = new ArrayList<URI>(Collections2.transform(
                eligibleMask.getKnownStoragePortUris(), CommonTransformerFunctions.FCTN_STRING_TO_URI));
        String exportMaskLabel = (null != cluster) ? cluster.getLabel() : hosts.get(0).getHostName();
        // update ZoneMappings and HLU's later if needed, now pass null
        ExportMaskUtils.initializeExportMaskWithVolumes(system, exportGroup, eligibleMask.getMaskName(), exportMaskLabel, allInitiators,
                null, storagePortUris, eligibleMask.getZoningMap(), volume, eligibleMask.getUnmanagedInitiatorNetworkIds(),
                eligibleMask.getNativeId(), userAddedInis, dbClient);

        // remove unmanaged mask if created if the block object is not marked as internal
        if (!volume.checkInternalFlags(Flag.NO_PUBLIC_ACCESS)) {
            unManagedVolume.getUnmanagedExportMasks().remove(eligibleMask.getId().toString());
            eligibleMask.getUnmanagedVolumeUris().remove(unManagedVolume.getId().toString());
        }

        updateExportGroup(exportGroup, volume, dbClient, allInitiators, hosts, cluster);

    }

    /**
     * validate Storage Ports in Virtual Array
     * 
     * @param dbClient
     * @param varray
     * @param portsInUnManagedMask
     * @param mask
     * @return
     */
    public static <T extends BlockObject> boolean validateStoragePortsInVarray(DbClient dbClient, T volume, URI varray,
            Set<String> portsInUnManagedMask, UnManagedExportMask mask) {

        List<URI> storagePortUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getVirtualArrayStoragePortsConstraint(varray.toString()));
        storagePortUris = filterOutUnregisteredPorts(dbClient, storagePortUris);
        Set<String> storagePortUriStr = new HashSet<String>((Collections2.transform(storagePortUris,
                CommonTransformerFunctions.FCTN_URI_TO_STRING)));
        SetView<String> diff = Sets.difference(portsInUnManagedMask, storagePortUriStr);
        if (!diff.isEmpty()) {
            _logger.warn("Storage Ports {} in unmanaged mask {} is not available in VArray {}", new Object[] {
                    Joiner.on(",").join(diff), mask.getMaskName(), varray });
            if (volume instanceof Volume) {
                Volume vol = (Volume) volume;
                URI haVarray = checkVplexHighAvailabilityArray(vol, dbClient);
                if (null != haVarray) {
                    _logger.info("found high availabilty virtual array {}, " + "so checking for storage ports over there",
                            haVarray);
                    storagePortUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVirtualArrayStoragePortsConstraint(haVarray.toString()));
                    storagePortUris = filterOutUnregisteredPorts(dbClient, storagePortUris);
                    storagePortUriStr = new HashSet<String>((Collections2.transform(storagePortUris,
                            CommonTransformerFunctions.FCTN_URI_TO_STRING)));
                    diff = Sets.difference(portsInUnManagedMask, storagePortUriStr);
                    if (!diff.isEmpty()) {
                        _logger.warn("Storage Ports {} in unmanaged mask {} are not available in high "
                                + "availability varray {}, matching fails",
                                new Object[] { Joiner.on(",").join(diff), mask.getMaskName(), haVarray });
                        return false;
                    } else {
                        _logger.info("Storage Ports {} in unmanaged mask {} found in "
                                + "high availability varray {}, so this mask is okay", new Object[] { Joiner.on(",").join(diff),
                                mask.getMaskName(), haVarray });
                        return true;
                    }
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Remove UNREGISTERED storage ports from a URI list.
     * 
     * @param dbClient -- DbClient
     * @param storagePortUris -- List<URI>
     * @return List<URI> StoragePorts with UNREGISTERED ports removed
     */
    static private List<URI> filterOutUnregisteredPorts(DbClient dbClient, List<URI> storagePortUris) {
        List<StoragePort> ports = dbClient.queryObject(StoragePort.class, storagePortUris);
        Iterator<StoragePort> portItr = ports.iterator();
        while (portItr.hasNext()) {
            StoragePort stPort = portItr.next();
            if (stPort.getRegistrationStatus().equalsIgnoreCase(RegistrationStatus.UNREGISTERED.toString())) {
                _logger.info("Removing unregistered port {}", stPort.getNativeGuid());
                storagePortUris.remove(stPort.getId());
            }
        }
        return storagePortUris;
    }

    /**
     * validating Initiators are registered.
     * 
     * @param initiators
     * @return
     */
    public static boolean validateInitiatorPortsRegistered(List<Initiator> initiators) {
        List<Initiator> regInis = new ArrayList<Initiator>();
        for (Initiator initiator : initiators) {
            if (RegistrationStatus.REGISTERED.name().equalsIgnoreCase(initiator.getRegistrationStatus())) {
                regInis.add(initiator);
            } else {
                _logger.info("Initiator {} not registered", initiator.getId());
            }
        }
        return !regInis.isEmpty();
    }

    /**
     * update Export Group
     * 
     * @param exportGroup
     * @param volume
     * @param dbClient
     * @param allInitiators
     * @param host
     * @param cluster
     */
    public static <T extends BlockObject> void updateExportGroup(ExportGroup exportGroup, T volume, DbClient dbClient,
            List<Initiator> allInitiators,
            List<Host> hosts, Cluster cluster) {

        for (Host host : hosts) {
            if (null == exportGroup.getHosts() || !exportGroup.getHosts().contains(host.getId().toString())) {
                exportGroup.addHost(host);
            }

        }

        if (null != cluster
                && (null == exportGroup.getClusters() || !exportGroup.getClusters().contains(
                        cluster.getId().toString()))) {
            exportGroup.addCluster(cluster);

        }

        for (Initiator ini : allInitiators) {
            if (exportGroup.getInitiators() == null
                    || !exportGroup.getInitiators().contains(ini.getId().toString())) {
                exportGroup.addInitiator(ini);

            }
        }

        // Do not add the block object to the export group if it has no public access
        if (!volume.checkInternalFlags(Flag.NO_PUBLIC_ACCESS)) {
            exportGroup.addVolume(volume.getId(), ExportGroup.LUN_UNASSIGNED);
        }

        if (volume instanceof Volume) {
            Volume vol = (Volume) volume;

            URI haVarray = checkVplexHighAvailabilityArray(vol, dbClient);
            if (null != haVarray) {
                exportGroup.putAltVirtualArray(
                        volume.getStorageController().toString(), haVarray.toString());
            }
        }
    }

    /**
     * Find Matching Masks for Host
     * 
     * @param unManagedMasks
     * @param initiatorUris
     * @param iniByProtocol
     * @param dbClient
     * @param vArray
     * @param vPoolURI
     * @param Host host
     * @param initiatorsPartOfCluster This field will populated, only if Host is part of a cluster
     * @return
     */
    public static <T extends BlockObject> List<UnManagedExportMask> findMatchingExportMaskForHost(T volume,
            List<UnManagedExportMask> unManagedMasks, Set<String> initiatorUris,
            Map<String, Set<String>> iniByProtocol, DbClient dbClient, URI vArray, URI vPoolURI,
            boolean hostPartOfCluster, Set<String> initiatorsPartOfCluster, URI clusterUri) {
        List<UnManagedExportMask> eligibleMasks = new ArrayList<UnManagedExportMask>();
        Iterator<UnManagedExportMask> itr = unManagedMasks.iterator();
        while (itr.hasNext()) {
            // if required initiators are available in the mask, then choose it,
            // irrespective of actual initiators on the MV on Array

            // if all initiators are available, then choose it
            // else, group initiators by protocol
            // for each group, find if its a complete subset of the available
            // masking views, if yes then choose it
            // if its not a complete subset & if other unknown initiators are
            // not available, then choose it
            UnManagedExportMask mask = itr.next();
            if (!VolumeIngestionUtil.validateStoragePortsInVarray(dbClient, volume, vArray, mask.getKnownStoragePortUris(), mask)) {
                itr.remove();
                continue;
            }
            if (null != mask.getKnownInitiatorUris() && !mask.getKnownInitiatorUris().isEmpty()) {

                _logger.info("Group initiators by protocol {}",
                        Joiner.on(",").join(iniByProtocol.entrySet()));
                // group Initiators by Protocol
                for (Entry<String, Set<String>> entry : iniByProtocol.entrySet()) {
                    _logger.info("Processing Initiators by Protocol {} Group", entry.getValue());
                    if (hostPartOfCluster) {
                        /**
                         * If Host is part of a Cluster, then
                         * 
                         * ViPR ini || Existing Mask in Array
                         * case 1: I1,I2 I1,I3,I4,I2 [Verify whether I3,I4 are part of then same Cluster, then skip it]
                         * case 2: I1,I2,I3 I1,I2 -- mask selected
                         * case 3: I1,I3 I1,I2 -- not selected
                         * 
                         */
                        _logger.info("Host part of a Cluster- Comparing discovered [{}] with unmanaged [{}] ", Joiner.on(",")
                                .join(entry.getValue()), Joiner.on(",").join(mask.getKnownInitiatorUris()));
                        Set<String> ViPRDiscToExistingKnownInisDiff = Sets.difference(entry.getValue(),
                                mask.getKnownInitiatorUris());

                        if (ViPRDiscToExistingKnownInisDiff.isEmpty()) {

                            // check whether remaining existing initiators on
                            // mask are part of the cluster
                            Set<String> remainingInis = Sets.difference(mask.getKnownInitiatorUris(), entry.getValue());
                            Set<String> iniPartOfCluster = Sets.difference(remainingInis, initiatorsPartOfCluster);
                            _logger.info(
                                    "ViPR initiators are a complete subset of unmanaged mask's known initiators. Trying to find whether the other initiators {}"
                                            + " in the unmanaged mask are actually owned by the same cluster {} this host belongs to.",
                                    Joiner.on(",").join(iniPartOfCluster), clusterUri);
                            if (iniPartOfCluster.size() == remainingInis.size()) {
                                _logger.info(
                                        "Matched Mask Found {}, as there are no other initiators in existing mask owned by the cluster, this host belongs to.",
                                        mask.getMaskName());
                                if (verifyNumPath(Collections.singletonList(initiatorUris), mask.getZoningMap(),
                                        volume, vPoolURI, dbClient)) {
                                    eligibleMasks.add(mask);
                                }
                                itr.remove();
                            } else {
                                _logger.info(
                                        "Even though Existing UnManaged Mask {} contains subset of ViPR initiators, it can't be used as there are other initiators [{}] "
                                                + "in the mask which are owned by a different Hosts in the same cluster {} as this host belongs.",
                                        new Object[] { mask.getMaskName(), Joiner.on(",").join(iniPartOfCluster), clusterUri });
                            }
                        } else {

                            Set<String> existingknownInisToViprDiscDiff = Sets.difference(mask.getKnownInitiatorUris(),
                                    entry.getValue());

                            if (existingknownInisToViprDiscDiff.isEmpty()) {
                                _logger.info(
                                        "Matched Mask Found {}, as existing ViPR known initiators are a complete subset of ViPR discovered.",
                                        mask.getMaskName());
                                if (verifyNumPath(Collections.singletonList(initiatorUris), mask.getZoningMap(),
                                        volume, vPoolURI, dbClient)) {
                                    eligibleMasks.add(mask);
                                }
                                itr.remove();
                            } else {
                                _logger.info(
                                        "Existing ViPR known Initiators are not a complete subset of ViPR discovered, skipping the unmanaged mask {}",
                                        mask.getMaskName());
                            }

                        }

                    } else {
                        _logger.info("Host not part of any Cluster- Comparing discovered [{}] with unmanaged [{}] ",
                                Joiner.on(",").join(entry.getValue()), Joiner.on(",").join(mask.getKnownInitiatorUris()));
                        Set<String> existingknownInisToViprDiscDiff = Sets.difference(mask.getKnownInitiatorUris(),
                                entry.getValue());

                        if (existingknownInisToViprDiscDiff.isEmpty()) {
                            _logger.info("Matched Mask Found after Grouping by Protocol {}", mask.getMaskName());
                            if (verifyNumPath(Collections.singletonList(initiatorUris), mask.getZoningMap(),
                                    volume, vPoolURI, dbClient)) {
                                eligibleMasks.add(mask);
                            }
                            itr.remove();
                        } else {
                            _logger.info(
                                    "Existing Unmanaged mask initiators have other ViPR known initiators {} from a different Host, where in the given host is not part of any cluster",
                                    Joiner.on(",").join(existingknownInisToViprDiscDiff));
                        }
                    }

                }

            }

        }

        return eligibleMasks;
    }

    /**
     * Find Matching Masks for Cluster
     * 
     * @param unManagedMasks
     * @param initiatorUris
     * @param dbClient
     * @param vArray
     * @param vPool
     * @return
     */
    public static <T extends BlockObject> List<UnManagedExportMask> findMatchingExportMaskForCluster(T volume,
            List<UnManagedExportMask> unManagedMasks, List<Set<String>> initiatorUris, DbClient dbClient, URI vArray,
            URI vPoolURI, URI cluster) {
        List<UnManagedExportMask> eligibleMasks = new ArrayList<UnManagedExportMask>();

        Set<String> clusterInitiators = new HashSet<String>();
        for (Set<String> initiatorUriList : initiatorUris) {
            clusterInitiators.addAll(initiatorUriList);
        }
        Map<String, Set<String>> clusterIniByProtocol = groupInitiatorsByProtocol(clusterInitiators, dbClient);
        Iterator<UnManagedExportMask> itr = unManagedMasks.iterator();
        List<String> maskErrorMessages = new ArrayList<String>();
        try {
            while (itr.hasNext()) {
                UnManagedExportMask mask = itr.next();
                if (!VolumeIngestionUtil.validateStoragePortsInVarray(dbClient, volume, vArray, mask.getKnownStoragePortUris(), mask)) {
                    // not a valid mask remove it
                    itr.remove();
                    continue;
                }

                // if required initiators are available in the mask, then choose it,
                // irrespective of actual initiators on the MV on Array
                if (null != mask.getKnownInitiatorUris() && !mask.getKnownInitiatorUris().isEmpty()) {

                    for (Entry<String, Set<String>> entry : clusterIniByProtocol.entrySet()) {
                        _logger.info("Processing Initiators by Protocol {} Group", entry.getValue());
                        _logger.info("Cluster- Comparing discovered [{}] with unmanaged [{}] ", Joiner.on(",").join(entry.getValue()),
                                Joiner.on(",").join(mask.getKnownInitiatorUris()));
                        Set<String> existingknownInisToViprDiscDiff = Sets.difference(mask.getKnownInitiatorUris(),
                                entry.getValue());
                        /**
                         * ViPR initiators || Existing Mask in Array
                         * case 1: I1,I2,I3,I4 I1,I2 -- mask skipped ,as I1,i2 are initiators of 1 single Node in cluster (exlusive export
                         * mode)
                         * case 2: I1,I2 I1,I2,I3 -- mask selected
                         * case 3: I1,I3 I1,I2 -- not selected
                         */
                        if (existingknownInisToViprDiscDiff.isEmpty()) {
                            _logger.info(
                                    "Mask Found {}, as existing ViPR known initiators are a complete subset of ViPR discovered. try to find whether the subset is actually corresponds to 1"
                                            +
                                            "Node in a cluster, if yes then skip this, as the existing mask is meant for Exclusive mode exports.",
                                    mask.getMaskName());
                            if (groupInitiatorsByHost(mask.getKnownInitiatorUris(), dbClient).size() == 1) {
                                _logger.info(
                                        "Skip this unmanaged mask {}, as the mask has only initiators from 1 Node in a cluster, probably meant for exclusive export",
                                        mask.getMaskName());
                            } else {
                                _logger.info("Mask Found {} with a subset of initiators from more than 1 node in a cluster",
                                        mask.getMaskName());
                                if (verifyNumPath(initiatorUris, mask.getZoningMap(), volume, vPoolURI, dbClient)) {
                                    eligibleMasks.add(mask);
                                }
                                itr.remove();
                            }
                        } else {
                            _logger.info("Existing ViPR known Initiators are not a complete subset of ViPR discovered, check whether ViPR discovered are a subset of existing");
                            Set<String> ViPRDiscToExistingKnownInisDiff = Sets.difference(entry.getValue(),
                                    mask.getKnownInitiatorUris());
                            if (ViPRDiscToExistingKnownInisDiff.isEmpty()) {
                                _logger.info("Mask Found {} with a subset of ViPR initiators in existing mask.", mask.getMaskName());
                                if (verifyNumPath(initiatorUris, mask.getZoningMap(), volume, vPoolURI, dbClient)) {
                                    eligibleMasks.add(mask);
                                }
                                itr.remove();
                            }
                        }
                    }
                }
            }

            if (eligibleMasks.isEmpty() && !unManagedMasks.isEmpty()) {
                _logger.info("Unable to find a MV/SG with all the cluster initiators, now trying to group initiators by Host and start the search");
                // return individual Host MVs if found any for each Cluster Node as
                // well, to support exclusive mode volume export.
                for (Set<String> initiatorUriList : initiatorUris) {
                    // if mask is already selected, the no need to run this again
                    if (unManagedMasks.isEmpty()) {
                        break;
                    }
                    _logger.info("Looking a Mask for initiators {} belonging to a cluster node", Joiner.on(",").join(initiatorUriList));
                    Map<String, Set<String>> iniByProtocol = groupInitiatorsByProtocol(initiatorUriList, dbClient);
                    eligibleMasks.addAll(findMatchingExportMaskForHost(volume, unManagedMasks, initiatorUriList,
                            iniByProtocol, dbClient, vArray, vPoolURI, true, clusterInitiators, cluster));
                }
            } else {
                _logger.info("Either masks already found or there are no unmanaged masks available");
            }

        } catch (IngestionException ex) {
            _logger.error(ex.getLocalizedMessage());
            if (!maskErrorMessages.contains(ex.getLocalizedMessage())) {
                maskErrorMessages.add(ex.getLocalizedMessage());
            }
        }

        if (!maskErrorMessages.isEmpty()) {
            String message = maskErrorMessages.size() + " of " + unManagedMasks.size() + " unmanaged export mask(s) failed zoning checks: ";
            String messages = Joiner.on("; ").join(maskErrorMessages);
            _logger.error(message + messages);
            throw IngestionException.exceptions.inconsistentZoningAcrossHosts(message + messages);
        }

        return eligibleMasks;
    }

    /**
     * Given a ZoneInfoMap, check that the hosts in a cluster have a number of
     * paths that is compliant with the vpool specifications.
     * 
     * @param initiatorUris
     *            a list of initiators sets, each set belongs to one host in the
     *            cluster
     * @param zoningMap
     *            the ZoneInfoMap that has the zone mapping between the
     *            UnManagedExportMask initiators and ports
     * @param block
     *            the volume or snapshot for which the zoning are verified
     * @param vPoolURI
     *            - URI of the VPool to ingest blockObject.
     * @param dbClient
     *            an instance of dbclient
     * @return true if the number of paths is valid.
     */
    private static boolean verifyNumPath(List<Set<String>> initiatorUris, ZoneInfoMap zoningMap, BlockObject block,
            URI vPoolURI, DbClient dbClient) {
        DbModelClientImpl dbModelClient = new DbModelClientImpl(dbClient);
        ExportPathParams pathParams = BlockStorageScheduler.getExportPathParam(block, vPoolURI, dbClient);
        for (Set<String> hostInitiatorsUri : initiatorUris) {
            List<Initiator> initiators = CustomQueryUtility.iteratorToList(dbModelClient.find(Initiator.class,
                    StringSetUtil.stringSetToUriList(hostInitiatorsUri)));
            if (hasFCInitiators(initiators)) {
                return verifyHostNumPath(pathParams, initiators, zoningMap);
            }
        }
        return true;
    }

    /**
     * has FC initiators
     * 
     * @param initiators
     * @return
     */
    private static boolean hasFCInitiators(List<Initiator> initiators) {
        for (Initiator initiator : initiators) {
            if (HostInterface.Protocol.FC.toString().equals(initiator.getProtocol())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Given the zoneInfoMap, check the existing paths to make sure they
     * comply with the ingestion vpool requirements.
     * 
     * @param pathParams the ingestion parameter
     * @param initiators the host initiators to be checked
     * @param zoneInfoMap the zoneInfoMap that is stored in the UnManagedExportMask
     * @return true if the host paths are compliant. False otherwise.
     */
    private static boolean verifyHostNumPath(ExportPathParams pathParams,
            List<Initiator> initiators, ZoneInfoMap zoneInfoMap) {
        if (initiators == null || initiators.isEmpty()) {
            _logger.error("Host has no initiators configured.");
            throw IngestionException.exceptions.hostHasNoInitiators();
        }
        int unassignedInitiators = 0;
        int totalPorts = 0;
        StringSetMap zoningMap = ExportMaskUtils.getZoneMapFromZoneInfoMap(zoneInfoMap, initiators);
        if (null == zoningMap || zoningMap.isEmpty()) {
            _logger.error("No zoning information found for the initiators");
            List<String> messageArray = new ArrayList<String>();
            for (Initiator init : initiators) {
                messageArray.add(init.getHostName() + ":" + init.getInitiatorPort());
            }
            throw IngestionException.exceptions.hostHasNoZoning(Joiner.on(", ").join(messageArray));
        }
        String hostName = initiators.get(0).getHostName();
        URI hostURI = initiators.get(0).getHost();
        _logger.info("Checking numpath for host {}", hostName);
        for (Initiator initiator : initiators) {
            if (initiator.getHostName() != null) {
                hostName = initiator.getHostName();
            }
            StringSet ports = zoningMap.get(initiator.getId().toString());
            if (ports == null || ports.isEmpty()) {
                unassignedInitiators++;
                _logger.info("Initiator {} of host {} is not assigned to any ports.",
                        new Object[] { initiator.getInitiatorPort(), hostName });
            } else if (ports.size() != pathParams.getPathsPerInitiator()) {
                _logger.error("Initiator {} of host {} has a different number of ports ({}) than " +
                        "what is required according to the virtual pool ({})", new Object[] { initiator.getInitiatorPort(),
                        hostName, ports.size(), pathParams.getPathsPerInitiator() });
                throw IngestionException.exceptions.hostZoningHasDifferentPortCount(
                        initiator.getInitiatorPort(), hostName,
                        String.valueOf(ports.size()), String.valueOf(pathParams.getPathsPerInitiator()));
            } else {
                totalPorts += ports.size();
                _logger.info("Initiator {} of host {} has {} paths", new Object[] { initiator.getInitiatorPort(),
                        hostName, ports.size(), ports.size() });
            }

        }
        if (totalPorts < pathParams.getMinPaths()) {
            _logger.error(String.format("Host %s (%s) has fewer ports assigned %d than min_paths %d",
                    hostName, hostURI.toString(), totalPorts, pathParams.getMinPaths()));
            throw IngestionException.exceptions.hostZoningHasFewerPorts(hostName,
                    String.valueOf(totalPorts), String.valueOf(pathParams.getMinPaths()));
        }
        if (totalPorts > pathParams.getMaxPaths()) {
            _logger.error(String.format("Host %s (%s) has more ports assigned %d than max_paths %d",
                    hostName, hostURI.toString(), totalPorts, pathParams.getMaxPaths()));
            throw IngestionException.exceptions.hostZoningHasMorePorts(hostName,
                    String.valueOf(totalPorts), String.valueOf(pathParams.getMaxPaths()));
        }
        if (unassignedInitiators > 0) {
            _logger.info(String.format("Host %s (%s) has %d unassigned initiators",
                    hostName, hostURI.toString(), unassignedInitiators));
        }
        return true;
    }

    /**
     * group Initiators by Protocol
     * 
     * @param iniStrList
     * @param dbClient
     * @return
     */
    public static Map<String, Set<String>> groupInitiatorsByProtocol(Set<String> iniStrList, DbClient dbClient) {
        Map<String, Set<String>> iniByProtocol = new HashMap<String, Set<String>>();
        List<URI> iniList = new ArrayList<URI>(Collections2.transform(
                iniStrList, CommonTransformerFunctions.FCTN_STRING_TO_URI));
        List<Initiator> initiators = dbClient.queryObject(Initiator.class, iniList);
        for (Initiator ini : initiators) {
            if (null == ini.getProtocol()) {
                _logger.warn("Initiator {} with protocol set to Null", ini.getId());
                continue;
            }
            if (!iniByProtocol.containsKey(ini.getProtocol())) {
                iniByProtocol.put(ini.getProtocol(), new HashSet<String>());
            }
            iniByProtocol.get(ini.getProtocol()).add(ini.getId().toString());
        }
        return iniByProtocol;

    }

    /**
     * find User Added initiators
     * 
     * @param existingInitiators
     * @param excludeUnmanagedMask
     * @param dbClient
     * @return
     */
    public static List<Initiator> findUserAddedInisFromExistingIniListInMask(List<Initiator> existingInitiators, URI excludeUnmanagedMask,
            DbClient dbClient) {
        List<Initiator> userAddedInis = new ArrayList<Initiator>();
        for (Initiator initiator : existingInitiators) {
            // get All unmanaged masks
            List<URI> unManagedMaskUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getUnManagedExportMaskKnownInitiatorConstraint(initiator.getInitiatorPort()));
            if (null == unManagedMaskUris || unManagedMaskUris.isEmpty()) {
                _logger.info("UnManaged Masks Empty, adding initiator {}..{} to userAddedList", initiator.getId(),
                        initiator.getInitiatorPort());
                userAddedInis.add(initiator);
            } else {
                unManagedMaskUris.remove(excludeUnmanagedMask);
                if (unManagedMaskUris.isEmpty()) {
                    userAddedInis.add(initiator);

                }
                List<UnManagedExportMask> unManagedMasks = dbClient.queryObject(UnManagedExportMask.class, unManagedMaskUris);
                for (UnManagedExportMask unManagedMask : unManagedMasks) {
                    ExportMask exportMask = getExportsMaskAlreadyIngested(unManagedMask, dbClient);

                    if (null != exportMask && exportMask.getCreatedBySystem()) {
                        _logger.info(
                                "ViPR created/ingested Export Mask {} found for unmanaged mask, adding initiator {}..{} to userAddedList",
                                new Object[] { exportMask.getMaskName(), initiator.getId(), initiator.getInitiatorPort() });
                        // update the userAddedInitiators for those already ingested export mask as well.
                        exportMask.addToUserCreatedInitiators(initiator);
                        exportMask.addToExistingInitiatorsIfAbsent(initiator);
                        exportMask.addInitiator(initiator);
                        dbClient.updateAndReindexObject(exportMask);
                        userAddedInis.add(initiator);
                    } else {
                        _logger.info(
                                "UnManaged Mask {} doesn't have any ViPR created export masks, skipping initiator from user added list",
                                unManagedMask.getMaskName(), initiator.getId());
                    }
                }
            }
        }
        _logger.info("User Added Initiators found {}", Joiner.on(",").join(userAddedInis));
        return userAddedInis;
    }

    /**
     * Group Inis by Host
     * 
     * @param iniStrList
     * @param dbClient
     * @return
     */
    private static Map<String, Set<String>> groupInitiatorsByHost(Set<String> iniStrList, DbClient dbClient) {
        Map<String, Set<String>> iniByHost = new HashMap<String, Set<String>>();
        List<URI> iniList = new ArrayList<URI>(Collections2.transform(
                iniStrList, CommonTransformerFunctions.FCTN_STRING_TO_URI));
        List<Initiator> initiators = dbClient.queryObject(Initiator.class, iniList);
        for (Initiator ini : initiators) {
            if (null == ini.getHost()) {
                _logger.warn("Initiator {} with Host set to Null", ini.getId());
                continue;
            }
            if (!iniByHost.containsKey(ini.getHost())) {
                iniByHost.put(ini.getHost().toString(), new HashSet<String>());
            }
            iniByHost.get(ini.getHost().toString()).add(ini.getId().toString());
        }
        return iniByHost;

    }

    /**
     * Verify Export Group exists
     * 
     * @param project
     * @param computeResource
     * @param vArray
     * @param resourceType
     * @param dbClient
     * @return
     */
    public static ExportGroup verifyExportGroupExists(URI project, URI computeResource, URI vArray, String resourceType,
            DbClient dbClient) {

        List<URI> exportGroupUris = dbClient.queryByConstraint(ContainmentConstraint.Factory.getProjectExportGroupConstraint(project));
        List<ExportGroup> exportGroups = dbClient.queryObject(ExportGroup.class, exportGroupUris);

        if (null == exportGroups || exportGroups.isEmpty()) {
            return null;
        }
        for (ExportGroup exportGroup : exportGroups) {
            if (exportGroup.getVirtualArray().equals(vArray)) {

                if (ExportGroup.ExportGroupType.Host.toString().equalsIgnoreCase(resourceType)) {
                    if (exportGroup.hasHost(computeResource) &&
                            !ExportGroup.ExportGroupType.Cluster.toString().equalsIgnoreCase(exportGroup.getType())) {
                        _logger.info("Export Groups {} matching Varray/Project/ComputeResource exists", exportGroup.getId());
                        return exportGroup;
                    }
                } else if (ExportGroup.ExportGroupType.Cluster.toString().equalsIgnoreCase(resourceType)) {
                    if (exportGroup.hasCluster(computeResource)) {
                        _logger.info("Export Groups {} matching Varray/Project/ComputeResource exists", exportGroup.getId());
                        return exportGroup;
                    }
                }

            }
        }

        return null;
    }

    /**
     * Get UnManaged Volumes associated with Host
     * 
     * @param hostUri
     * @param dbClient
     * @return
     */
    public static List<UnManagedVolume> findUnManagedVolumesForHost(URI hostUri, DbClient dbClient) {

        _logger.info("finding unmanaged volumes for host " + hostUri);
        List<UnManagedVolume> unmanagedVolumes = new ArrayList<UnManagedVolume>();
        List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, hostUri);
        Map<String, UnManagedExportMask> cache = new HashMap<String, UnManagedExportMask>();
        Host host = dbClient.queryObject(Host.class, hostUri);
        Set<String> clusterInis = new HashSet<String>();
        /**
         * If host is part of a cluster, then unmanaged volumes which are exclusive to this host will be selected
         * Get the remaining Host initiators of this cluster, and if there is at least a match between the cluster inis and
         * the initiators the unmanaged volume is exposed, then the volume will be skipped.
         */
        if (!NullColumnValueGetter.isNullURI(host.getCluster())) {
            List<URI> hostUris = ComputeSystemHelper.getChildrenUris(dbClient, host.getCluster(), Host.class, "cluster");
            hostUris.remove(hostUri);
            for (URI uri : hostUris) {
                List<URI> inis = dbClient.queryByConstraint(ContainmentConstraint.Factory.getContainedObjectsConstraint(uri,
                        Initiator.class, "host"));
                clusterInis.addAll(Collections2.transform(inis, CommonTransformerFunctions.FCTN_URI_TO_STRING));
            }
        }

        Set<URI> unManagedVolumeUris = new HashSet<URI>();
        URIQueryResultList results = new URIQueryResultList();
        for (Initiator initiator : initiators) {
            _logger.info("      looking at initiator " + initiator.getInitiatorPort());
            dbClient.queryByConstraint(AlternateIdConstraint.
                    Factory.getUnManagedVolumeInitiatorNetworkIdConstraint(initiator.getInitiatorPort()), results);
            if (results.iterator() != null) {
                for (URI uri : results) {
                    _logger.debug("      found UnManagedVolume " + uri);
                    unManagedVolumeUris.add(uri);
                }
            }
        }
        _logger.info("Found Unmanaged volumes {} associated to Host {}", Joiner.on(",").join(unManagedVolumeUris), hostUri);
        for (URI unmanagedVolumeUri : unManagedVolumeUris) {
            UnManagedVolume unmanagedVolume = dbClient.queryObject(UnManagedVolume.class, unmanagedVolumeUri);
            if (unmanagedVolume == null || unmanagedVolume.getInactive() == true) {
                continue;
            }
            Set<String> inisOfunManagedMask = getInitiatorsOfUnmanagedExportMask(unmanagedVolume, cache, dbClient);
            Set<String> interSection = Sets.intersection(clusterInis, inisOfunManagedMask);
            if (interSection.isEmpty()) {
                unmanagedVolumes.add(unmanagedVolume);
                _logger.info("   volume: " + unmanagedVolume.getLabel() + " nativeGuid: " + unmanagedVolume.getNativeGuid());
            } else {
                _logger.info("UnManagedVolume {} is exposed to cluster as well, skipping", unmanagedVolume.getNativeGuid());
            }

        }

        if (unmanagedVolumes.isEmpty()) {
            _logger.info("   did not find any unmanaged volumes for this host");
        }

        return unmanagedVolumes;
    }

    /**
     * Get initiators of unmanaged export mask
     * 
     * @param unManagedVolume
     * @param cache
     * @param dbClient
     * @return
     */
    private static Set<String> getInitiatorsOfUnmanagedExportMask(UnManagedVolume unManagedVolume,
            Map<String, UnManagedExportMask> cache, DbClient dbClient) {
        Set<String> inis = new HashSet<String>();
        for (String eMaskUri : unManagedVolume.getUnmanagedExportMasks()) {
            UnManagedExportMask unManagedExportMask = cache.get(eMaskUri);
            if (null == unManagedExportMask) {
                unManagedExportMask = dbClient.queryObject(UnManagedExportMask.class, URI.create(eMaskUri));
            }
            if (unManagedExportMask != null) {
                cache.put(unManagedExportMask.getId().toString(), unManagedExportMask);
                inis.addAll(unManagedExportMask.getKnownInitiatorUris());
            }
        }
        return inis;
    }

    /**
     * get UnManaged Volumes associated with Cluster
     * 
     * @param clusterUri
     * @param dbClient
     * @return
     */
    public static List<UnManagedVolume> findUnManagedVolumesForCluster(URI clusterUri, DbClient dbClient) {

        _logger.info("finding unmanaged volumes for cluster " + clusterUri);
        Set<URI> consistentVolumeUris = new HashSet<URI>();
        List<URI> hostUris = ComputeSystemHelper.getChildrenUris(dbClient, clusterUri, Host.class, "cluster");

        int hostIndex = 0;
        for (URI hostUri : hostUris) {
            _logger.info("   looking at host " + hostUri);
            List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, hostUri);
            URIQueryResultList results = new URIQueryResultList();
            Set<URI> unManagedVolumeUris = new HashSet<URI>();
            for (Initiator initiator : initiators) {
                _logger.info("      looking at initiator " + initiator.getInitiatorPort());
                dbClient.queryByConstraint(AlternateIdConstraint.
                        Factory.getUnManagedVolumeInitiatorNetworkIdConstraint(initiator.getInitiatorPort()), results);
                if (results.iterator() != null) {
                    for (URI uri : results) {
                        _logger.info("      found UnManagedVolume " + uri);
                        unManagedVolumeUris.add(uri);
                    }
                }
            }
            Set<URI> thisHostsUris = new HashSet<URI>();
            for (URI unmanagedVolumeUri : unManagedVolumeUris) {
                if (hostIndex == 0) {
                    // on the first host, just add all UnManagedVolumes that were found
                    consistentVolumeUris.add(unmanagedVolumeUri);
                } else {
                    // on subsequent hosts, create a collection to use in diffing the sets
                    thisHostsUris.add(unmanagedVolumeUri);
                }
            }

            if (hostIndex > 0) {
                // retain only UnManagedVolumes that are found exposed to all hosts in the cluster
                consistentVolumeUris.retainAll(thisHostsUris);
            }
            hostIndex++;
        }

        _logger.info("   found {} UnManagedVolumes to be consistent across all hosts", consistentVolumeUris.size());
        List<UnManagedVolume> unmanagedVolumes = new ArrayList<UnManagedVolume>();
        for (URI unmanagedVolumeUri : consistentVolumeUris) {
            UnManagedVolume unmanagedVolume = dbClient.queryObject(UnManagedVolume.class, unmanagedVolumeUri);
            if (unmanagedVolume == null || unmanagedVolume.getInactive() == true) {
                continue;
            }
            unmanagedVolumes.add(unmanagedVolume);
            _logger.info("      volume: " + unmanagedVolume.getLabel() + " nativeGuid: " + unmanagedVolume.getNativeGuid());
        }

        return unmanagedVolumes;
    }

    /**
     * get UnManaged ExportMasks for Host
     * 
     * @param hostUri
     * @param dbClient
     * @return
     */
    public static List<UnManagedExportMask> findUnManagedExportMasksForHost(URI hostUri, DbClient dbClient) {

        _logger.info("finding unmanaged export masks for host " + hostUri);
        List<UnManagedExportMask> uems = new ArrayList<UnManagedExportMask>();
        List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, hostUri);

        Set<URI> uemUris = new HashSet<URI>();
        URIQueryResultList results = new URIQueryResultList();
        for (Initiator initiator : initiators) {
            _logger.info("      looking at initiator " + initiator.getInitiatorPort());
            dbClient.queryByConstraint(AlternateIdConstraint.
                    Factory.getUnManagedExportMaskKnownInitiatorConstraint(initiator.getInitiatorPort()), results);
            if (results.iterator() != null) {
                for (URI uri : results) {
                    _logger.info("      found UnManagedExportMask " + uri);
                    uemUris.add(uri);
                }
            }
        }
        for (URI uemUri : uemUris) {
            UnManagedExportMask uem = dbClient.queryObject(UnManagedExportMask.class, uemUri);
            if (uem == null || uem.getInactive() == true) {
                continue;
            }
            uems.add(uem);
            _logger.info("   maskName: " + uem.getMaskName() + " nativeGuid: " + uem.getNativeGuid());
        }
        if (uems.isEmpty()) {
            _logger.info("   did not find any unmanaged export masks for this host");
        }

        return uems;
    }

    /**
     * get UnManaged Export Masks for Cluster
     * 
     * @param clusterUri
     * @param dbClient
     * @return
     */
    public static List<UnManagedExportMask> findUnManagedExportMasksForCluster(URI clusterUri, DbClient dbClient) {

        _logger.info("finding unmanaged export masks for cluster " + clusterUri);
        Set<URI> consistentUemUris = new HashSet<URI>();
        List<URI> hostUris = ComputeSystemHelper.getChildrenUris(dbClient, clusterUri, Host.class, "cluster");

        for (URI hostUri : hostUris) {
            _logger.info("   looking at host " + hostUri);
            List<Initiator> initiators = ComputeSystemHelper.queryInitiators(dbClient, hostUri);

            URIQueryResultList results = new URIQueryResultList();
            for (Initiator initiator : initiators) {
                _logger.info("      looking at initiator " + initiator.getInitiatorPort());
                dbClient.queryByConstraint(AlternateIdConstraint.
                        Factory.getUnManagedExportMaskKnownInitiatorConstraint(initiator.getInitiatorPort()), results);
                if (results.iterator() != null) {
                    for (URI uri : results) {
                        _logger.info("      found UnManagedExportMask " + uri);
                        consistentUemUris.add(uri);
                    }
                }
            }
        }

        _logger.info("   found {} UnManagedExportMasks for this cluster", consistentUemUris.size());
        List<UnManagedExportMask> uems = new ArrayList<UnManagedExportMask>();
        for (URI uemUri : consistentUemUris) {
            UnManagedExportMask uem = dbClient.queryObject(UnManagedExportMask.class, uemUri);
            if (uem == null || uem.getInactive() == true) {
                continue;
            }
            uems.add(uem);
            _logger.info("      maskName: " + uem.getMaskName() + " nativeGuid: " + uem.getNativeGuid());
        }

        return uems;
    }

    /**
     * TODO - move this to common Utils.
     * check Snapshot exists in DB
     * 
     * @param nativeGuid
     * @param dbClient
     * @return
     * @throws IOException
     */
    public static BlockSnapshot checkSnapShotExistsInDB(String nativeGuid, DbClient dbClient) {
        List<BlockSnapshot> activeSnapshots = CustomQueryUtility.getActiveBlockSnapshotByNativeGuid(
                dbClient, nativeGuid);
        Iterator<BlockSnapshot> snapshotItr = activeSnapshots.iterator();
        return snapshotItr.hasNext() ? snapshotItr.next() : null;
    }

    /**
     * Can unmanaged volume be deleted
     * 
     * @param unManagedVolume
     * @return
     */
    public static boolean canDeleteUnManagedVolume(UnManagedVolume unManagedVolume) {
        return null == unManagedVolume.getUnmanagedExportMasks()
                || unManagedVolume.getUnmanagedExportMasks().isEmpty();
    }

    /**
     * TODO : Need to move all these DB checks to DBSvc project
     * check Volume exists in DB
     * 
     * @param volumeNativeGuid
     * @param dbClient
     * @return
     */
    public static Volume checkIfVolumeExistsInDB(String volumeNativeGuid,
            DbClient dbClient) {
        List<Volume> activeVolumes = CustomQueryUtility.getActiveVolumeByNativeGuid(
                dbClient, volumeNativeGuid);
        Iterator<Volume> volumeItr = activeVolumes.iterator();
        return volumeItr.hasNext() ? volumeItr.next() : null;
    }

    /**
     * TODO : Need to move all these DB checks to DBSvc project
     * check Volume exists in DB
     * 
     * @param mirrorNativeGuid
     * @param dbClient
     * @return
     */
    public static BlockMirror checkIfBlockMirrorExistsInDB(String mirrorNativeGuid, DbClient dbClient) {
        List<BlockMirror> activeMirrors = CustomQueryUtility.getActiveBlockMirrorByNativeGuid(dbClient,
                mirrorNativeGuid);
        Iterator<BlockMirror> mirrorItr = activeMirrors.iterator();
        return mirrorItr.hasNext() ? mirrorItr.next() : null;
    }

    /**
     * Gets the URI of the high availability virtual array for the given
     * volume, or null if not available.
     * 
     * @param volume the Volume to check
     * @param dbClient a database client instance
     * 
     * @return the high availability virtual array URI, or null if not found
     */
    public static URI checkVplexHighAvailabilityArray(Volume volume, DbClient dbClient) {
        URI haVarray = null;
        VirtualPool vpool = dbClient.queryObject(VirtualPool.class, volume.getVirtualPool());
        if ((null != vpool) && VirtualPool.vPoolSpecifiesHighAvailability(vpool)) {
            _logger.info("volume {} virtual pool {} specifies high availability",
                    volume.getLabel(), vpool.getLabel());
            if (VirtualPool.HighAvailabilityType.vplex_distributed
                    .name().equals(vpool.getHighAvailability())) {
                haVarray = VPlexUtil.getHAVarray(vpool);
                _logger.info("   the high availability virtual array URI is " + haVarray);
            }
        }
        return haVarray;
    }

    public static void setupSnapParentRelations(BlockObject snapshot, BlockObject parentVolume, DbClient dbClient) {
        _logger.info("Setting up relationship between snapshot {} and parent {}", snapshot.getId(), parentVolume.getId());
        ((BlockSnapshot) snapshot).setSourceNativeId(parentVolume.getNativeId());
        ((BlockSnapshot) snapshot).setParent(new NamedURI(parentVolume.getId(), parentVolume.getLabel()));
        snapshot.setProtocol(new StringSet());
        snapshot.getProtocol().addAll(parentVolume.getProtocol());
        URI cgUri = parentVolume.getConsistencyGroup();
        if (cgUri != null) {
            snapshot.setConsistencyGroup(cgUri);
        }
        // TODO - check how to populate snapsetlabel if in consistency group
    }

    public static void setupMirrorParentRelations(BlockObject mirror, BlockObject parent, DbClient dbClient) {
        _logger.info("Setting up relationship between mirror {} and parent {}", mirror.getId(), parent.getId());
        ((BlockMirror) mirror).setSource(new NamedURI(parent.getId(), parent.getLabel()));
        if (parent instanceof Volume) {
            StringSet mirrors = ((Volume) parent).getMirrors();
            if (mirrors == null) {
                mirrors = new StringSet();
            }
            mirrors.add(mirror.getId().toString());
            ((Volume) parent).setMirrors(mirrors);
        }
    }

    public static void setupSRDFParentRelations(BlockObject targetBlockObj, BlockObject sourceBlockObj, DbClient dbClient) {
        _logger.info("Setting up relationship between mirror {} and parent {}", targetBlockObj.getId(), sourceBlockObj.getId());
        Volume targetVolume = (Volume) targetBlockObj;
        Volume sourceVolume = (Volume) sourceBlockObj;
        targetVolume.setSrdfParent(new NamedURI(sourceBlockObj.getId(), sourceBlockObj.getLabel()));
        targetVolume.setPersonality(PersonalityTypes.TARGET.toString());
        if (sourceBlockObj instanceof Volume) {
            StringSet srdfTargets = sourceVolume.getSrdfTargets();
            if (null == srdfTargets) {
                srdfTargets = new StringSet();
            }
            srdfTargets.add(targetVolume.getId().toString());
            sourceVolume.setSrdfTargets(srdfTargets);
            sourceVolume.setPersonality(PersonalityTypes.SOURCE.toString());
        }
    }

    public static void setupCloneParentRelations(BlockObject clone, BlockObject parent, DbClient dbClient) {
        _logger.info("Setting up relationship between clone {} and parent {}", clone.getId(), parent.getId());
        ((Volume) clone).setAssociatedSourceVolume(parent.getId());
        if (parent instanceof Volume) {
            Volume sourceVolume = (Volume) parent;
            StringSet associatedFullCopies = sourceVolume.getFullCopies();
            if (associatedFullCopies == null) {
                associatedFullCopies = new StringSet();
                sourceVolume.setFullCopies(associatedFullCopies);
            }
            associatedFullCopies.add(clone.getId().toString());
        }
    }

    /**
     * This method will clear the internal flags set during ingestion.
     * Before clearing the flags check if there is any unmanaged volume corresponding to the block object.
     * If yes, then perform the below operations:
     * 1) Find all the export masks corresponding to the unmanaged export masks of the unmanaged volume.
     * For each export mask:
     * a) Remove the block object from existing volumes of the export mask
     * b) Add the block object to user created volumes of the export mask
     * 2) Get the export groups corresponding to the export masks and add the block object to the export groups
     * 3) Remove the unmanaged volume from the unmanaged export mask
     * 4) Remove the unmanaged export mask from the unmanaged volume
     * 
     * @param blockObject
     * @param dbClient
     */
    public static void clearInternalFlags(BlockObject blockObject, List<DataObject> updatedObjects, DbClient dbClient) {
        // for each block object, get the corresponding unmanaged volume.
        List<UnManagedExportMask> uemsToPersist = new ArrayList<UnManagedExportMask>();
        String unmanagedVolumeGUID = blockObject.getNativeGuid().replace(VOLUME, UNMANAGEDVOLUME);
        List<URI> unmanagedVolumeUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getVolumeInfoNativeIdConstraint(unmanagedVolumeGUID));
        List<UnManagedVolume> unManagedVolumes = dbClient.queryObject(UnManagedVolume.class, unmanagedVolumeUris);
        if (unManagedVolumes != null && !unManagedVolumes.isEmpty()) {
            UnManagedVolume unManagedVolume = unManagedVolumes.get(0);
            // Get the exportGroupType from the unManagedVolume
            String exportGroupType = unManagedVolume.getVolumeCharacterstics().get(
                    SupportedVolumeCharacterstics.EXPORTGROUP_TYPE.toString());

            // If there are unmanaged export masks, get the corresponding ViPR export masks
            StringSet unmanagedExportMasks = unManagedVolume.getUnmanagedExportMasks();
            if (null != unmanagedExportMasks && !unmanagedExportMasks.isEmpty()) {
                List<URI> unManagedMaskUris = new ArrayList<URI>(Collections2.transform(unmanagedExportMasks,
                        CommonTransformerFunctions.FCTN_STRING_TO_URI));
                List<UnManagedExportMask> unManagedMasks = dbClient.queryObject(UnManagedExportMask.class, unManagedMaskUris);
                for (UnManagedExportMask unManagedExportMask : unManagedMasks) {
                    Set<ExportMask> exportMasks = new HashSet<ExportMask>();
                    List<URI> initiatorUris = new ArrayList<URI>(Collections2.transform(
                            unManagedExportMask.getKnownInitiatorUris(), CommonTransformerFunctions.FCTN_STRING_TO_URI));
                    for (URI ini : initiatorUris) {
                        List<URI> exportMaskUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory
                                .getExportMaskInitiatorConstraint(ini.toString()));
                        if (null == exportMaskUris) {
                            continue;
                        }
                        for (URI eMaskUri : exportMaskUris) {
                            ExportMask eMask = dbClient.queryObject(ExportMask.class, eMaskUri);
                            if (eMask.getStorageDevice().equals(unManagedExportMask.getStorageSystemUri())) {
                                _logger.info("Found Mask {} with matching initiator and matching Storage System", eMaskUri);
                                exportMasks.add(eMask);
                            } else {
                                _logger.info("Found Mask {} with matching initiator and unmatched Storage System. Skipping mask", eMaskUri);
                            }
                        }
                    }

                    Set<ExportGroup> exportGroups = new HashSet<ExportGroup>();
                    // Remove the block object from existing volumes and add to the user created volumes of the export mask
                    for (ExportMask exportMask : exportMasks) {
                        String normalizedWWN = BlockObject.normalizeWWN(blockObject.getWWN());
                        if (exportMask.hasAnyExistingVolumes() && exportMask.getExistingVolumes().containsKey(normalizedWWN)) {
                            _logger.info(
                                    "Removing block object {} from existing volumes and adding to user created volumes of export mask {}",
                                    blockObject.getNativeGuid(), exportMask.getMaskName());
                            exportMask.removeFromExistingVolumes(blockObject);
                            exportMask.addToUserCreatedVolumes(blockObject);
                            exportGroups.addAll(ExportMaskUtils.getExportGroups(dbClient, exportMask));
                        }
                    }
                    updatedObjects.addAll(exportMasks);

                    // Add the block object to the export groups corresponding to the export masks
                    for (ExportGroup exportGroup : exportGroups) {
                        _logger.info("Processing exportGroup {} to add block object", exportGroup.getId());
                        // only add to those export groups whose project and varray matches the block object
                        if (exportGroup.getProject().getURI().equals(getBlockProject(blockObject)) &&
                                exportGroup.getVirtualArray().equals(blockObject.getVirtualArray()) &&
                                (null != exportGroupType && exportGroupType.equalsIgnoreCase(exportGroup.getType()))) {
                            _logger.info("Adding block object {} to export group {}", blockObject.getNativeGuid(), exportGroup.getLabel());
                            exportGroup.addVolume(blockObject.getId(), ExportGroup.LUN_UNASSIGNED);
                        }
                    }
                    updatedObjects.addAll(exportGroups);

                    unManagedVolume.getUnmanagedExportMasks().remove(unManagedExportMask.getId().toString());
                    unManagedExportMask.getUnmanagedVolumeUris().remove(unManagedVolume.getId().toString());
                    uemsToPersist.add(unManagedExportMask);
                }
            } else {
                _logger.info("No unmanaged export masks found for the unmanaged volume {}", unManagedVolumes.get(0).getNativeGuid());
            }
            updatedObjects.addAll(uemsToPersist);

            if (canDeleteUnManagedVolume(unManagedVolume)) {
                _logger.info("Set unmanaged volume inactive {}", unManagedVolume.getId());
                unManagedVolume.setInactive(true);
            }
            updatedObjects.add(unManagedVolume);
        } else {
            _logger.info("No unmanaged volume found for the object {}", blockObject.getNativeGuid());
        }

        blockObject.clearInternalFlags(BlockIngestOrchestrator.INTERNAL_VOLUME_FLAGS);
    }

    public static BlockObject getBlockObject(String nativeGUID, DbClient dbClient) {
        BlockObject blockObject = null;
        _logger.info("Checking for unmanagedvolume {} [Volume] ingestion status.", nativeGUID);
        List<URI> blockObjectUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getVolumeNativeGuidConstraint(nativeGUID));
        if (!blockObjectUris.isEmpty()) {
            _logger.info("Found volume {} ingested.", nativeGUID);
            return BlockObject.fetch(dbClient, blockObjectUris.get(0));
        }
        _logger.info("Checking for unmanagedvolume {} [Snap] ingestion status", nativeGUID);
        blockObjectUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getBlockSnapshotsByNativeGuid(nativeGUID));

        if (!blockObjectUris.isEmpty()) {
            _logger.info("Found snapshot {} ingested.", nativeGUID);
            return BlockObject.fetch(dbClient, blockObjectUris.get(0));
        }

        _logger.info("Checking for unmanagedvolume {} [Mirror] ingestion status", nativeGUID);
        blockObjectUris = dbClient.queryByConstraint(AlternateIdConstraint.Factory.getMirrorByNativeGuid(nativeGUID));
        if (!blockObjectUris.isEmpty()) {
            _logger.info("Found mirror {} ingested.", nativeGUID);
            return BlockObject.fetch(dbClient, blockObjectUris.get(0));
        }

        return blockObject;
    }

    public static Set<String> getUnIngestedReplicas(StringSet replicaVoluemGUIDs, List<BlockObject> replicaObjects) {
        StringSet replicas = new StringSet();
        for (BlockObject replica : replicaObjects) {
            replicas.add(replica.getNativeGuid().replace(VolumeIngestionUtil.VOLUME, VolumeIngestionUtil.UNMANAGEDVOLUME));
        }

        return Sets.difference(replicaVoluemGUIDs, replicas);
    }

    public static URI getBlockProject(BlockObject block) {
        if (block.getClass() == Volume.class) {
            return ((Volume) block).getProject().getURI();
        } else if (block.getClass() == BlockSnapshot.class) {
            return ((BlockSnapshot) block).getProject().getURI();
        } else if (block.getClass() == BlockMirror.class) {
            return ((BlockMirror) block).getProject().getURI();
        }

        return null;
    }

    /**
     * Checks if a volume was ingested. An exception will be
     * thrown if the given operation is not supported on ingested volumes.
     * 
     * @param volume the Volume in question
     * @param operation a text description of the operation
     *            (for use in the Exception message)
     * @param dbClient Reference to a database client
     */
    public static void checkOperationSupportedOnIngestedVolume(Volume volume,
            ResourceOperationTypeEnum operation, DbClient dbClient) {
        if (volume.isIngestedVolume(dbClient)) {
            switch (operation) {
                case CREATE_VOLUME_FULL_COPY:
                case CREATE_VOLUME_SNAPSHOT:
                case EXPAND_BLOCK_VOLUME:
                case CREATE_VOLUME_MIRROR:
                case CHANGE_BLOCK_VOLUME_VARRAY:
                case UPDATE_CONSISTENCY_GROUP:
                    _logger.error("Operation {} is not permitted on ingested volumes.", operation.getName());
                    throw APIException.badRequests.operationNotPermittedOnIngestedVolume(
                            operation.getName(), volume.getLabel());
                default:
                    return;
            }
        }
    }
}
