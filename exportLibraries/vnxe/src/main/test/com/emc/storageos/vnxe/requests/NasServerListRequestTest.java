/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.vnxe.requests;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.emc.storageos.services.util.EnvConfig;
import com.emc.storageos.vnxe.models.VNXeBase;
import com.emc.storageos.vnxe.models.VNXeNfsServer;

public class NasServerListRequestTest {
    private static KHClient _client;
    private static String host = EnvConfig.get("sanity", "vnxe.host");
    private static String userName = EnvConfig.get("sanity", "vnxe.username");
    private static String password = EnvConfig.get("sanity", "vnxe.password");

    @BeforeClass
    public static void setup() throws Exception {
        synchronized (_client) {
            _client = new KHClient(host, userName, password);
        }

    }

    @Test
    public void getTest() {
        NfsServerListRequest req = new NfsServerListRequest(_client);
        List<VNXeNfsServer> list = req.get();
        for (VNXeNfsServer nfsServer : list) {
            String id = nfsServer.getId();
            System.out.println(id);
            List<VNXeBase> fileInterfaces = nfsServer.getFileInterfaces();
            System.out.println(fileInterfaces.size());
            for (VNXeBase finterface : fileInterfaces) {
                System.out.println(finterface.getId());
            }
        }
    }

}
