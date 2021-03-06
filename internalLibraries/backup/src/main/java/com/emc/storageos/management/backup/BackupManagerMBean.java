/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.management.backup;

import java.util.List;

public interface BackupManagerMBean {

    /**
     * Creates DB/ZK backup according to parameter (name of backup)
     * 
     * @param backupTag
     *            The name of backup
     */
    public void create(final String backupTag);

    /**
     * Lists all backup files on local
     * 
     * @return list of backup files
     */
    public List<BackupSetInfo> list();

    /**
     * Deletes specified backup file
     * 
     * @param backupTag
     *            The name of backup which will be deleted
     */
    public void delete(final String backupTag);

    /**
     * Gets disk quota for backup files in gigabyte
     */
    public int getQuotaGb();

}
