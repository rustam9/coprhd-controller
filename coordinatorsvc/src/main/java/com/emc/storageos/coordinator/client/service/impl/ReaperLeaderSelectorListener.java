/*
 * Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.coordinator.client.service.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.emc.storageos.coordinator.client.service.impl.LeaderSelectorListenerImpl;
import org.apache.curator.framework.recipes.locks.Reaper;
import org.apache.curator.framework.recipes.locks.ChildReaper;

/**
 * LeaderSelectorListenerImpl for Reaper
 */
public class ReaperLeaderSelectorListener extends LeaderSelectorListenerImpl {
    private static final Log _log = LogFactory.getLog(ReaperLeaderSelectorListener.class);

    private String _reaperPath;
    private ChildReaper _mutexReaper = null;

    public ReaperLeaderSelectorListener(String reaperPath) {
        _reaperPath = reaperPath;
    }

    public void startLeadership() throws Exception {
        try {
            _mutexReaper = new ChildReaper(_curatorClient,
                    _reaperPath, Reaper.Mode.REAP_INDEFINITELY);
            _mutexReaper.start();
            _log.info("Child reaper started.");
        } catch (Exception e) {
            _log.error("Child reaper start threw", e);
        }
    }

    public void stopLeadership() {
        try {
            if (_mutexReaper != null) {
                _mutexReaper.close();
                _log.info("Child reaper stopped.");
            }
        } catch (Exception e) {
            _log.error("Child reaper stop threw", e);
        }
    }
}
